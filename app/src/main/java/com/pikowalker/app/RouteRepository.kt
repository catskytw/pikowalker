package com.pikowalker.app

import android.content.Context
import com.pikowalker.app.model.GeoPoint
import com.pikowalker.app.model.SavedRoute
import com.pikowalker.app.model.WaypointLoopMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class RouteRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pikowalker_routes", Context.MODE_PRIVATE)
    private val _routes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val routes: StateFlow<List<SavedRoute>> = _routes.asStateFlow()

    init { _routes.value = loadFromPrefs() }

    fun save(route: SavedRoute) {
        val updated = listOf(route) + _routes.value.filter { it.id != route.id }
        persist(updated)
        _routes.value = updated
    }

    fun delete(id: String) {
        val updated = _routes.value.filter { it.id != id }
        persist(updated)
        _routes.value = updated
    }

    private fun loadFromPrefs(): List<SavedRoute> {
        val json = prefs.getString("routes", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val wps = obj.getJSONArray("waypoints")
                    SavedRoute(
                        id        = obj.getString("id"),
                        name      = obj.getString("name"),
                        loopMode  = runCatching { WaypointLoopMode.valueOf(obj.getString("loopMode")) }
                            .getOrDefault(WaypointLoopMode.PING_PONG),
                        savedAt   = obj.getLong("savedAt"),
                        waypoints = (0 until wps.length()).map { j ->
                            val wp = wps.getJSONObject(j)
                            GeoPoint(wp.getDouble("lat"), wp.getDouble("lng"))
                        }
                    )
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun persist(routes: List<SavedRoute>) {
        val arr = JSONArray()
        routes.forEach { r ->
            arr.put(JSONObject().apply {
                put("id",       r.id)
                put("name",     r.name)
                put("loopMode", r.loopMode.name)
                put("savedAt",  r.savedAt)
                put("waypoints", JSONArray().also { wps ->
                    r.waypoints.forEach { pt ->
                        wps.put(JSONObject().put("lat", pt.lat).put("lng", pt.lng))
                    }
                })
            })
        }
        prefs.edit().putString("routes", arr.toString()).apply()
    }
}
