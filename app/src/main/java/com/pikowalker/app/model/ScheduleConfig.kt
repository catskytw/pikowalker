package com.pikowalker.app.model

data class ScheduleConfig(
    val enabled: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 0,
    val routeId: String? = null
)
