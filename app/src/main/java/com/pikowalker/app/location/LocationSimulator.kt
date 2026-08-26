package com.pikowalker.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.pikowalker.app.debug.DebugLogger
import com.pikowalker.app.model.WaypointLoopMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Called from two different threads: [com.pikowalker.app.service.MockLocationService]'s
 *  onStartCommand (main thread — start/stop/teleport/setWaypoints) and its hold/walk loop
 *  (Dispatchers.Default — tick/keepAlive). loopJob.cancel() before dispatching a main-thread
 *  call is cooperative, not immediate, so the two can genuinely overlap (observed for real when
 *  STOP_ROUTE is quickly followed by START_ROUTE). The public entry points below are
 *  [Synchronized] on that basis — Kotlin's monitor lock is reentrant, so calls between them
 *  (e.g. tick() calling pushLocation()) don't self-deadlock. */
class LocationSimulator(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Waypoint path state
    private var wpList: List<Pair<Double, Double>> = emptyList()
    private var wpIndex = 0
    private var wpForward = true       // for PING_PONG direction
    private var reachedEnd = false     // true once STOP_AT_END has arrived at the final point
    private var wpLat = 25.0330
    private var wpLng = 121.5654

    // Read from MockLocationService's loop thread right after tick()/keepAlive() write them —
    // @Volatile makes that plain, unsynchronized read safe without pulling the reader into the
    // same lock these mutating methods use (see the class doc below).
    @Volatile var currentLat: Double = wpLat; private set
    @Volatile var currentLng: Double = wpLng; private set
    @Volatile var currentBearing: Float = 0f; private set

    private var gpsProviderAdded = false
    private var networkProviderAdded = false
    private var fusedProviderAdded = false

    // Consecutive location-push failures since the last successful push, per provider — used to
    // decide when to stop silently self-healing and actually surface an error to the user.
    private var gpsFailureStreak = 0
    private var networkFailureStreak = 0
    private var fusedFailureStreak = 0
    var onPersistentFailure: (() -> Unit)? = null

    // Wall-clock time of each provider's last successful push — lets a failure log show exactly
    // how long the provider had actually been broken for, not just "it failed this tick".
    private var gpsLastSuccessMs = 0L
    private var networkLastSuccessMs = 0L
    private var fusedLastSuccessMs = 0L

    private val rng = Random(System.nanoTime())

    @Synchronized
    fun setCenter(lat: Double, lng: Double) {
        wpLat = lat; wpLng = lng
        currentLat = lat; currentLng = lng
    }

    @Synchronized
    fun teleport(lat: Double, lng: Double) {
        setCenter(lat, lng)
        if (gpsProviderAdded) pushLocation(lat, lng, 0f)
    }

    /** Re-push the current position, keeping the mock fix fresh while idle. Deliberately no
     *  jitter here — unlike active walking (where movement masks it), a stationary hold with
     *  random noise reads as the position drifting and snapping back. */
    @Synchronized
    fun keepAlive() {
        if (gpsProviderAdded) {
            pushLocation(wpLat, wpLng, 0f)
        }
    }

    @Synchronized
    fun setWaypoints(wps: List<Pair<Double, Double>>) {
        wpList = wps
        wpIndex = 0
        wpForward = true
        reachedEnd = false
        if (wps.isNotEmpty()) {
            wpLat = wps[0].first; wpLng = wps[0].second
            currentLat = wpLat; currentLng = wpLng
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val gpsOk = reRegisterGpsProvider()
        if (!gpsOk) return false
        // Best-effort: also mock NETWORK_PROVIDER (and, on API 31+, the platform FUSED_PROVIDER)
        // so fused-location clients — which is what most step/game apps actually read, Pikmin
        // Bloom included — never blend in a real fix and drift away from the simulated point.
        // Failure here doesn't block simulation — GPS alone still works for apps that read
        // GPS_PROVIDER directly.
        reRegisterNetworkProvider()
        reRegisterFusedProvider()
        return true
    }

    /** Fully removes then re-adds the GPS test provider, mirroring exactly what a manual full
     *  stop-then-restart does. The OS (especially battery-aggressive OEM skins) can silently
     *  disable a mock provider mid-session without the app being told — [pushLocation] calls
     *  this to self-heal instead of requiring the user to notice and manually restart. */
    private fun reRegisterGpsProvider(): Boolean = try {
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, false, false, false, false,
            true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        gpsProviderAdded = true
        true
    } catch (_: Exception) {
        gpsProviderAdded = false
        false
    }

    private fun reRegisterNetworkProvider(): Boolean = try {
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
        locationManager.addTestProvider(
            LocationManager.NETWORK_PROVIDER,
            false, false, false, false, false,
            true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        networkProviderAdded = true
        true
    } catch (_: Exception) {
        networkProviderAdded = false
        false
    }

    /** FUSED_PROVIDER is AOSP's own platform fusion provider (API 31+) — distinct from Google
     *  Play Services' proprietary FusedLocationProviderClient, which most third-party apps
     *  (Pikmin Bloom included) actually use and which isn't reachable through this API at all.
     *  Mocking it here only helps the subset of apps that query the platform provider directly,
     *  but costs nothing to also cover. */
    private fun reRegisterFusedProvider(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            try { locationManager.removeTestProvider(LocationManager.FUSED_PROVIDER) } catch (_: Exception) {}
            locationManager.addTestProvider(
                LocationManager.FUSED_PROVIDER,
                false, false, false, false, false,
                true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true)
            fusedProviderAdded = true
            true
        } catch (_: Exception) {
            fusedProviderAdded = false
            false
        }
    }

    @Synchronized
    fun stop() {
        if (gpsProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) { DebugLogger.log("Location", "stop() 清除 gps provider 失敗：$e") }
            gpsProviderAdded = false
        }
        if (networkProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) { DebugLogger.log("Location", "stop() 清除 network provider 失敗：$e") }
            networkProviderAdded = false
        }
        if (fusedProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.FUSED_PROVIDER)
            } catch (e: Exception) { DebugLogger.log("Location", "stop() 清除 fused provider 失敗：$e") }
            fusedProviderAdded = false
        }
        wpIndex = 0; wpForward = true; reachedEnd = false
        // Reset so a fresh session doesn't inherit a near-threshold streak from whatever the
        // previous session ended on — otherwise a handful of failures right after restarting
        // could immediately fire onPersistentFailure for a session that just began.
        gpsFailureStreak = 0; networkFailureStreak = 0; fusedFailureStreak = 0
    }

    /** Advances one tick along the waypoint path. Returns true the instant STOP_AT_END reaches
     *  the final point (a one-time transition event the caller should react to).
     *
     *  The per-tick movement budget can exceed the distance to the next waypoint — at 60km/h
     *  that's ~16.7m per tick, easily bigger than the 3m arrival threshold whenever two waypoints
     *  sit close together (a tight corner, in particular). The old code moved by straight-line
     *  extrapolation toward the target regardless of that, which overshoots past it instead of
     *  landing on it; the very next tick then finds the (now-passed) target behind it and heads
     *  back, overshooting the other way — an oscillation that can persist indefinitely without
     *  ever satisfying the arrival check, which looked like getting stuck exactly at a corner.
     *  Lower speeds rarely overshoot far enough to trigger it, which is why it only reproduced at
     *  higher speed. Fixed by consuming the movement budget across as many waypoints as it
     *  actually covers this tick, landing exactly on each one crossed rather than past it. */
    @Synchronized
    fun tick(speedKmh: Double, loopMode: WaypointLoopMode): Boolean {
        if (!gpsProviderAdded || wpList.size < 2 || reachedEnd) {
            // Stationary — no jitter, same reasoning as keepAlive().
            pushLocation(wpLat, wpLng, 0f)
            currentLat = wpLat; currentLng = wpLng
            return false
        }

        val oldLat = wpLat
        val oldLng = wpLng
        val speedMs = speedKmh / 3.6
        var justReachedEnd = false
        // Small per-tick pace variation — a perfectly constant speed is itself an anomaly
        // signal, real walking pace naturally wobbles a few percent tick to tick.
        val tickSpeedMs = speedMs * (1.0 + rng.nextDouble(-0.08, 0.08))
        var remainingM = tickSpeedMs

        // Bounded well above anything a real route needs, purely so a degenerate route (e.g.
        // every waypoint at the same coordinate, in LOOP mode) can't spin this forever — with
        // zero-distance hops never consuming the budget, the loop's own end condition alone
        // wouldn't stop it.
        var guard = wpList.size * 4 + 16
        while (remainingM > 0.0 && !reachedEnd && guard-- > 0) {
            val target = wpList[wpIndex]
            val dist = distMeters(wpLat, wpLng, target.first, target.second)

            if (dist <= remainingM) {
                // This tick's budget reaches (or exactly covers) the target — land on it exactly
                // and advance, carrying over whatever's left toward the next leg.
                wpLat = target.first; wpLng = target.second
                remainingM -= dist
                when (loopMode) {
                    WaypointLoopMode.PING_PONG -> {
                        if (wpForward) {
                            if (wpIndex < wpList.size - 1) {
                                wpIndex++
                            } else {
                                wpIndex = (wpList.size - 2).coerceAtLeast(0)
                                wpForward = false
                            }
                        } else {
                            if (wpIndex > 0) {
                                wpIndex--
                            } else {
                                wpIndex = 1.coerceAtMost(wpList.size - 1)
                                wpForward = true
                            }
                        }
                    }
                    WaypointLoopMode.LOOP -> {
                        wpIndex = (wpIndex + 1) % wpList.size
                    }
                    WaypointLoopMode.STOP_AT_END -> {
                        if (wpIndex < wpList.size - 1) {
                            wpIndex++
                        } else {
                            reachedEnd = true
                            justReachedEnd = true
                        }
                    }
                }
            } else {
                // Won't reach the target this tick — move the remaining budget straight toward it.
                val dLatM = (target.first - wpLat) * 111_320.0
                val dLngM = (target.second - wpLng) * 111_320.0 * cos(Math.toRadians(wpLat))
                val scale = remainingM / dist
                wpLat += metersToLat(dLatM * scale)
                wpLng += metersToLng(dLngM * scale, wpLat)
                remainingM = 0.0
            }
        }

        val headingTarget = wpList[wpIndex]
        currentBearing = bearingBetween(oldLat, oldLng, headingTarget.first, headingTarget.second)
        currentLat = wpLat; currentLng = wpLng
        pushLocation(jitterLat(wpLat), jitterLng(wpLng, wpLat), tickSpeedMs.toFloat(), currentBearing)
        return justReachedEnd
    }

    private fun metersToLat(m: Double) = m / 111_320.0
    private fun metersToLng(m: Double, refLat: Double) =
        m / (111_320.0 * cos(Math.toRadians(refLat)))

    /** Initial compass bearing (0–360°, 0 = north) from point 1 to point 2. */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lng2 - lng1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta) + 360.0) % 360.0).toFloat()
    }

    /** Small random offset standing in for real GPS receiver noise (≤ ~1.2m), applied only to
     *  what's reported to the OS — [currentLat]/[currentLng] and the path-following math stay
     *  on the clean coordinate so the app's own UI and arrival detection are unaffected. */
    private fun jitterLat(lat: Double) = lat + metersToLat(rng.nextDouble(-1.2, 1.2))
    private fun jitterLng(lng: Double, refLat: Double) = lng + metersToLng(rng.nextDouble(-1.2, 1.2), refLat)

    private fun distMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat1 - lat2) * 111_320.0
        val dLng = (lng1 - lng2) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    // Above this many consecutive failed pushes for a provider (even after re-registering each
    // time), something deeper than a transient OS drop is wrong — surface it instead of
    // silently spinning forever while the app's own UI still looks like it's working fine.
    private val persistentFailureThreshold = 15

    private fun reRegisterProvider(provider: String): Boolean = when (provider) {
        LocationManager.GPS_PROVIDER -> reRegisterGpsProvider()
        LocationManager.NETWORK_PROVIDER -> reRegisterNetworkProvider()
        LocationManager.FUSED_PROVIDER -> reRegisterFusedProvider()
        else -> false
    }

    /** Snapshot of system state at the exact moment a provider push fails — as opposed to the
     *  periodic 30-tick diagnostic snapshot, which isn't aligned to a failure at all. Built to
     *  test hypotheses we don't otherwise have evidence for: is this tied to Doze, memory
     *  pressure, the OS's own process-importance re-evaluation (onTrimMemory / process
     *  importance — a much more direct read of "does the system think we matter right now" than
     *  inferring it from Activity visibility), screen state, charging state, or AppOps actually
     *  revoking the MOCK_LOCATION grant (as opposed to LocationManagerService's own test-provider
     *  table being cleared some other way — those are two different layers we've had no way to
     *  tell apart). */
    private fun failureDiagnostics(provider: String, lastSuccessMs: Long): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val deviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm?.isDeviceIdleMode else null
        val screenOn = pm?.isInteractive

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val availMemMb = memInfo.availMem / (1024 * 1024)
        val totalMemMb = memInfo.totalMem / (1024 * 1024)

        // RunningAppProcessInfo.importance is the OS's own current priority tier for this
        // process (e.g. IMPORTANCE_FOREGROUND_SERVICE=125, IMPORTANCE_BACKGROUND=400) — the most
        // direct available read of whether the system currently considers us backgrounded.
        val myPid = android.os.Process.myPid()
        val processImportance = runCatching {
            am?.runningAppProcesses?.firstOrNull { it.pid == myPid }?.importance
        }.getOrNull()

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        val mockOpMode = runCatching {
            @Suppress("DEPRECATION")
            appOps?.checkOpNoThrow("android:mock_location", android.os.Process.myUid(), context.packageName)
        }.getOrNull()

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val charging = runCatching {
            bm?.isCharging
        }.getOrNull()

        val downMs = if (lastSuccessMs > 0) System.currentTimeMillis() - lastSuccessMs else -1L

        return "pid=$myPid deviceIdle=$deviceIdle screenOn=$screenOn 可用記憶體=${availMemMb}MB/${totalMemMb}MB lowMemory=${memInfo.lowMemory} " +
            "processImportance=$processImportance lastTrimMemory=${DebugLogger.lastTrimMemoryLevel} " +
            "mockOpMode=$mockOpMode charging=$charging 距上次成功推送=${downMs}ms"
    }

    private fun pushLocation(lat: Double, lng: Double, speed: Float, bearing: Float? = null) {
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val providers = buildList {
            if (gpsProviderAdded) add(LocationManager.GPS_PROVIDER)
            if (networkProviderAdded) add(LocationManager.NETWORK_PROVIDER)
            if (fusedProviderAdded) add(LocationManager.FUSED_PROVIDER)
        }
        providers.forEach { provider ->
            val loc = Location(provider).apply {
                latitude = lat; longitude = lng; altitude = 15.0
                accuracy = 3.0f; this.speed = speed
                this.time = time
                elapsedRealtimeNanos = elapsed
                if (bearing != null) this.bearing = bearing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 5.0f; speedAccuracyMetersPerSecond = 0.5f
                    if (bearing != null) bearingAccuracyDegrees = 10f
                }
            }
            val pushResult = runCatching { locationManager.setTestProviderLocation(provider, loc) }
            var ok = pushResult.isSuccess
            if (!ok) {
                // Captured before reRegisterProvider() touches anything — this is the state at
                // the moment of failure, not after we've already patched it back up.
                val lastSuccessMs = when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsLastSuccessMs
                    LocationManager.NETWORK_PROVIDER -> networkLastSuccessMs
                    else -> fusedLastSuccessMs
                }
                val diagnostics = failureDiagnostics(provider, lastSuccessMs)
                DebugLogger.log("Location", "推送失敗 provider=$provider ex=${pushResult.exceptionOrNull()} $diagnostics")

                // The OS silently dropped/disabled this test provider — re-register it exactly
                // like a manual full stop+restart would, then retry once, so the walk keeps
                // working without the user having to notice and intervene. Also recorded as a
                // Crashlytics non-fatal purely for frequency data — every prior attempt at
                // understanding how often this actually happens relied on a user noticing and
                // manually sharing a debug log, which is a heavily biased sample.
                val reRegistered = reRegisterProvider(provider)
                DebugLogger.log("Location", "重新註冊 provider=$provider result=$reRegistered")
                runCatching {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("provider", provider)
                        setCustomKey("reRegistered", reRegistered)
                        setCustomKey("diagnostics", diagnostics)
                        recordException(IllegalStateException("mock provider revoked: $provider"))
                    }
                }
                if (reRegistered) {
                    ok = runCatching { locationManager.setTestProviderLocation(provider, loc) }
                        .onFailure { DebugLogger.log("Location", "重試後仍失敗 provider=$provider ex=$it") }
                        .isSuccess
                }
            }
            if (ok) {
                val now = System.currentTimeMillis()
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsLastSuccessMs = now
                    LocationManager.NETWORK_PROVIDER -> networkLastSuccessMs = now
                    else -> fusedLastSuccessMs = now
                }
            }

            val streak = when (provider) {
                LocationManager.GPS_PROVIDER -> gpsFailureStreak
                LocationManager.NETWORK_PROVIDER -> networkFailureStreak
                else -> fusedFailureStreak
            }
            val newStreak = if (ok) 0 else streak + 1
            when (provider) {
                LocationManager.GPS_PROVIDER -> gpsFailureStreak = newStreak
                LocationManager.NETWORK_PROVIDER -> networkFailureStreak = newStreak
                else -> fusedFailureStreak = newStreak
            }
            if (!ok) {
                DebugLogger.log("Location", "provider=$provider 連續失敗=$newStreak lat=$lat lng=$lng")
            }
            // Not just GPS_PROVIDER — NETWORK_PROVIDER and FUSED_PROVIDER matter just as much
            // here, since most fused-location clients (Pikmin Bloom included) never read
            // GPS_PROVIDER directly. A silent persistent failure on either of those would
            // otherwise never surface to the user at all.
            if (newStreak == persistentFailureThreshold) {
                onPersistentFailure?.invoke()
            }
        }
    }
}
