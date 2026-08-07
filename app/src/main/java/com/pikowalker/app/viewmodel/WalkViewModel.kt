package com.pikowalker.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pikowalker.app.PikStepApp
import com.pikowalker.app.RouteRepository
import com.pikowalker.app.health.HealthConnectHelper
import com.pikowalker.app.model.GeoPoint
import com.pikowalker.app.model.SavedRoute
import com.pikowalker.app.model.ScheduleConfig
import com.pikowalker.app.model.WalkState
import com.pikowalker.app.model.WaypointLoopMode
import com.pikowalker.app.schedule.ScheduleManager
import com.pikowalker.app.service.MockLocationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WalkViewModel(application: Application) : AndroidViewModel(application) {

    private val repo         = (application as PikStepApp).walkRepository
    private val routeRepo    = (application as PikStepApp).routeRepository
    private val scheduleRepo = (application as PikStepApp).scheduleRepository
    private val healthConnectHelper = HealthConnectHelper(application)

    val walkState:      StateFlow<WalkState>        = repo.state
    val savedRoutes:     StateFlow<List<SavedRoute>> = routeRepo.routes
    val scheduleConfig: StateFlow<ScheduleConfig>    = scheduleRepo.config

    /** Persists the daily auto-start schedule and arms/cancels the underlying alarm to match. */
    fun updateSchedule(enabled: Boolean, hour: Int, minute: Int, routeId: String?) {
        val ctx = getApplication<Application>()
        scheduleRepo.save(ScheduleConfig(enabled, hour, minute, routeId))
        if (enabled && routeId != null) {
            ScheduleManager.scheduleNext(ctx, hour, minute)
        } else {
            ScheduleManager.cancel(ctx)
        }
    }

    private val _lastSavedName = MutableStateFlow<String?>(null)
    val lastSavedName: StateFlow<String?> = _lastSavedName.asStateFlow()

    /** A coordinate handed to us by another app (e.g. "用應用程式開啟" on a geo: link for a
     *  Pikmin Bloom flower/mushroom). Shown as a search-style pin for the user to confirm via
     *  設為模擬點 — arriving here never moves fake GPS on its own. */
    private val _pendingDeepLinkPoint = MutableStateFlow<GeoPoint?>(null)
    val pendingDeepLinkPoint: StateFlow<GeoPoint?> = _pendingDeepLinkPoint.asStateFlow()

    fun setDeepLinkPoint(lat: Double, lng: Double) {
        _pendingDeepLinkPoint.value = GeoPoint(lat, lng)
    }

    fun consumeDeepLinkPoint() {
        _pendingDeepLinkPoint.value = null
    }

    private val _todaySteps = MutableStateFlow(0L)
    val todaySteps: StateFlow<Long> = _todaySteps.asStateFlow()

    /** Re-reads today's Health Connect step total (all sources, not just PikoWalker). */
    fun refreshTodaySteps() {
        viewModelScope.launch {
            _todaySteps.value = healthConnectHelper.readTodaySteps()
        }
    }

    /** Manually corrects today's Health Connect step total toward [target]. See
     *  [HealthConnectHelper.adjustTodaySteps] for why a decrease may not land exactly on target. */
    fun applyTodayStepsTarget(target: Long) {
        viewModelScope.launch {
            healthConnectHelper.adjustTodaySteps(target)
            _todaySteps.value = healthConnectHelper.readTodaySteps()
        }
    }

    /** Tap on the map. In path mode, taps only append to the route being planned — the live
     *  faked position never moves until "走這條路徑" starts it. Outside path mode, a tap
     *  replaces the single point and (if fake GPS is already on) moves there immediately.
     *  Ignored while actively walking a route — stop first. */
    fun tapMap(lat: Double, lng: Double) {
        val state = repo.currentState
        if (state.isWalkingRoute) return
        if (state.isPathMode) {
            repo.addWaypoint(GeoPoint(lat, lng))
        } else {
            repo.clearWaypoints()
            repo.addWaypoint(GeoPoint(lat, lng))
            if (state.isSimulating) holdAt(lat, lng)
        }
    }

    /** Collapses the waypoint list down to wherever fake GPS currently is (even mid-walk),
     *  stopping any in-progress route walk first. Fake GPS itself stays on. */
    private fun collapseToCurrentPosition() {
        val state = repo.currentState
        if (!state.isSimulating) return
        if (state.isWalkingRoute) stopWalkingRoute()
        val current = GeoPoint(state.currentLat, state.currentLng)
        repo.clearWaypoints()
        repo.addWaypoint(current)
    }

    fun setPathMode(active: Boolean) {
        if (!active && repo.currentState.isPathMode) {
            collapseToCurrentPosition()
        }
        repo.setPathMode(active)
    }

    private fun sendHoldIntent(lat: Double, lng: Double) {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(MockLocationService.holdIntent(ctx, lat, lng))
    }

    /** Holds the mock position at a specific point. Only takes effect while fake GPS is
     *  already on — starting/stopping fake GPS is exclusively controlled by the
     *  start/stop button on the map, never as a side effect of anything else. */
    fun holdAt(lat: Double, lng: Double) {
        if (!repo.currentState.isSimulating) return
        sendHoldIntent(lat, lng)
    }

    /** The sole entry point that turns fake GPS on — called only by the map's start/stop button. */
    fun startSimulatingAt(lat: Double, lng: Double) {
        if (repo.currentState.waypoints.isEmpty()) {
            repo.addWaypoint(GeoPoint(lat, lng))
        }
        sendHoldIntent(lat, lng)
    }

    /** Starts walking the route from the beginning. No-op unless fake GPS is already on. */
    fun startWalkingRoute() {
        if (!repo.currentState.isSimulating) return
        val ctx = getApplication<Application>()
        ctx.startForegroundService(MockLocationService.startRouteIntent(ctx))
    }

    /** Resumes a paused route walk from wherever it left off (see [WalkState.isRoutePaused]).
     *  No-op unless fake GPS is already on. */
    fun resumeWalkingRoute() {
        if (!repo.currentState.isSimulating) return
        val ctx = getApplication<Application>()
        ctx.startForegroundService(MockLocationService.resumeRouteIntent(ctx))
    }

    /** Stops walking the route only — fake GPS stays on, settling at the last point reached.
     *  Turning fake GPS off entirely requires [stopSimulation]. */
    fun stopWalkingRoute() {
        val ctx = getApplication<Application>()
        ctx.startService(MockLocationService.stopRouteIntent(ctx))
    }

    /** The sole entry point that turns fake GPS off — called only by the map's start/stop button. */
    fun stopSimulation() {
        val ctx = getApplication<Application>()
        ctx.startService(MockLocationService.stopIntent(ctx))
    }

    fun setSpeedKmh(kmh: Double) = repo.setSpeedKmh(kmh)
    fun setStepLimit(limit: Long) = repo.setStepLimit(limit)
    fun setWaypointLoopMode(mode: WaypointLoopMode) = repo.setWaypointLoopMode(mode)

    fun removeWaypoint(index: Int) {
        repo.removeWaypoint(index)
        val state = repo.currentState
        if (!state.isPathMode && state.isSimulating && !state.isWalkingRoute) {
            state.waypoints.lastOrNull()?.let { holdAt(it.lat, it.lng) }
        }
    }

    /** Reorders waypoints — purely a path-planning action, never touches the live faked
     *  position (same rule as building a path in path mode). */
    fun moveWaypoint(from: Int, to: Int) = repo.moveWaypoint(from, to)

    fun clearPath() {
        if (repo.currentState.isSimulating) {
            collapseToCurrentPosition()
        } else {
            repo.clearWaypoints()
        }
    }

    fun saveRoute(name: String) {
        val state = repo.currentState
        val routeName = name.ifBlank {
            if (state.waypoints.size == 1) "單點定位" else "${state.waypoints.size} 點路線"
        }
        routeRepo.save(
            SavedRoute(
                id        = java.util.UUID.randomUUID().toString(),
                name      = routeName,
                waypoints = state.waypoints,
                loopMode  = state.waypointLoopMode,
                savedAt   = System.currentTimeMillis()
            )
        )
        _lastSavedName.value = routeName
        viewModelScope.launch {
            delay(3000)
            _lastSavedName.value = null
        }
    }

    /** Loads a saved route's waypoints onto the map without starting simulation. */
    fun loadRoute(savedRoute: SavedRoute) {
        repo.clearWaypoints()
        savedRoute.waypoints.forEach { repo.addWaypoint(it) }
        repo.setWaypointLoopMode(savedRoute.loopMode)
        repo.setPathMode(savedRoute.waypoints.size >= 2)
    }

    fun loadRouteAndWalk(savedRoute: SavedRoute) {
        loadRoute(savedRoute)
        val wps = savedRoute.waypoints
        when {
            wps.size >= 2 -> startWalkingRoute()
            wps.size == 1 -> holdAt(wps[0].lat, wps[0].lng)
        }
    }

    fun deleteRoute(id: String) = routeRepo.delete(id)
}
