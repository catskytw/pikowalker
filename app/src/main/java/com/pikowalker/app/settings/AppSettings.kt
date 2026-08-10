package com.pikowalker.app.settings

import android.content.Context

object AppSettings {
    private const val PREFS = "pikowalker_settings"
    private const val KEY_WRITE_STEPS = "write_steps_enabled"

    @Volatile var writeStepsEnabled: Boolean = true
        private set

    fun init(context: Context) {
        writeStepsEnabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WRITE_STEPS, true)
    }

    fun setWriteStepsEnabled(context: Context, value: Boolean) {
        writeStepsEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WRITE_STEPS, value)
            .apply()
    }
}
