package com.pikowalker.app.model

data class SavedRoute(
    val id: String,
    val name: String,
    val waypoints: List<GeoPoint>,
    val loopMode: WaypointLoopMode,
    val savedAt: Long,
    /** Just a name, not a separate entity — a folder exists exactly as long as some route carries
     *  its name, so there's nothing to create/delete/keep in sync. Blank = not filed anywhere. */
    val folder: String = ""
) {
    /** What the schedule picker shows — the same name can legitimately exist in two folders. */
    val displayName: String get() = if (folder.isBlank()) name else "$folder / $name"
}
