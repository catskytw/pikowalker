package com.pikowalker.app

import android.app.Application
import android.location.LocationManager
import android.os.Build
import com.pikowalker.app.debug.CrashLogger
import com.pikowalker.app.debug.MainThreadWatchdog
import com.pikowalker.app.settings.AppSettings
import org.osmdroid.config.Configuration
import java.io.File

class PikStepApp : Application() {
    val walkRepository = WalkRepository()
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val scheduleRepository: ScheduleRepository by lazy { ScheduleRepository(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this) // first — catches failures in every init line below too
        CrashLogger.checkForPreviousAnr(this)
        MainThreadWatchdog.start()
        clearStaleMockProviders()
        initOsmdroid()
        AppSettings.init(this)
        routeRepository // eager init so SharedPreferences load before first UI frame
        scheduleRepository
    }

    /** osmdroid refuses to load tiles without a custom User-Agent (OSM's tile usage policy
     *  blocks the default one) and otherwise falls back to external storage for its cache,
     *  which needs a permission this app doesn't request — point it at the app's own cache dir
     *  instead, which needs nothing. */
    private fun initOsmdroid() {
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        config.osmdroidBasePath = File(cacheDir, "osmdroid").apply { mkdirs() }
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles").apply { mkdirs() }
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
