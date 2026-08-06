package com.pikowalker.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pikowalker.app.PikStepApp

/** Alarms don't survive a reboot — re-arm the daily schedule if one is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PikStepApp
        val config = app.scheduleRepository.current
        if (config.enabled && config.routeId != null) {
            ScheduleManager.scheduleNext(context, config.hour, config.minute)
        }
    }
}
