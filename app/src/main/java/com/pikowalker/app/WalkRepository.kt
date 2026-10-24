package com.pikowalker.app

import com.pikowalker.app.model.GeoPoint
import com.pikowalker.app.model.WalkState
import com.pikowalker.app.model.WaypointLoopMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WalkRepository {
    private val _state = MutableStateFlow(WalkState())
    val state: StateFlow<WalkState> = _state.asStateFlow()

    fun setSimulating(simulating: Boolean) = _state.update { it.copy(isSimulating = simulating) }
    fun setWalkingRoute(walking: Boolean) = _state.update { it.copy(isWalkingRoute = walking) }
    fun setSpeedKmh(kmh: Double) = _state.update { it.copy(speedKmh = kmh) }
    fun setStepLimit(limit: Long) = _state.update { it.copy(stepLimit = limit.coerceAtLeast(0L)) }
    fun setMockLocationReady(ready: Boolean) = _state.update { it.copy(isMockLocationReady = ready) }
    fun setHealthConnect(available: Boolean, hasPermission: Boolean) =
        _state.update { it.copy(healthConnectAvailable = available, hasHealthPermission = hasPermission) }
    fun setError(message: String?) = _state.update { it.copy(errorMessage = message) }

    fun updateStats(steps: Long, distanceMeters: Double, elapsedSeconds: Int) =
        _state.update { it.copy(steps = steps, distanceMeters = distanceMeters, elapsedSeconds = elapsedSeconds) }

    fun resetStats() =
        _state.update { it.copy(steps = 0L, distanceMeters = 0.0, elapsedSeconds = 0) }

    fun updateCurrentPosition(lat: Double, lng: Double, bearing: Float? = null) =
        _state.update { it.copy(currentLat = lat, currentLng = lng, currentBearing = bearing ?: it.currentBearing) }

    fun addWaypoint(point: GeoPoint) =
        _state.update { it.copy(waypoints = it.waypoints + point, isRoutePaused = false) }

    fun removeWaypoint(index: Int) =
        _state.update { it.copy(waypoints = it.waypoints.filterIndexed { i, _ -> i != index }, isRoutePaused = false) }

    fun moveWaypoint(from: Int, to: Int) = _state.update {
        if (from == to || from !in it.waypoints.indices || to !in it.waypoints.indices) return@update it
        val list = it.waypoints.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        it.copy(waypoints = list, isRoutePaused = false)
    }

    fun clearWaypoints() = _state.update { it.copy(waypoints = emptyList(), isRoutePaused = false) }

    /** Switching to or from 種花繞圈 changes the shape of the path itself (it's expanded into
     *  laps once, when a walk starts), not just what happens at its end like the other modes — so a
     *  paused walk can't be resumed across that switch, same as editing the waypoints. */
    fun setWaypointLoopMode(mode: WaypointLoopMode) = _state.update {
        val reshapesPath = (it.waypointLoopMode == WaypointLoopMode.PLANT_CIRCLES) !=
            (mode == WaypointLoopMode.PLANT_CIRCLES)
        it.copy(
            waypointLoopMode = mode,
            isRoutePaused = if (reshapesPath) false else it.isRoutePaused
        )
    }
    fun setPathMode(active: Boolean) = _state.update { it.copy(isPathMode = active) }
    fun setRoutePaused(paused: Boolean) = _state.update { it.copy(isRoutePaused = paused) }

    val currentState get() = _state.value
}
