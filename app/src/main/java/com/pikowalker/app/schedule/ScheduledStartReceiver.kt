package com.pikowalker.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pikowalker.app.PikStepApp
import com.pikowalker.app.debug.DebugLogger
import com.pikowalker.app.service.MockLocationService

/** Fires when the daily schedule alarm goes off: loads the configured saved route and starts
 *  fake GPS (holding at the first point, then walking if it's a multi-point route), the
 *  automated equivalent of the user pressing the map's start button themselves. */
class ScheduledStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PikStepApp
        val config = app.scheduleRepository.current
        if (!config.enabled) return

        val route = app.routeRepository.routes.value.find { it.id == config.routeId }
        if (route != null && route.waypoints.isNotEmpty()) {
            app.walkRepository.clearWaypoints()
            route.waypoints.forEach { app.walkRepository.addWaypoint(it) }
            app.walkRepository.setWaypointLoopMode(route.loopMode)
            app.walkRepository.setPathMode(route.waypoints.size >= 2)

            // Starting a location-type foreground service from here (no visible UI, possibly
            // hours since the app was last opened) requires ACCESS_BACKGROUND_LOCATION — without
            // it the system throws a SecurityException instead of just denying quietly. That's
            // opt-in from 設定 (see SettingsScreen's 排程自動開始 section), so a user who hasn't
            // granted it yet must not crash the whole app over one missed scheduled walk.
            try {
                val first = route.waypoints.first()
                context.startForegroundService(MockLocationService.holdIntent(context, first.lat, first.lng))
                if (route.waypoints.size >= 2) {
                    context.startForegroundService(MockLocationService.startRouteIntent(context))
                }
            } catch (e: SecurityException) {
                DebugLogger.log("Schedule", "排程自動開始失敗，可能缺少背景定位權限：$e")
            }
        }

        // One-shot alarms don't repeat on their own — re-arm for the same time tomorrow.
        ScheduleManager.scheduleNext(context, config.hour, config.minute)
    }
}
