package com.pikowalker.app.model

data class GeoPoint(val lat: Double, val lng: Double)

data class WalkState(
    val isSimulating: Boolean = false,     // master switch: mock GPS active (holding or walking)
    val isWalkingRoute: Boolean = false,   // true while actively traversing a multi-point path
    val speedKmh: Double = WalkSpeed.NORMAL.kmh,
    val stepLimit: Long = 2000L,            // 0 = no limit; walking auto-stops once reached
    val steps: Long = 0L,
    val distanceMeters: Double = 0.0,
    val elapsedSeconds: Int = 0,
    val isMockLocationReady: Boolean = false,
    val healthConnectAvailable: Boolean = false,
    val hasHealthPermission: Boolean = false,
    val errorMessage: String? = null,
    val waypoints: List<GeoPoint> = emptyList(),
    val waypointLoopMode: WaypointLoopMode = WaypointLoopMode.LOOP,
    val isPathMode: Boolean = false,       // false = tapping the map replaces the single point; true = tapping appends to a path
    val isRoutePaused: Boolean = false,    // true when a route walk was stopped mid-way and can be resumed
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val currentBearing: Float = 0f,        // compass heading of travel, degrees; meaningless while stationary
) {
    val distanceKm: String get() = "%.1f".format(distanceMeters / 1000.0)
    val elapsedTime: String get() {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    /** True when the mock GPS is on but not actively advancing — a single tapped point, or a
     *  route that reached its end under [WaypointLoopMode.STOP_AT_END]. */
    val isStaticAtWaypoint: Boolean get() = isSimulating && !isWalkingRoute
}

enum class WalkSpeed(val kmh: Double, val label: String, val emoji: String) {
    SLOW(1.0, "慢步", "🐢"),
    NORMAL(3.5, "正常", "🚶"),
    FAST(5.0, "快步", "🏃")
}

/** There used to be a 來回折返 (ping-pong) mode too, since removed on purpose. Routes saved or
 *  shared with it no longer parse as a valid value and fall back to [LOOP] wherever a mode is read
 *  back (see RouteRepository / RouteShareCodec), so old data keeps working. */
enum class WaypointLoopMode(val label: String, val emoji: String, val description: String) {
    LOOP("循環回起點", "🔁", "走到最後一點後接回起點，一直循環走下去"),
    STOP_AT_END("到底定點", "🏁", "走到最後一點就停下來，定點待命"),
    /** Walks to each waypoint, circles it once at [FLOWER_CIRCLE_RADIUS_M] radius, then moves on
     *  to the next; after the last one it heads back to the first and keeps going, like [LOOP].
     *  The constant keeps its old name so routes saved as PLANT_CIRCLES still parse. */
    PLANT_CIRCLES("種花循環", "🌸", "每個點各繞一圈（半徑 25m）再前往下一點，繞完最後一點回到起點繼續，一直循環種下去"),
}

/** Radius of the circle walked around each waypoint in the 種花 modes, in metres. */
const val FLOWER_CIRCLE_RADIUS_M = 25.0
