package com.pikowalker.app

import android.content.Context
import com.pikowalker.app.model.ScheduleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScheduleRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pikowalker_schedule", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<ScheduleConfig> = _config.asStateFlow()
    val current get() = _config.value

    private fun load(): ScheduleConfig = ScheduleConfig(
        enabled = prefs.getBoolean("enabled", false),
        hour = prefs.getInt("hour", 8),
        minute = prefs.getInt("minute", 0),
        routeId = prefs.getString("routeId", null)
    )

    fun save(config: ScheduleConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putInt("hour", config.hour)
            .putInt("minute", config.minute)
            .putString("routeId", config.routeId)
            .apply()
        _config.value = config
    }
}
