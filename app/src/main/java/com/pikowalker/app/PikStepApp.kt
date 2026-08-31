package com.pikowalker.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import com.pikowalker.app.debug.CrashLogger
import com.pikowalker.app.debug.DebugLogger
import com.pikowalker.app.debug.MainThreadWatchdog
import com.pikowalker.app.settings.AppSettings

class PikStepApp : Application() {
    val walkRepository = WalkRepository()
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val scheduleRepository: ScheduleRepository by lazy { ScheduleRepository(this) }
    val pureSpotRepository: PureSpotRepository by lazy { PureSpotRepository(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this) // first — catches failures in every init line below too
        CrashLogger.checkForPreviousAnr(this)
        MainThreadWatchdog.start()
        clearStaleMockProviders()
        registerTrimMemoryTracking()
        AppSettings.init(this)
        routeRepository // eager init so SharedPreferences load before first UI frame
        scheduleRepository
    }

    /** onTrimMemory is Android's own purpose-built signal for "the system just re-evaluated this
     *  app's importance" — a much more direct read than inferring it from Activity lifecycle
     *  events, which only tell us about UI visibility, not process priority. Tracked here (not
     *  just logged) so [DebugLogger]'s failure-time diagnostics can report the most recent level
     *  even when the failure itself doesn't happen to coincide with a fresh callback. */
    private fun registerTrimMemoryTracking() {
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                DebugLogger.lastTrimMemoryLevel = level
                DebugLogger.log("System", "onTrimMemory level=${trimMemoryLevelName(level)}")
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
    }

    /** If a previous process died abnormally while a mock location provider was still
     *  registered (e.g. killed mid-walk after the service's wake lock lapsed), the system's
     *  LocationManagerService can be left holding a stale registration — which on some OEM
     *  builds has been observed to wedge the location stack until the device is rebooted.
     *  Clearing any leftover registration here, before anything else touches location, lets a
     *  fresh launch self-heal instead. */
    private fun clearStaleMockProviders() {
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
        providers.forEach { runCatching { lm.removeTestProvider(it) } }
    }
}

private fun trimMemoryLevelName(level: Int): String = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE($level)"
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW($level)"
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL($level)"
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN($level)"
    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND($level)"
    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE($level)"
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE($level)"
    else -> "UNKNOWN($level)"
}
