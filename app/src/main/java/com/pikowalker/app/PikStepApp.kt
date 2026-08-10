package com.pikowalker.app

import android.app.Application
import com.pikowalker.app.debug.DebugLogger
import org.maplibre.android.MapLibre

class PikStepApp : Application() {
    val walkRepository = WalkRepository()
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val scheduleRepository: ScheduleRepository by lazy { ScheduleRepository(this) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        DebugLogger.init(this)
        routeRepository // eager init so SharedPreferences load before first UI frame
        scheduleRepository
    }
}
