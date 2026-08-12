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
        checkPermissions()
        checkHealthConnect()
        handleIncomingIntent(intent)
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

    override fun onResume() {
        super.onResume()
        checkHealthConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Two ways another app can hand us a location:
     *  - ACTION_VIEW on a geo: link ("用應用程式開啟" on a spot that offers a real chooser).
     *  - ACTION_SEND of text (Google Maps' own "分享" button on a pin) — used e.g. when the
     *    other app hard-codes Maps as the opener (like Pikmin Bloom does) and Maps itself is
     *    the only way to get the coordinate back out. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                parseGeoUri(uri)?.let { (lat, lng) -> viewModel.setDeepLinkPoint(lat, lng) }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
                }
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
        if (text.isNullOrBlank()) return
        parseCoordsFromText(text)?.let { (lat, lng) -> viewModel.setDeepLinkPoint(lat, lng); return }

        // Shortened links (goo.gl/maps, maps.app.goo.gl) don't carry visible coordinates —
        // resolve the redirect chain first, then parse the final URL the same way. goo.gl in
        // particular (it's a deprecated, increasingly flaky Google service) has been seen to
        // intermittently fail a link that resolves fine moments later, so retry a few times.
        val url = Regex("https?://\\S+").find(text)?.value ?: return
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
                    viewModel.setError("這是商家地點連結，Google 沒有把座標放進連結裡，請改用複製貼上經緯度")
                } else {
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
