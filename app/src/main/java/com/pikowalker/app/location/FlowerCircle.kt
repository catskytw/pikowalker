package com.pikowalker.app.location

import com.pikowalker.app.model.FLOWER_CIRCLE_RADIUS_M
import com.pikowalker.app.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/** Expands a plain waypoint list into the denser path [com.pikowalker.app.model.WaypointLoopMode.PLANT_CIRCLES]
 *  actually walks: for every waypoint, step out to the circle, walk one full lap around it, then
 *  head for the next waypoint. Done as a one-time rewrite of the polyline up front, rather than
 *  teaching [LocationSimulator.tick] a new kind of movement — the existing "follow these points in
 *  order" logic (arrival, carry-over of leftover distance, bearing) then works on circles for free. */
object FlowerCircle {
    const val RADIUS_M = FLOWER_CIRCLE_RADIUS_M

    /** A lap is approximated by this many chords. At a 20 m radius that's a 0.08 m gap between
     *  chord and true arc — well under the ~1.2 m of GPS jitter [LocationSimulator] adds anyway. */
    private const val SEGMENTS = 36

    /** Per waypoint: center, the point on the circle facing the next waypoint, a full clockwise lap
     *  back to that same point, then on to the next waypoint's center. Starting the lap on the side
     *  facing the next waypoint means the walk out of the circle toward it is the shortest one.
     *  The path is meant to be walked as a loop (the simulator wraps from the last point back to the
     *  first), so the last waypoint faces the first one and no closing point is appended. */
    fun expand(points: List<GeoPoint>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(points.size * (SEGMENTS + 2))
        points.forEachIndexed { i, center ->
            val facing = if (points.size > 1) bearing(center, points[(i + 1) % points.size]) else 0.0
            out.add(center.lat to center.lng)
            for (k in 0..SEGMENTS) {
                out.add(offset(center, facing + k * 360.0 / SEGMENTS, RADIUS_M))
            }
        }
        return out
    }

    /** Compass bearing in degrees (0 = north, increasing clockwise) from [from] to [to]. */
    private fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val dLat = to.lat - from.lat
        val dLng = (to.lng - from.lng) * cos(Math.toRadians(from.lat))
        return Math.toDegrees(kotlin.math.atan2(dLng, dLat))
    }

    private fun offset(origin: GeoPoint, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        val dLat = meters * cos(rad) / 111_320.0
        val dLng = meters * sin(rad) / (111_320.0 * cos(Math.toRadians(origin.lat)))
        return (origin.lat + dLat) to (origin.lng + dLng)
    }
}
