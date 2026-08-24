package com.pikowalker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pikowalker.app.MainActivity
import com.pikowalker.app.PikStepApp
import com.pikowalker.app.debug.CrashLogger
import com.pikowalker.app.debug.DebugLogger
import com.pikowalker.app.health.HealthConnectHelper
import com.pikowalker.app.location.LocationSimulator
import com.pikowalker.app.settings.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "pikstep_walk"
        const val NOTIF_ID = 1001
        const val ACTION_HOLD         = "com.pikowalker.app.HOLD"
        const val ACTION_START_ROUTE  = "com.pikowalker.app.START_ROUTE"
        const val ACTION_RESUME_ROUTE = "com.pikowalker.app.RESUME_ROUTE"
        const val ACTION_STOP_ROUTE   = "com.pikowalker.app.STOP_ROUTE"
        const val ACTION_STOP         = "com.pikowalker.app.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_SEC = 30 * 60

        fun holdIntent(context: Context, lat: Double, lng: Double) =
            Intent(context, MockLocationService::class.java).apply {
                action = ACTION_HOLD
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
            }

        /** Starts walking the route from the beginning (restarts progress and stats). */
        fun startRouteIntent(context: Context) =
            Intent(context, MockLocationService::class.java).apply { action = ACTION_START_ROUTE }

        /** Resumes walking a previously-paused route from wherever it left off. */
        fun resumeRouteIntent(context: Context) =
            Intent(context, MockLocationService::class.java).apply { action = ACTION_RESUME_ROUTE }

        /** Stops walking the route only — settles into a static hold wherever the route
         *  currently is. Fake GPS stays on; only [stopIntent] turns it off entirely. */
        fun stopRouteIntent(context: Context) =
            Intent(context, MockLocationService::class.java).apply { action = ACTION_STOP_ROUTE }

        fun stopIntent(context: Context) =
            Intent(context, MockLocationService::class.java).apply { action = ACTION_STOP }
    }

    private val repo get() = (applicationContext as PikStepApp).walkRepository

    // Without this, any uncaught exception in a background tick (location push, Health Connect
    // write, notification update — not just mock-location calls, which already catch their own
    // failures) propagates to the thread's default handler and takes down the whole app process.
    // A background hiccup should surface as an in-app warning, never a hard crash.
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, e ->
        DebugLogger.log("Service", "背景執行緒發生未預期例外，已攔截：$e")
        repo.setError("背景定位服務發生未預期的錯誤\n請重新開始偽造GPS")
        reportCaughtException("MockLocationService/scope", e)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + serviceExceptionHandler)
    private lateinit var locationSimulator: LocationSimulator
    private lateinit var healthConnectHelper: HealthConnectHelper
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: kotlinx.coroutines.Job? = null

    private var totalSteps = 0L
    private var fractionalSteps = 0.0   // accumulates sub-step remainder for accurate counting
    private var stepsAtLastInsert = 0L  // totalSteps value at last HC insert
    private var elapsedSeconds = 0
    private var lastHcInsertMs = 0L
    private var lastCaughtReportMs = 0L

    /** Persists at most one caught-exception report per minute — a tick failure that keeps
     *  recurring (e.g. once a second) would otherwise spam the capped [CrashLogger] storage and
     *  evict earlier, possibly more informative reports within seconds. One example per window
     *  is enough; the live [DebugLogger] buffer already has the full blow-by-blow for as long as
     *  the current process stays alive. */
    private fun reportCaughtException(tag: String, e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastCaughtReportMs < 60_000L) return
        lastCaughtReportMs = now
        CrashLogger.writeCaughtReport(this, tag, e)
    }

    private fun stepsPerSecond(speedKmh: Double) = (speedKmh * 1000.0 / 3600.0) / 0.75

    override fun onCreate() {
        super.onCreate()
        DebugLogger.log("Service", "onCreate pid=${android.os.Process.myPid()}")
        locationSimulator = LocationSimulator(this)
        locationSimulator.onPersistentFailure = {
            repo.setError("模擬定位似乎被系統關閉，已嘗試自動修復但未成功\n請完全停止再重新開始偽造GPS")
        }
        healthConnectHelper = HealthConnectHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log("Service", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_HOLD -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startForegroundCompat()
                loopJob?.cancel()
                val ready = locationSimulator.start()
                if (!ready) {
                    repo.setError("無法啟動模擬位置\n請在開發人員選項中將 PikoWalker 設定為虛擬位置應用程式")
                    stopSelf()
                    return START_NOT_STICKY
                }
                locationSimulator.teleport(lat, lng)
                repo.updateCurrentPosition(lat, lng)
                repo.setSimulating(true)
                repo.setWalkingRoute(false)
                repo.setError(null)
                updateNotification("📍 靜止定位中")
                acquireWakeLock()
                startHoldLoop()
                return START_STICKY
            }
            ACTION_START_ROUTE -> {
                val state = repo.currentState
                // Fake GPS must already be on — this action never turns it on or off itself.
                if (!state.isSimulating || state.waypoints.size < 2) return START_NOT_STICKY
                loopJob?.cancel()
                locationSimulator.setWaypoints(state.waypoints.map { it.lat to it.lng })
                repo.resetStats()
                repo.setWalkingRoute(true)
                repo.setRoutePaused(false)
                repo.setError(null)
                totalSteps = 0L; fractionalSteps = 0.0; stepsAtLastInsert = 0L
                elapsedSeconds = 0
                lastHcInsertMs = System.currentTimeMillis()
                updateNotification("模擬走路中... 0 步")
                acquireWakeLock()
                startWalkLoop()
                return START_STICKY
            }
            ACTION_RESUME_ROUTE -> {
                val state = repo.currentState
                if (!state.isSimulating || state.waypoints.size < 2 || state.isWalkingRoute) return START_NOT_STICKY
                loopJob?.cancel()
                repo.setWalkingRoute(true)
                repo.setRoutePaused(false)
                repo.setError(null)
                lastHcInsertMs = System.currentTimeMillis()
                updateNotification("模擬走路中... ${"%,d".format(totalSteps)} 步")
                acquireWakeLock()
                startWalkLoop()
                return START_STICKY
            }
            ACTION_STOP_ROUTE -> {
                if (repo.currentState.isWalkingRoute) {
                    settleIntoHold()
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification("正在啟動..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, buildNotification("正在啟動..."))
        }
    }

    private fun logDiagnosticSnapshot(tag: String) {
        val state = repo.currentState
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOpt = pm.isIgnoringBatteryOptimizations(packageName)
        val isDeviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isDeviceIdleMode else false
        DebugLogger.log(
            "Service",
            "$tag isSimulating=${state.isSimulating} isWalkingRoute=${state.isWalkingRoute} " +
                "batteryOptExempt=$isIgnoringBatteryOpt deviceIdle=$isDeviceIdle " +
                "wakeLockHeld=${wakeLock?.isHeld} lat=${state.currentLat} lng=${state.currentLng}"
        )
    }

    private fun startHoldLoop() {
        loopJob?.cancel()
        var tick = 0
        loopJob = serviceScope.launch {
            while (true) {
                delay(1000)
                try {
                    locationSimulator.keepAlive()
                    tick++
                    if (tick % 30 == 0) logDiagnosticSnapshot("hold")
                    renewWakeLockIfNeeded(tick)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    // One bad tick shouldn't kill the loop — log and keep holding rather than
                    // silently freezing the position until the user notices and restarts.
                    DebugLogger.log("Service", "hold tick 發生例外：$e")
                    reportCaughtException("MockLocationService/hold", e)
                }
            }
        }
    }

    private fun startWalkLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (true) {
                delay(1000)
                val shouldStop = try {
                    walkTick()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    DebugLogger.log("Service", "walk tick 發生例外：$e")
                    repo.setError("背景定位服務發生未預期的錯誤\n請重新開始偽造GPS")
                    reportCaughtException("MockLocationService/walk", e)
                    false
                }
                if (shouldStop) return@launch
            }
        }
    }

    /** One tick of the walk loop. Returns true once the loop should stop (route settled into a
     *  hold) — the caller breaks out rather than this function using a non-local return, since
     *  it's no longer the loop's own lambda body (see the try/catch wrapping it in
     *  [startWalkLoop] that keeps a single bad tick from crashing the whole app). */
    private fun walkTick(): Boolean {
        val state = repo.currentState

        val justReachedEnd = locationSimulator.tick(state.speedKmh, state.waypointLoopMode)

        fractionalSteps += stepsPerSecond(state.speedKmh)
        val newSteps = fractionalSteps.toLong()
        fractionalSteps -= newSteps
        totalSteps += newSteps.coerceAtLeast(0L)

        val distanceMeters = totalSteps * 0.75
        elapsedSeconds++
        repo.updateStats(totalSteps, distanceMeters, elapsedSeconds)
        repo.updateCurrentPosition(locationSimulator.currentLat, locationSimulator.currentLng, locationSimulator.currentBearing)

        val now = System.currentTimeMillis()
        if (now - lastHcInsertMs >= 30_000L) {
            val stepsInPeriod = totalSteps - stepsAtLastInsert
            val start = lastHcInsertMs
            stepsAtLastInsert = totalSteps
            lastHcInsertMs = now
            if (AppSettings.writeStepsEnabled && stepsInPeriod > 0) {
                serviceScope.launch(Dispatchers.IO) {
                    DebugLogger.log("HealthConnect", "準備寫入 steps=$stepsInPeriod")
                    healthConnectHelper.insertSteps(stepsInPeriod, start, now)
                }
            } else {
                DebugLogger.log("HealthConnect", "略過寫入，這段時間步數=$stepsInPeriod writeStepsEnabled=${AppSettings.writeStepsEnabled}")
            }
        }

        if (elapsedSeconds % 5 == 0) {
            val km = "%.1f".format(distanceMeters / 1000.0)
            updateNotification(
                "已走 ${"%,d".format(totalSteps)} 步 · $km km · ${repo.currentState.elapsedTime}"
            )
        }

        if (state.stepLimit > 0 && totalSteps >= state.stepLimit) {
            settleIntoHold("📍 已達步數上限，靜止定位中")
            return true
        }

        if (justReachedEnd) {
            settleIntoHold("📍 已到達終點，靜止定位中")
            return true
        }

        if (elapsedSeconds % 30 == 0) logDiagnosticSnapshot("walk")
        renewWakeLockIfNeeded(elapsedSeconds)
        return false
    }

    /** Stops advancing and settles into a static hold at wherever the position currently is —
     *  used both when STOP_AT_END reaches the final waypoint and when the user explicitly stops
     *  walking a route (fake GPS itself stays on). */
    private fun settleIntoHold(notifyText: String = "📍 靜止定位中") {
        loopJob?.cancel()
        flushStepsRemaining()
        repo.setWalkingRoute(false)
        repo.setRoutePaused(true)
        updateNotification(notifyText)
        startHoldLoop()
    }

    private fun flushStepsRemaining() {
        val stepsRemaining = totalSteps - stepsAtLastInsert
        if (stepsRemaining > 0 && lastHcInsertMs > 0) {
            val start = lastHcInsertMs
            val end = System.currentTimeMillis()
            stepsAtLastInsert = totalSteps
            lastHcInsertMs = end
            serviceScope.launch(Dispatchers.IO) {
                healthConnectHelper.insertSteps(stepsRemaining, start, end)
            }
        }
    }

    override fun onDestroy() {
        DebugLogger.log("Service", "onDestroy pid=${android.os.Process.myPid()}")
        super.onDestroy()
        loopJob?.cancel()
        locationSimulator.stop()
        repo.setSimulating(false)
        repo.setWalkingRoute(false)
        repo.setRoutePaused(false)
        wakeLock?.let { if (it.isHeld) it.release() }

        // Insert remaining steps synchronously so they can't be dropped if process dies — bounded
        // by a timeout so a slow/hung Health Connect call can't block the main thread indefinitely
        // during teardown; losing the last few steps to a timeout beats stalling shutdown on it.
        val stepsRemaining = totalSteps - stepsAtLastInsert
        if (stepsRemaining > 0 && lastHcInsertMs > 0) {
            val start = lastHcInsertMs
            val end = System.currentTimeMillis()
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    withContext(Dispatchers.IO) { healthConnectHelper.insertSteps(stepsRemaining, start, end) }
                }
            }
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "PikStep 步行模擬", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "PikStep 背景執行通知" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌿 PikStep 執行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    /** Timed acquire so a leaked wake lock can't drain the battery forever — but that means a
     *  session running longer than this needs to actively renew it (see [renewWakeLockIfNeeded]),
     *  or the device can slip into deep sleep mid-walk and the service gets killed by the OS. */
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikStep:WalkLock").apply {
                setReferenceCounted(false)
            }
        }
        // Re-acquiring a timed lock that's already held resets its countdown, so calling this
        // periodically from the loops is what actually extends the hold past WAKE_LOCK_TIMEOUT_MS.
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    /** Called from the hold/walk loop ticks — renews well before [WAKE_LOCK_TIMEOUT_MS] would
     *  lapse, so a walk left running for many hours never loses CPU-wake protection mid-session. */
    private fun renewWakeLockIfNeeded(tickCount: Int) {
        if (tickCount % WAKE_LOCK_RENEW_INTERVAL_SEC == 0) acquireWakeLock()
    }
}
