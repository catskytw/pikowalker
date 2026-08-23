package com.pikowalker.app

import android.util.Base64
import com.pikowalker.app.model.GeoPoint
import com.pikowalker.app.model.SavedRoute
import com.pikowalker.app.model.WaypointLoopMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Turns a [SavedRoute] into a self-contained text code (no server involved) that can be pasted
 *  or shared through any existing channel — chat apps, SMS, email — and decoded back on another
 *  device running PikoWalker. Only the shareable parts travel (name/waypoints/loop mode); id and
 *  savedAt are regenerated on import so an imported route never collides with the recipient's own. */
object RouteShareCodec {
    private const val PREFIX = "PKWK1:"
    private const val MAX_WAYPOINTS = 50
    private const val MAX_NAME_LENGTH = 60
    private val CODE_REGEX = Regex(Regex.escape(PREFIX) + "[A-Za-z0-9+/=]+")

    fun encode(route: SavedRoute): String {
        val json = JSONObject().apply {
            put("n", route.name)
            put("m", route.loopMode.name)
            put("w", JSONArray().also { arr ->
                route.waypoints.forEach { pt -> arr.put(JSONArray().put(pt.lat).put(pt.lng)) }
            })
        }
        val payload = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return PREFIX + payload
    }

    /** Finds and decodes a route code anywhere inside [text] — the caller can hand this the
     *  whole shared message rather than needing to isolate the code first. Returns null for
     *  anything that isn't a valid, in-range route code (never throws on garbage input). */
    fun decode(text: String): SavedRoute? {
        val match = CODE_REGEX.find(text) ?: return null
        return runCatching {
            val payload = match.value.removePrefix(PREFIX)
            val json = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            val obj = JSONObject(json)

            val name = obj.getString("n").take(MAX_NAME_LENGTH).ifBlank { "分享的路線" }
            val loopMode = runCatching { WaypointLoopMode.valueOf(obj.getString("m")) }
                .getOrDefault(WaypointLoopMode.PING_PONG)

            val wpsArr = obj.getJSONArray("w")
            require(wpsArr.length() in 1..MAX_WAYPOINTS)
            val waypoints = (0 until wpsArr.length()).map { i ->
                val pair = wpsArr.getJSONArray(i)
                val lat = pair.getDouble(0)
                val lng = pair.getDouble(1)
                require(lat in -90.0..90.0 && lng in -180.0..180.0)
                GeoPoint(lat, lng)
            }

            SavedRoute(
                id = UUID.randomUUID().toString(),
                name = name,
                waypoints = waypoints,
                loopMode = loopMode,
                savedAt = System.currentTimeMillis()
            )
        }.getOrNull()
    }

    /** The message body handed to the share sheet — human-readable context plus the code, so it
     *  reads sensibly whether the recipient imports it or just sees it as a chat message. */
    fun shareText(route: SavedRoute): String =
        "我在 PikoWalker 存了一條路線「${route.name}」，把這段貼到 App 裡就能匯入：\n\n${encode(route)}"
}
