package com.pikowalker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.pikowalker.app.debug.DebugLogger
import com.pikowalker.app.health.HealthConnectHelper
import com.pikowalker.app.ui.navigation.PikoWalkerNavGraph
import com.pikowalker.app.ui.theme.PikoWalkerTheme
import com.pikowalker.app.viewmodel.WalkViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private val viewModel: WalkViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    // Health Connect permission launcher
    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val hasAll = granted.containsAll(HealthConnectHelper.PERMISSIONS)
        (application as PikStepApp).walkRepository.setHealthConnect(
            available = true,
            hasPermission = hasAll
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log("Activity", "onCreate savedInstanceState=${savedInstanceState != null} task=${taskId}")
        checkPermissions()
        checkHealthConnect()
        handleIncomingIntent(intent)
        clearIntentData()
        setContent {
            PikoWalkerTheme {
                if (permissionsGranted) {
                    PikoWalkerNavGraph(
                        viewModel,
                        onRequestHcPermission = { requestHcPermission() }
                    )
                } else {
                    PermissionScreen(onRequest = { permissionLauncher.launch(requiredPermissions) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DebugLogger.log("Activity", "onStart")
    }

    override fun onStop() {
        super.onStop()
        DebugLogger.log("Activity", "onStop isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
    }

    override fun onResume() {
        super.onResume()
        DebugLogger.log("Activity", "onResume")
        checkHealthConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        clearIntentData()
    }

    /** getIntent() otherwise keeps returning whatever ACTION_VIEW/ACTION_SEND intent last
     *  started or was delivered to this Activity — including on a system-restored task — so
     *  once it's been handled, replace it with an inert one or it gets silently reprocessed
     *  forever, re-showing the 設為模擬點 prompt for a link the user already dealt with. */
    private fun clearIntentData() {
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Two ways another app can hand us a location:
     *  - ACTION_VIEW on a geo: link ("用應用程式開啟" on a spot that offers a real chooser).
     *  - ACTION_SEND of text (Google Maps' own "分享" button on a pin) — used e.g. when the
     *    other app hard-codes Maps as the opener (like Pikmin Bloom does) and Maps itself is
     *    the only way to get the coordinate back out. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        DebugLogger.log("Share", "handleIncomingIntent action=${intent.action} type=${intent.type} hasData=${intent.data != null}")
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                val parsed = parseGeoUri(uri)
                if (parsed != null) {
                    viewModel.setDeepLinkPoint(parsed.first, parsed.second)
                } else {
                    DebugLogger.log("Share", "geo: URI 無法解析 scheme=${uri.scheme}")
                    recordShareParseFailure("geo_uri")
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
                } else {
                    DebugLogger.log("Share", "ACTION_SEND 但 type 不是 text/plain：${intent.type}")
                }
            }
        }
    }

    /** Non-fatal purely for frequency data on the Crashlytics dashboard — deliberately doesn't
     *  include the shared text/URL itself (that's the user's location data; it stays in the
     *  local DebugLogger export only, never leaves the device unless they choose to share it). */
    private fun recordShareParseFailure(reason: String) {
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                setCustomKey("reason", reason)
                recordException(IllegalStateException("shared location text/link could not be parsed: $reason"))
            }
        }
    }

    private fun parseGeoUri(uri: Uri): Pair<Double, Double>? {
        if (uri.scheme != "geo") return null
        val raw = uri.schemeSpecificPart ?: return null
        val coordRegex = Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)")

        // "geo:0,0?q=24.123,121.456(label)" style — the q param carries the real coordinate.
        val qValue = Regex("[?&]q=([^&]+)").find(raw)?.groupValues?.get(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
        val match = qValue?.let { coordRegex.find(it) } ?: coordRegex.find(raw.substringBefore("?"))
        val lat = match?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        return lat to lng
    }

    /** Coordinate patterns seen in Google Maps share text/links, checked in order of
     *  reliability: the place-page's own !3d/!4d pair (exact pin), then the viewport "@lat,lng"
     *  center, then the older q=/ll= query-param forms. */
    private val mapsCoordPatterns = listOf(
        Regex("!3d(-?\\d{1,3}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"),
        Regex("@(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
        Regex("[?&]q=(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
        Regex("[?&]ll=(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
    )

    private fun parseCoordsFromText(text: String): Pair<Double, Double>? {
        for (pattern in mapsCoordPatterns) {
            val m = pattern.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            return lat to lng
        }
        return null
    }

    private fun handleSharedText(text: String?) {
        DebugLogger.log("Share", "handleSharedText 收到文字，長度=${text?.length ?: 0}")
        if (text.isNullOrBlank()) {
            DebugLogger.log("Share", "分享文字是空的，不處理")
            return
        }
        RouteShareCodec.decode(text)?.let { route -> viewModel.setPendingImportRoute(route); return }
        parseCoordsFromText(text)?.let { (lat, lng) -> viewModel.setDeepLinkPoint(lat, lng); return }

        // Shortened links (goo.gl/maps, maps.app.goo.gl) don't carry visible coordinates —
        // resolve the redirect chain first, then parse the final URL the same way. goo.gl in
        // particular (it's a deprecated, increasingly flaky Google service) has been seen to
        // intermittently fail a link that resolves fine moments later, so retry a few times.
        val url = Regex("https?://\\S+").find(text)?.value
        if (url == null) {
            // Neither a route code, a direct coordinate, nor a URL — nothing left to try. This
            // used to silently no-op, which is exactly what "分享沒反應" looks like from outside.
            DebugLogger.log("Share", "文字裡沒有路線代碼、座標，也沒有網址，放棄解析")
            recordShareParseFailure("no_url_no_coords")
            return
        }
        lifecycleScope.launch {
            viewModel.setResolvingSharedLink(true)
            try {
                var resolved: String? = null
                var coords: Pair<Double, Double>? = null
                repeat(3) { attempt ->
                    if (coords != null) return@repeat
                    if (attempt > 0) delay(500)
                    resolved = resolveFinalUrl(url)
                    coords = resolved?.let { parseCoordsFromText(it) }
                }
                if (coords != null) {
                    val (lat, lng) = coords!!
                    viewModel.setDeepLinkPoint(lat, lng)
                } else if (resolved?.contains("/maps/place/") == true) {
                    // A business/POI share references Google's internal place ID rather than
                    // embedding a coordinate — nothing here to parse without scraping their page.
                    DebugLogger.log("Share", "解析到商家地點連結，Google 沒有內嵌座標")
                    recordShareParseFailure("place_id_no_coords")
                    viewModel.setError("這是商家地點連結，Google 沒有把座標放進連結裡，請改用複製貼上經緯度")
                } else {
                    DebugLogger.log("Share", "解析失敗，resolved=${resolved != null}")
                    recordShareParseFailure("url_resolved_but_unparseable")
                    viewModel.setError("無法解析分享的地圖連結，請改用複製貼上經緯度")
                }
            } finally {
                viewModel.setResolvingSharedLink(false)
            }
        }
    }

    private suspend fun resolveFinalUrl(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            var current = url
            repeat(5) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null) {
                        current = if (location.startsWith("http")) location
                        else URL(URL(current), location).toString()
                        return@repeat
                    }
                } else {
                    conn.inputStream.close()
                    if (code == 200) return@runCatching current
                    return@runCatching null
                }
            }
            current
        }.getOrNull()
    }

    private fun checkPermissions() {
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun checkHealthConnect() {
        val available = HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        if (!available) {
            (application as PikStepApp).walkRepository.setHealthConnect(available = false, hasPermission = false)
            return
        }
        lifecycleScope.launch {
            val helper = HealthConnectHelper(this@MainActivity)
            val has = helper.hasPermissions()
            (application as PikStepApp).walkRepository.setHealthConnect(available = true, hasPermission = has)
        }
    }

    fun requestHcPermission() {
        hcPermissionLauncher.launch(HealthConnectHelper.PERMISSIONS)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("PikoWalker 需要以下權限才能運作：",
                style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• 位置權限（模擬 GPS 位置）", style = MaterialTheme.typography.bodyMedium)
            Text("• 通知權限（背景執行狀態列）", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("授予權限") }
        }
    }
}
