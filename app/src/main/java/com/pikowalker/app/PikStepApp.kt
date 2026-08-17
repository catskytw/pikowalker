package com.pikowalker.app

import android.app.Application
import com.pikowalker.app.debug.CrashLogger
import com.pikowalker.app.settings.AppSettings
import org.maplibre.android.MapLibre

class PikStepApp : Application() {
    val walkRepository = WalkRepository()
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val scheduleRepository: ScheduleRepository by lazy { ScheduleRepository(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this) // first — catches failures in every init line below too
        MapLibre.getInstance(this)
        AppSettings.init(this)
        routeRepository // eager init so SharedPreferences load before first UI frame
        scheduleRepository
    }
}
