package com.pikowalker.app.model

data class SavedRoute(
    val id: String,
    val name: String,
    val waypoints: List<GeoPoint>,
    val loopMode: WaypointLoopMode,
    val savedAt: Long
)
