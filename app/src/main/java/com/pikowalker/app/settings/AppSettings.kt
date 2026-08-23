package com.pikowalker.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppSettings {
    private const val PREFS = "pikowalker_settings"
    private const val KEY_WRITE_STEPS = "write_steps_enabled"

    private val _writeStepsEnabled = MutableStateFlow(true)

    /** Exposed as a flow so every UI surface showing this toggle (Settings, and the map
     *  screen's route panels) stays in sync when it's changed from any one of them. */
    val writeStepsEnabledFlow: StateFlow<Boolean> = _writeStepsEnabled.asStateFlow()

    val writeStepsEnabled: Boolean get() = _writeStepsEnabled.value

    fun init(context: Context) {
        _writeStepsEnabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WRITE_STEPS, true)
    }

    fun setWriteStepsEnabled(context: Context, value: Boolean) {
        _writeStepsEnabled.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WRITE_STEPS, value)
            .apply()
    }
}
