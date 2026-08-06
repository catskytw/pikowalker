package com.pikowalker.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.pikowalker.app.model.WaypointLoopMode
import kotlin.math.cos
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

    private var gpsProviderAdded = false
    private var networkProviderAdded = false

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
        try {
            if (!gpsProviderAdded) {
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false, false,
                    true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                gpsProviderAdded = true
            }
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (_: Exception) {
            return false
        }

        // Best-effort: also mock NETWORK_PROVIDER so fused location clients (used by most
        // step-tracking apps) never fall back to a real network fix and drift away from the
        // simulated point. Failure here doesn't block simulation — GPS alone still works for
        // apps that read GPS_PROVIDER directly.
        try {
            if (!networkProviderAdded) {
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER,
                    false, false, false, false, false,
                    true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                networkProviderAdded = true
            }
            locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        } catch (_: Exception) {
            networkProviderAdded = false
        }

        return true
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

        currentLat = wpLat; currentLng = wpLng
        pushLocation(jitterLat(wpLat), jitterLng(wpLng, wpLat), tickSpeedMs.toFloat())
        return justReachedEnd
    }

    private fun metersToLat(m: Double) = m / 111_320.0
    private fun metersToLng(m: Double, refLat: Double) =
        m / (111_320.0 * cos(Math.toRadians(refLat)))

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

    private fun pushLocation(lat: Double, lng: Double, speed: Float) {
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val providers = buildList {
            if (gpsProviderAdded) add(LocationManager.GPS_PROVIDER)
            if (networkProviderAdded) add(LocationManager.NETWORK_PROVIDER)
        }
        providers.forEach { provider ->
            val loc = Location(provider).apply {
                latitude = lat; longitude = lng; altitude = 15.0
                accuracy = 3.0f; this.speed = speed
                this.time = time
                elapsedRealtimeNanos = elapsed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 5.0f; speedAccuracyMetersPerSecond = 0.5f
                }
            }
            try { locationManager.setTestProviderLocation(provider, loc) } catch (_: Exception) {}
        }
    }
}
