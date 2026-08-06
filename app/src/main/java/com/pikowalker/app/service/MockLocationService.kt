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
import com.pikowalker.app.health.HealthConnectHelper
import com.pikowalker.app.location.LocationSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var locationSimulator: LocationSimulator
    private lateinit var healthConnectHelper: HealthConnectHelper
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: kotlinx.coroutines.Job? = null

    private var totalSteps = 0L
    private var fractionalSteps = 0.0   // accumulates sub-step remainder for accurate counting
    private var stepsAtLastInsert = 0L  // totalSteps value at last HC insert
    private var elapsedSeconds = 0
    private var lastHcInsertMs = 0L

    private fun stepsPerSecond(speedKmh: Double) = (speedKmh * 1000.0 / 3600.0) / 0.75

    override fun onCreate() {
        super.onCreate()
        locationSimulator = LocationSimulator(this)
        healthConnectHelper = HealthConnectHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun startHoldLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (true) {
                delay(1000)
                locationSimulator.keepAlive()
            }
        }
    }

    private fun startWalkLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (true) {
                delay(1000)
                val state = repo.currentState

                val justReachedEnd = locationSimulator.tick(state.speedKmh, state.waypointLoopMode)

                fractionalSteps += stepsPerSecond(state.speedKmh)
                val newSteps = fractionalSteps.toLong()
                fractionalSteps -= newSteps
                totalSteps += newSteps.coerceAtLeast(0L)

                val distanceMeters = totalSteps * 0.75
                elapsedSeconds++
                repo.updateStats(totalSteps, distanceMeters, elapsedSeconds)
                repo.updateCurrentPosition(locationSimulator.currentLat, locationSimulator.currentLng)

                val now = System.currentTimeMillis()
                if (now - lastHcInsertMs >= 30_000L) {
                    val stepsInPeriod = totalSteps - stepsAtLastInsert
                    if (stepsInPeriod > 0) {
                        val start = lastHcInsertMs
                        stepsAtLastInsert = totalSteps
                        lastHcInsertMs = now
                        serviceScope.launch(Dispatchers.IO) {
                            healthConnectHelper.insertSteps(stepsInPeriod, start, now)
                        }
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
                    return@launch
                }

                if (justReachedEnd) {
                    settleIntoHold("📍 已到達終點，靜止定位中")
                    return@launch
                }
            }
        }
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
        super.onDestroy()
        loopJob?.cancel()
        locationSimulator.stop()
        repo.setSimulating(false)
        repo.setWalkingRoute(false)
        repo.setRoutePaused(false)
        wakeLock?.let { if (it.isHeld) it.release() }

        // Insert remaining steps synchronously so they can't be dropped if process dies.
        val stepsRemaining = totalSteps - stepsAtLastInsert
        if (stepsRemaining > 0 && lastHcInsertMs > 0) {
            val start = lastHcInsertMs
            val end = System.currentTimeMillis()
            runBlocking { withContext(Dispatchers.IO) { healthConnectHelper.insertSteps(stepsRemaining, start, end) } }
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikStep:WalkLock")
        wakeLock?.acquire(6 * 60 * 60 * 1000L)
    }
}
