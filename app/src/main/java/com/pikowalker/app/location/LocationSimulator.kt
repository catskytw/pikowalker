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

    var currentLat: Double = wpLat; private set
    var currentLng: Double = wpLng; private set
    var currentBearing: Float = 0f; private set

    private var gpsProviderAdded = false
    private var networkProviderAdded = false
    private var fusedProviderAdded = false

    // Consecutive location-push failures since the last successful push, per provider — used to
    // decide when to stop silently self-healing and actually surface an error to the user.
    private var gpsFailureStreak = 0
    private var networkFailureStreak = 0
    private var fusedFailureStreak = 0
    var onPersistentFailure: (() -> Unit)? = null

    private val rng = Random(System.nanoTime())

    fun setCenter(lat: Double, lng: Double) {
        wpLat = lat; wpLng = lng
        currentLat = lat; currentLng = lng
    }

    fun teleport(lat: Double, lng: Double) {
        setCenter(lat, lng)
        if (gpsProviderAdded) pushLocation(lat, lng, 0f)
    }

    /** Re-push the current position, keeping the mock fix fresh while idle. Deliberately no
     *  jitter here — unlike active walking (where movement masks it), a stationary hold with
     *  random noise reads as the position drifting and snapping back. */
    fun keepAlive() {
        if (gpsProviderAdded) {
            pushLocation(wpLat, wpLng, 0f)
        }
    }

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

    fun stop() {
        if (gpsProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {}
            gpsProviderAdded = false
        }
        if (networkProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {}
            networkProviderAdded = false
        }
        if (fusedProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.FUSED_PROVIDER)
            } catch (_: Exception) {}
            fusedProviderAdded = false
        }
        wpIndex = 0; wpForward = true; reachedEnd = false
    }

    /** Advances one tick along the waypoint path. Returns true the instant STOP_AT_END reaches
     *  the final point (a one-time transition event the caller should react to). */
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
        val target = wpList[wpIndex]
        val dist = distMeters(wpLat, wpLng, target.first, target.second)
        var justReachedEnd = false
        // Small per-tick pace variation — a perfectly constant speed is itself an anomaly
        // signal, real walking pace naturally wobbles a few percent tick to tick.
        val tickSpeedMs = speedMs * (1.0 + rng.nextDouble(-0.08, 0.08))

        if (dist < 3.0) {
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
            val dLatM = (target.first - wpLat) * 111_320.0
            val dLngM = (target.second - wpLng) * 111_320.0 * cos(Math.toRadians(wpLat))
            val scale = tickSpeedMs / dist
            wpLat += metersToLat(dLatM * scale)
            wpLng += metersToLng(dLngM * scale, wpLat)
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
            var ok = runCatching { locationManager.setTestProviderLocation(provider, loc) }
                .onFailure { DebugLogger.log("Location", "推送失敗 provider=$provider ex=$it") }
                .isSuccess
            if (!ok) {
                // The OS silently dropped/disabled this test provider — re-register it exactly
                // like a manual full stop+restart would, then retry once, so the walk keeps
                // working without the user having to notice and intervene.
                val reRegistered = reRegisterProvider(provider)
                DebugLogger.log("Location", "重新註冊 provider=$provider result=$reRegistered")
                if (reRegistered) {
                    ok = runCatching { locationManager.setTestProviderLocation(provider, loc) }
                        .onFailure { DebugLogger.log("Location", "重試後仍失敗 provider=$provider ex=$it") }
                        .isSuccess
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
            if (provider == LocationManager.GPS_PROVIDER && newStreak == persistentFailureThreshold) {
                onPersistentFailure?.invoke()
            }
        }
    }
}
