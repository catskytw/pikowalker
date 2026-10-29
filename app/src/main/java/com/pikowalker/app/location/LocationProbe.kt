package com.pikowalker.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pikowalker.app.debug.DebugLogger

/** Diagnostic only — never changes what's simulated. While fake GPS is on, listens the way a
 *  typical game does (Google Play Services' fused client, the one path [LocationSimulator]
 *  can't write into) and writes what it actually delivers to the debug log.
 *
 *  Exists because some Pixel users see Pikmin Bloom bounce between the simulated spot and their
 *  real one while PikoWalker's own map stays correct: every push to the platform providers
 *  succeeds, so the cause has to be on the consumer's side, and this records what it receives —
 *  how far each fix is from the simulated point, and whether it's flagged as coming from a mock
 *  provider. Individual fixes are only logged when they're off-target; a heartbeat line every
 *  [HEARTBEAT_MS] covers the "nothing off-target" and "nothing delivered at all" cases.
 *
 *  Caveat: this app is one more fused-location client with its own request, which is not exactly
 *  what Pikmin asks for, so it's evidence about what Play Services delivers, not proof of what
 *  Pikmin saw. */
class LocationProbe(
    context: Context,
    private val simulatedPosition: () -> Pair<Double, Double>,
    private val maxPushGapMs: () -> Long,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var callback: LocationCallback? = null

    private var fixes = 0
    private var offTarget = 0
    private var maxOffM = 0.0
    private var lastHeartbeatMs = 0L

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (callback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = result.locations.forEach(::record)
        }
        callback = cb
        fixes = 0; offTarget = 0; maxOffM = 0.0
        lastHeartbeatMs = System.currentTimeMillis()
        runCatching { client.requestLocationUpdates(request, cb, Looper.getMainLooper()) }
            .onFailure {
                callback = null
                DebugLogger.log("Probe", "無法啟動融合定位量測：$it")
            }
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { client.removeLocationUpdates(it) } }
        callback = null
    }

    @Synchronized
    private fun record(loc: Location) {
        val (lat, lng) = simulatedPosition()
        val off = FloatArray(1).also { Location.distanceBetween(lat, lng, loc.latitude, loc.longitude, it) }[0].toDouble()
        fixes++
        if (off > OFF_TARGET_M) {
            offTarget++
            if (off > maxOffM) maxOffM = off
            DebugLogger.log(
                "Probe",
                "偏離模擬點 ${"%.0f".format(off)}m provider=${loc.provider} isMock=${isMock(loc)} " +
                    "精度=${loc.accuracy}m 位置=${"%.5f".format(loc.latitude)},${"%.5f".format(loc.longitude)}"
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
            DebugLogger.log("Probe", "近 ${(now - lastHeartbeatMs) / 1000}s 收到 $fixes 筆，偏離 $offTarget 筆，最大偏離 ${"%.0f".format(maxOffM)}m，模擬位置推送最大間隔 ${maxPushGapMs()}ms")
            fixes = 0; offTarget = 0; maxOffM = 0.0
            lastHeartbeatMs = now
        }
    }

    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider

    private companion object {
        const val UPDATE_INTERVAL_MS = 5_000L
        const val HEARTBEAT_MS = 60_000L
        // Well beyond the ~1.2 m jitter and 3 m reported accuracy, well short of any real hop.
        const val OFF_TARGET_M = 200.0
    }
}
