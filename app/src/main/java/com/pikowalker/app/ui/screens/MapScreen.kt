package com.pikowalker.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Address
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.pikowalker.app.geocoding.GeocodingRepository
import com.pikowalker.app.RouteShareCodec
import com.pikowalker.app.settings.AppSettings
import com.pikowalker.app.model.GeoPoint
import com.pikowalker.app.model.SavedRoute
import com.pikowalker.app.model.WalkSpeed
import com.pikowalker.app.model.WalkState
import com.pikowalker.app.model.WaypointLoopMode
import com.pikowalker.app.ui.theme.*
import com.pikowalker.app.viewmodel.WalkViewModel
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.pikowalker.app.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.*

private const val MAX_PINS = 20

// Switching tabs disposes and recreates MapScreen's whole composition — a plain remember{}
// zoom var would reset every time. Holding it at the file/object level instead survives that,
// so leaving 地圖 and coming back keeps whatever zoom the user had.
private var lastMapZoom = 16f

// Hides most POI icons/labels (shops, clinics, places of worship, transit stops) that were
// cluttering the view at street zoom, while leaving parks/attractions, roads and water alone —
// Google's style schema has no "village/里 boundary" knob, global map data doesn't carry that
// granularity, so this is the closest available approximation of "just landmarks, not addresses".
private const val MAP_STYLE_JSON = """
[
  {"featureType":"poi.business","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.medical","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.place_of_worship","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.government","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.school","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.sports_complex","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]}
]
"""

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(viewModel: WalkViewModel) {
    val state by viewModel.walkState.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    val lastSavedName by viewModel.lastSavedName.collectAsState()
    val pendingDeepLinkPoint by viewModel.pendingDeepLinkPoint.collectAsState()
    val resolvingSharedLink by viewModel.resolvingSharedLink.collectAsState()
    val pendingImportRoute by viewModel.pendingImportRoute.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geocodingRepo = remember { GeocodingRepository(context) }

    var searchResult by remember { mutableStateOf<GeoPoint?>(null) }
    var searchCandidates by remember { mutableStateOf<List<Address>>(emptyList()) }
    var showSavedRoutesDialog by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var userBearing by remember { mutableStateOf(0f) }

    // Re-checked fresh every time this composable is (re)entered — MapScreen's whole composition
    // gets disposed and recreated on every tab switch (see lastMapZoom above), so this naturally
    // re-verifies without any extra lifecycle wiring, including right after the user comes back
    // from fixing it in 設定. Dismissing only lasts for this visit — not persisted — because unlike
    // "虛擬位置應用程式" (which blocks 開始偽造GPS outright with its own error) battery optimization
    // fails silently in the background, so a one-time-ever dismissal could hide a real problem.
    val batteryOptExempt = remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    var batteryBannerDismissed by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        val initLat = if (state.currentLat != 0.0) state.currentLat else 25.0330
        val initLng = if (state.currentLng != 0.0) state.currentLng else 121.5654
        position = CameraPosition.fromLatLngZoom(LatLng(initLat, initLng), lastMapZoom)
    }
    val mapProperties = remember {
        MapProperties(
            mapStyleOptions = MapStyleOptions(MAP_STYLE_JSON),
            minZoomPreference = 3f,
            maxZoomPreference = 20f
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            // Google's own compass only fades in once the map is actually rotated off north,
            // which reads as "the compass disappeared" compared to the always-visible button
            // this had before — a custom always-on one below replaces it instead.
            compassEnabled = false,
            rotationGesturesEnabled = true
        )
    }

    // Deliberately doesn't touch zoom — re-centering (search result, saved route, my-location)
    // shouldn't undo a zoom level the user already chose.
    fun flyTo(lat: Double, lng: Double) {
        scope.launch {
            cameraPositionState.animate(CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
        }
    }

    fun resetNorth() {
        scope.launch {
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(cameraPositionState.position).bearing(0f).build()
                )
            )
        }
    }

    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.position.zoom }.collect { lastMapZoom = it }
    }

    // Nothing faked yet and no waypoints ever placed — the only sensible starting view is
    // wherever the phone actually is. getLastKnownLocation() alone often comes back empty on
    // a phone that hasn't used real GPS recently, so fall back to requesting one live fix.
    LaunchedEffect(Unit) {
        if (state.currentLat == 0.0 && state.currentLng == 0.0) {
            getFreshLocation(context)?.let { loc ->
                userLocation = LatLng(loc.latitude, loc.longitude)
                userBearing = loc.bearing
                cameraPositionState.animate(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)))
            }
        }
    }

    // A coordinate handed to us by another app (e.g. opening a Pikmin Bloom flower/mushroom's
    // geo: link with PikoWalker). Treated exactly like a search result — just a pin to confirm
    // via 設為模擬點, never moves fake GPS on its own.
    LaunchedEffect(pendingDeepLinkPoint) {
        val point = pendingDeepLinkPoint ?: return@LaunchedEffect
        flyTo(point.lat, point.lng)
        searchResult = point
        searchCandidates = emptyList()
        viewModel.consumeDeepLinkPoint()
    }

    // Update user-location icon + animate camera while statically holding
    LaunchedEffect(state.currentLat, state.currentLng) {
        if (state.currentLat != 0.0 || state.currentLng != 0.0) {
            val pos = LatLng(state.currentLat, state.currentLng)
            userLocation = pos
            userBearing = state.currentBearing
            if (state.isStaticAtWaypoint) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLng(pos))
            }
        }
    }

    // Poll real GPS to keep the person icon moving when no mock is active. Always re-fetches
    // (rather than gating on currentLat/Lng being unset) so it resumes correctly after fake
    // GPS has been used at least once — currentLat/Lng retain the last faked position and
    // never reset to 0,0 on their own.
    LaunchedEffect(state.isSimulating) {
        if (!state.isSimulating) {
            while (true) {
                getLastKnownLocation(context)?.let { loc ->
                    userLocation = LatLng(loc.latitude, loc.longitude)
                    userBearing = loc.bearing
                }
                kotlinx.coroutines.delay(3_000)
            }
        }
    }

    val routeColor = remember { Color(0xFF2D6A4F) }
    val routeWidthPx = with(LocalDensity.current) { 5.dp.toPx() }
    val userLocationBitmap = remember { userLocationBitmap(context) }
    val bearingBitmap = remember { bearingIndicatorBitmap(context) }
    val searchPinBitmap = remember { createSearchPinBitmap() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = mapProperties,
                uiSettings = mapUiSettings,
                onMapClick = { latLng ->
                    searchResult = null
                    searchCandidates = emptyList()
                    viewModel.tapMap(latLng.latitude, latLng.longitude)
                }
            ) {
                if (state.waypoints.size >= 2) {
                    val coords = buildList {
                        addAll(state.waypoints)
                        when (state.waypointLoopMode) {
                            WaypointLoopMode.LOOP -> add(state.waypoints[0])
                            WaypointLoopMode.PING_PONG -> addAll(state.waypoints.asReversed().drop(1))
                            WaypointLoopMode.STOP_AT_END -> {}
                        }
                    }
                    Polyline(
                        points = coords.map { LatLng(it.lat, it.lng) },
                        color = routeColor,
                        width = routeWidthPx,
                        jointType = JointType.ROUND
                    )
                }

                state.waypoints.forEachIndexed { i, wp ->
                    val markerState = remember(wp.lat, wp.lng) { MarkerState(position = LatLng(wp.lat, wp.lng)) }
                    val icon = remember(i, state.waypoints.size) {
                        BitmapDescriptorFactory.fromBitmap(createPinBitmap((i + 1).coerceAtMost(MAX_PINS)))
                    }
                    Marker(state = markerState, icon = icon, anchor = Offset(0.5f, 1f), zIndex = 0f)
                }

                searchResult?.let { result ->
                    Marker(
                        state = remember(result) { MarkerState(position = LatLng(result.lat, result.lng)) },
                        icon = BitmapDescriptorFactory.fromBitmap(searchPinBitmap),
                        anchor = Offset(0.5f, 1f),
                        zIndex = 0.5f
                    )
                }

                userLocation?.let { pos ->
                    Marker(
                        state = remember(pos) { MarkerState(position = pos) },
                        icon = BitmapDescriptorFactory.fromBitmap(bearingBitmap),
                        anchor = Offset(0.5f, 0.5f),
                        rotation = userBearing,
                        flat = true,
                        zIndex = 1f
                    )
                    Marker(
                        state = remember(pos) { MarkerState(position = pos) },
                        icon = BitmapDescriptorFactory.fromBitmap(userLocationBitmap),
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 1f
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MapSearchBar(
                        geocodingRepo = geocodingRepo,
                        scope = scope,
                        onResult = { lat, lng ->
                            flyTo(lat, lng)
                            searchResult = GeoPoint(lat, lng)
                            searchCandidates = emptyList()
                        },
                        onMultipleResults = { candidates ->
                            searchCandidates = candidates
                            searchResult = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(19.dp),
                        color = Color.White,
                        shadowElevation = 3.dp,
                        onClick = { showSavedRoutesDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Bookmarks, "已存路線",
                                Modifier.size(18.dp), tint = ForestGreen
                            )
                        }
                    }
                }
                if (searchCandidates.isNotEmpty()) {
                    SearchCandidatesList(
                        candidates = searchCandidates,
                        onPick = { address ->
                            flyTo(address.latitude, address.longitude)
                            searchResult = GeoPoint(address.latitude, address.longitude)
                            searchCandidates = emptyList()
                        },
                        onDismiss = { searchCandidates = emptyList() }
                    )
                }
                searchResult?.let { result ->
                    SearchResultBar(
                        result = result,
                        enabled = !state.isWalkingRoute,
                        onUseAsPoint = {
                            viewModel.tapMap(result.lat, result.lng)
                            searchResult = null
                        },
                        onDismiss = { searchResult = null }
                    )
                }
                if (resolvingSharedLink) {
                    Surface(shape = RoundedCornerShape(12.dp), color = ForestGreen.copy(alpha = 0.92f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("解析分享連結中…", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
                state.errorMessage?.let { msg ->
                    Surface(shape = RoundedCornerShape(12.dp), color = EarthRed.copy(alpha = 0.92f)) {
                        Text(
                            "⚠ $msg",
                            fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                pendingImportRoute?.let { route ->
                    Surface(shape = RoundedCornerShape(12.dp), color = ForestGreen.copy(alpha = 0.92f)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            val subtitle = if (route.waypoints.size == 1) "單點定位"
                                else "${route.waypoints.size} 個路標 · ${route.loopMode.label}"
                            Text("偵測到分享的路線「${route.name}」", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(subtitle, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { viewModel.confirmImportRoute() },
                                    modifier = Modifier.heightIn(min = 28.dp),
                                    shape = RoundedCornerShape(7.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ForestGreenDark),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) { Text("匯入", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                OutlinedButton(
                                    onClick = { viewModel.dismissImportRoute() },
                                    modifier = Modifier.heightIn(min = 28.dp),
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) { Text("取消", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                if (!batteryOptExempt && !batteryBannerDismissed) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF57C00).copy(alpha = 0.92f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Text(
                                "電池最佳化未排除，背景偽造GPS可能被系統暫停",
                                fontSize = 11.sp, color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                }
                            ) { Text("去設定", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                            Icon(
                                Icons.Default.Close, "關閉提示",
                                modifier = Modifier.size(16.dp).clickable { batteryBannerDismissed = true },
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            SmallFloatingActionButton(
                onClick = { resetNorth() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 132.dp),
                containerColor = Color.White,
                contentColor = ForestGreen,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(
                    Icons.Default.Navigation, "指北針，點一下轉回正北",
                    Modifier.size(20.dp).rotate(-cameraPositionState.position.bearing)
                )
            }

            SmallFloatingActionButton(
                onClick = {
                    getLastKnownLocation(context)?.let { loc -> flyTo(loc.latitude, loc.longitude) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 72.dp),
                containerColor = Color.White,
                contentColor = ForestGreen,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Filled.MyLocation, "定位", Modifier.size(20.dp))
            }

            FakeGpsButton(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                onStart = {
                    val last = state.waypoints.lastOrNull()
                    val started = if (last != null) {
                        viewModel.startSimulatingAt(last.lat, last.lng); true
                    } else {
                        getLastKnownLocation(context)?.let { loc -> viewModel.startSimulatingAt(loc.latitude, loc.longitude); true } ?: false
                    }
                    val message = if (started) "正在偽造GPS" else "請先點地圖選一個點位，或到「已存路線」載入一條路線"
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                },
                onStop = { viewModel.stopSimulation() }
            )

            SmallFloatingActionButton(
                onClick = { viewModel.setPathMode(!state.isPathMode) },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 72.dp),
                containerColor = if (state.isPathMode) ForestGreen else Color.White,
                contentColor = if (state.isPathMode) Color.White else ForestGreen,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Default.Route, "路徑模式", Modifier.size(20.dp))
            }
        }

        SimulationBottomSheet(
            state = state,
            lastSavedName = lastSavedName,
            viewModel = viewModel
        )
    }

    if (showSavedRoutesDialog) {
        SavedRoutesDialog(
            savedRoutes = savedRoutes,
            isSimulating = state.isSimulating,
            onFlyTo = { lat, lng -> flyTo(lat, lng) },
            onLoad = { viewModel.loadRoute(it); showSavedRoutesDialog = false },
            onLoadAndWalk = { viewModel.loadRouteAndWalk(it); showSavedRoutesDialog = false },
            onShare = { route ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, RouteShareCodec.shareText(route))
                }
                context.startActivity(Intent.createChooser(intent, "分享路線"))
            },
            onImportRoute = { route ->
                viewModel.setPendingImportRoute(route)
                showSavedRoutesDialog = false
            },
            onDelete = { viewModel.deleteRoute(it) },
            onDismiss = { showSavedRoutesDialog = false }
        )
    }
}

// ── Search bar ─────────────────────────────────────────────────────────────────

private val LAT_LNG_REGEX = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$""")

@Composable
private fun MapSearchBar(
    geocodingRepo: GeocodingRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (Double, Double) -> Unit,
    onMultipleResults: (List<Address>) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        val m = LAT_LNG_REGEX.matchEntire(q)
        if (m != null) {
            onResult(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            return
        }
        searching = true
        scope.launch {
            val results = runCatching { geocodingRepo.searchAddress(q) }.getOrNull() ?: emptyList()
            searching = false
            when {
                results.isEmpty() -> {}
                results.size == 1 -> onResult(results[0].latitude, results[0].longitude)
                else -> onMultipleResults(results)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().height(38.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(15.dp), tint = StoneGray)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("搜尋地標或輸入經緯度", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF333333),
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    cursorBrush = SolidColor(ForestGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = ForestGreen)
            } else if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close, null,
                    modifier = Modifier.size(14.dp).clickable { query = "" },
                    tint = StoneGray
                )
            }
        }
    }
}

@Composable
private fun SearchCandidatesList(
    candidates: List<Address>,
    onPick: (Address) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "找到 ${candidates.size} 筆結果，請選一個",
                    fontSize = 10.sp, color = StoneGray, modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Close, null,
                    modifier = Modifier.size(14.dp).clickable { onDismiss() },
                    tint = StoneGray
                )
            }
            candidates.forEachIndexed { i, address ->
                val label = address.getAddressLine(0)
                    ?: listOfNotNull(address.featureName, address.locality, address.adminArea)
                        .joinToString(", ")
                        .ifBlank { "%.6f, %.6f".format(address.latitude, address.longitude) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(address) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, null, Modifier.size(14.dp), tint = Color(0xFF1E88E5))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label, fontSize = 12.sp, color = Color(0xFF333333),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (i < candidates.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(start = 12.dp, end = 12.dp), color = Color(0xFFF0F0F0))
                }
            }
        }
    }
}

@Composable
private fun SearchResultBar(
    result: GeoPoint,
    enabled: Boolean,
    onUseAsPoint: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(15.dp), tint = Color(0xFF1E88E5))
                Spacer(Modifier.width(6.dp))
                Text(
                    "%.6f, %.6f".format(result.lat, result.lng),
                    fontSize = 11.sp, color = Color(0xFF555555),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onUseAsPoint,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "設為模擬點", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (enabled) ForestGreen else Color(0xFFBBBBBB)
                    )
                }
                Icon(
                    Icons.Default.Close, null,
                    modifier = Modifier.size(14.dp).clickable { onDismiss() },
                    tint = StoneGray
                )
            }
            if (!enabled) {
                Text(
                    "走路中無法設定新的模擬點，請先停止模擬走路",
                    fontSize = 9.sp, color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                )
            }
        }
    }
}

// ── Fake GPS button ────────────────────────────────────────────────────────────

@Composable
private fun FakeGpsButton(
    state: WalkState,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val simulating = state.isSimulating
    Button(
        onClick = { if (simulating) onStop() else onStart() },
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (simulating) EarthRed else ForestGreen,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Text(
            if (simulating) "停止偽造GPS" else "▶ 開始偽造GPS",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Bottom sheet ──────────────────────────────────────────────────────────────

@Composable
private fun SimulationBottomSheet(
    state: WalkState,
    lastSavedName: String?,
    viewModel: WalkViewModel
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val headerLabel = when {
        state.isWalkingRoute -> "走路中 · ${"%,d".format(state.steps)} 步 · ${state.distanceKm} km"
        state.waypoints.isEmpty() -> "尚未設定模擬位置"
        state.waypoints.size == 1 -> "單點定位"
        else -> "${state.waypoints.size} 個路標"
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shadowElevation = 10.dp) {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    headerLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF555555), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    null, Modifier.size(18.dp), tint = StoneGray
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isWalkingRoute) {
                        WalkingSheetContent(state, viewModel)
                    } else {
                        PathTabContent(state, viewModel, lastSavedName)
                    }
                }
            }
        }
    }
}

@Composable
private fun WalkingSheetContent(state: WalkState, viewModel: WalkViewModel) {
    Text(
        "${state.waypointLoopMode.label} · ${state.elapsedTime}",
        fontSize = 11.sp, color = StoneGray
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("速度（可即時調整）")
        SpeedPickerRow(state, viewModel)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("步數上限(步)（可即時調整）")
        StepLimitPickerRow(state, viewModel)
    }
    WriteStepsToggleRow(state, liveHint = true)
    Button(
        onClick = { viewModel.stopWalkingRoute() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EarthRed, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("停止模擬走路", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedPickerRow(state: WalkState, viewModel: WalkViewModel) {
    var showSpeedDialog by remember { mutableStateOf(false) }
    if (showSpeedDialog) {
        CustomSpeedDialog(
            currentKmh = state.speedKmh,
            onConfirm = { viewModel.setSpeedKmh(it); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false }
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        WalkSpeed.entries.forEach { speed ->
            ModePill(
                label = speed.label,
                selected = state.speedKmh == speed.kmh,
                modifier = Modifier.weight(1f)
            ) { viewModel.setSpeedKmh(speed.kmh) }
        }
        val isCustom = WalkSpeed.entries.none { it.kmh == state.speedKmh }
        ModePill(
            label = if (isCustom) "自訂 ${"%.1f".format(state.speedKmh)}" else "自訂",
            selected = isCustom,
            modifier = Modifier.weight(1f)
        ) { showSpeedDialog = true }
    }
}

/** Surfaced here too (not just in Settings) so starting or adjusting a walk and deciding
 *  whether it writes steps happen in the same place — previously this lived only in Settings,
 *  a different top-level screen entirely, disconnected from the route-starting flow. Shares
 *  [AppSettings.writeStepsEnabledFlow] with the Settings copy so either one stays in sync. */
@Composable
private fun WriteStepsToggleRow(state: WalkState, liveHint: Boolean) {
    if (!state.healthConnectAvailable) return
    val context = LocalContext.current
    val enabled by AppSettings.writeStepsEnabledFlow.collectAsState()
    val hcPermitted = state.hasHealthPermission
    val grayed = Color(0xFFBDBDBD)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (liveHint) "寫入步數（可即時調整）" else "寫入步數",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (hcPermitted) Color(0xFF1A1A1A) else grayed
            )
            Text(
                if (hcPermitted) "若不寫入，只會變更GPS，沒有步數"
                else "需先到設定頁授予步數讀寫權限",
                fontSize = 10.sp, color = if (hcPermitted) Color(0xFF999999) else grayed, lineHeight = 13.sp
            )
        }
        Switch(
            checked = enabled && hcPermitted,
            onCheckedChange = { AppSettings.setWriteStepsEnabled(context, it) },
            enabled = hcPermitted,
            colors = SwitchDefaults.colors(checkedTrackColor = ForestGreen)
        )
    }
}

private val STEP_LIMIT_PRESETS = listOf(1000L, 3000L, 5000L, 10000L, 0L)

@Composable
private fun StepLimitPickerRow(state: WalkState, viewModel: WalkViewModel) {
    var showLimitDialog by remember { mutableStateOf(false) }
    if (showLimitDialog) {
        CustomStepLimitDialog(
            currentLimit = state.stepLimit,
            onConfirm = { viewModel.setStepLimit(it); showLimitDialog = false },
            onDismiss = { showLimitDialog = false }
        )
    }
    val isCustom = STEP_LIMIT_PRESETS.none { it == state.stepLimit }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Split 5 presets + 自訂 across two rows (3 + 3) instead of squeezing all 6 into one —
        // one row of this many pills reads as a cramped wall of tiny text.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            STEP_LIMIT_PRESETS.take(3).forEach { limit ->
                ModePill(
                    label = if (limit == 0L) "無上限" else "$limit",
                    selected = state.stepLimit == limit,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setStepLimit(limit) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            STEP_LIMIT_PRESETS.drop(3).forEach { limit ->
                ModePill(
                    label = if (limit == 0L) "無上限" else "$limit",
                    selected = state.stepLimit == limit,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setStepLimit(limit) }
            }
            ModePill(
                label = if (isCustom) "自訂 ${state.stepLimit}" else "自訂",
                selected = isCustom,
                modifier = Modifier.weight(1f)
            ) { showLimitDialog = true }
        }
    }
}

@Composable
private fun PathTabContent(
    state: WalkState,
    viewModel: WalkViewModel,
    lastSavedName: String?
) {
    val waypoints = state.waypoints
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    if (lastSavedName != null) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                .background(ForestGreen.copy(0.1f)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("✓", fontSize = 13.sp, color = ForestGreen)
            Text("「$lastSavedName」已儲存", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ForestGreenDark)
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            shape = RoundedCornerShape(16.dp),
            icon = {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BookmarkAdd, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            title = { Text("儲存路線", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val subtitle = if (waypoints.size == 1) "單點定位"
                        else "${waypoints.size} 個路標點 · ${state.waypointLoopMode.label}"
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            subtitle, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("名稱") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen, cursorColor = ForestGreen),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveRoute(saveName); showSaveDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen, contentColor = Color.White)
                ) { Text("儲存", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
        )
    }

    if (waypoints.isEmpty()) {
        Text("點地圖任一處，設定模擬位置", fontSize = 12.sp, color = StoneGray)
        return
    }

    ReorderableWaypointList(
        waypoints = waypoints,
        onMove = { from, to -> viewModel.moveWaypoint(from, to) },
        onDelete = { viewModel.removeWaypoint(it) }
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("速度")
        SpeedPickerRow(state, viewModel)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("步數上限(步)")
        StepLimitPickerRow(state, viewModel)
    }

    WriteStepsToggleRow(state, liveHint = false)

    if (waypoints.size >= 2) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("走路模式")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                WaypointLoopMode.entries.forEach { mode ->
                    ModePill(
                        label = mode.label,
                        selected = state.waypointLoopMode == mode,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.setWaypointLoopMode(mode) }
                }
            }
        }
        if (state.isRoutePaused) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.resumeWalkingRoute() },
                    enabled = state.isSimulating,
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen, contentColor = Color.White),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("繼續走這條", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { viewModel.startWalkingRoute() },
                    enabled = state.isSimulating,
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFDDDDDD)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreenDark)
                ) {
                    Icon(Icons.Default.Replay, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新走這條", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Button(
                onClick = { viewModel.startWalkingRoute() },
                enabled = state.isSimulating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen, contentColor = Color.White,
                    disabledContainerColor = Color(0xFFEEEEEE), disabledContentColor = Color(0xFFAAAAAA)
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("走這條路徑", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!state.isSimulating) {
            Text("請先按地圖下方「開始偽造GPS」", fontSize = 10.sp, color = Color(0xFFAAAAAA))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                saveName = if (waypoints.size == 1) "單點定位" else "${waypoints.size} 點路線"
                showSaveDialog = true
            },
            modifier = Modifier.weight(1f).heightIn(min = 34.dp),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(0.5.dp, Color(0xFFDDDDDD)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreenDark),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Save, null, Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("儲存", fontSize = 11.sp)
        }
        OutlinedButton(
            onClick = { viewModel.clearPath() },
            modifier = Modifier.weight(1f).heightIn(min = 34.dp),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(0.5.dp, Color(0xFFF5B5B5)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.DeleteOutline, null, Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("清除路徑", fontSize = 11.sp)
        }
    }
}

@Composable
private fun CustomSpeedDialog(currentKmh: Double, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (WalkSpeed.entries.any { it.kmh == currentKmh }) "" else "%.1f".format(currentKmh)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自訂速度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("km/h") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { text.toDoubleOrNull()?.let { if (it > 0) onConfirm(it) } },
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
            ) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CustomStepLimitDialog(currentLimit: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (STEP_LIMIT_PRESETS.contains(currentLimit)) "" else currentLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自訂步數上限", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("輸入 0 代表無上限", fontSize = 12.sp, color = StoneGray)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    label = { Text("步") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { text.toLongOrNull()?.let { onConfirm(it) } },
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
            ) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Opened from the 已存路線 button in [PathTabContent] rather than living as a permanent second
 *  panel — the route list is something you dip into to pick or manage a route, not something
 *  that needs to stay visible alongside the path you're actively editing. */
@Composable
private fun SavedRoutesDialog(
    savedRoutes: List<SavedRoute>,
    isSimulating: Boolean,
    onFlyTo: (Double, Double) -> Unit,
    onLoad: (SavedRoute) -> Unit,
    onLoadAndWalk: (SavedRoute) -> Unit,
    onShare: (SavedRoute) -> Unit,
    onImportRoute: (SavedRoute) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf(false) }
    var showImportSection by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已存路線", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Collapsed by default — importing a shared route is occasional, not something
                // that should compete for space with the route list every time this dialog opens.
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showImportSection = !showImportSection },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "收到朋友分享的路線？", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showImportSection) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        null, Modifier.size(18.dp)
                    )
                }
                if (showImportSection) {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = false },
                        placeholder = { Text("在這裡貼上朋友傳給你的整段訊息", fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen, cursorColor = ForestGreen),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pasteError) {
                        Text("看起來不是有效的路線代碼", fontSize = 11.sp, color = EarthRed)
                    }
                    Button(
                        onClick = {
                            val route = RouteShareCodec.decode(pasteText)
                            if (route != null) {
                                onImportRoute(route)
                                pasteText = ""
                                showImportSection = false
                            } else {
                                pasteError = true
                            }
                        },
                        enabled = pasteText.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreen, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    ) { Text("匯入路線", fontWeight = FontWeight.SemiBold) }
                }

                if (savedRoutes.isEmpty()) {
                    Text("尚無已存的位置或路線", fontSize = 12.sp, color = StoneGray)
                } else {
                    savedRoutes.forEach { route ->
                        SavedRouteCard(
                            route = route,
                            isSimulating = isSimulating,
                            onLoad = {
                                onLoad(route)
                                route.waypoints.firstOrNull()?.let { onFlyTo(it.lat, it.lng) }
                            },
                            onLoadAndWalk = {
                                onLoadAndWalk(route)
                                route.waypoints.firstOrNull()?.let { onFlyTo(it.lat, it.lng) }
                            },
                            onShare = { onShare(route) },
                            onDelete = { onDelete(route.id) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } }
    )
}

// ── Shared small composables ──────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ForestGreenDark, letterSpacing = 0.5.sp)
}

@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ForestGreen.copy(0.12f) else Color(0xFFEEEEEE),
        onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) ForestGreen else Color(0xFF888888)
            )
        }
    }
}

@Composable
private fun ReorderableWaypointList(
    waypoints: List<GeoPoint>,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (Int) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 30.dp.toPx() }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())
    ) {
        waypoints.forEachIndexed { i, pt ->
            val isDragging = draggedIndex == i
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(if (isDragging) Color(0xFFF2F2F2) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DragHandle, null,
                    tint = StoneGray.copy(0.5f),
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp)
                        .pointerInput(waypoints.size) {
                            detectDragGestures(
                                onDragStart = { draggedIndex = i; dragOffset = 0f },
                                onDragEnd = { draggedIndex = null; dragOffset = 0f },
                                onDragCancel = { draggedIndex = null; dragOffset = 0f },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta.y
                                    val current = draggedIndex ?: return@detectDragGestures
                                    val target = (current + (dragOffset / rowHeightPx).roundToInt())
                                        .coerceIn(0, waypoints.size - 1)
                                    if (target != current) {
                                        dragOffset -= (target - current) * rowHeightPx
                                        onMove(current, target)
                                        draggedIndex = target
                                    }
                                }
                            )
                        }
                )
                Box(modifier = Modifier.weight(1f)) {
                    WaypointRow(i, pt, true) { onDelete(i) }
                }
            }
            if (i < waypoints.size - 1 && !isDragging) {
                HorizontalDivider(modifier = Modifier.padding(start = 28.dp), color = Color(0xFFF0F0F0))
            }
        }
    }
}

@Composable
private fun WaypointRow(index: Int, pt: GeoPoint, canDelete: Boolean, onDelete: () -> Unit) {
    val accent = Color(0xFFE05A2B)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Text(
                "${index + 1}",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                lineHeight = 12.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "%.6f, %.6f".format(pt.lat, pt.lng),
            fontSize = 11.sp, color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = StoneGray.copy(0.6f))
            }
        }
    }
}

@Composable
private fun SavedRouteCard(
    route: SavedRoute,
    isSimulating: Boolean,
    onLoad: () -> Unit,
    onLoadAndWalk: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("刪除？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("「${route.name}」將被永久刪除。", fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = { onDelete(); showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = EarthRed)) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        color = Color.White,
        border = BorderStroke(0.5.dp, Color(0xFFE0E0E0))
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(route.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(timeSince(route.savedAt), fontSize = 9.sp, color = Color(0xFFBBBBBB))
            }
            val subtitle = if (route.waypoints.size == 1) "單點定位"
                else "${route.waypoints.size} 個路標 · ${route.loopMode.label} · ${estimateDistanceKm(route.waypoints)}"
            Text(subtitle, fontSize = 9.sp, color = StoneGray)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(
                    onClick = onLoadAndWalk,
                    modifier = Modifier.weight(1f).heightIn(min = 26.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen, contentColor = Color.White),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    val label = when {
                        !isSimulating -> "載入路徑"
                        route.waypoints.size >= 2 -> "▶ 走這條"
                        else -> "▶ 去這裡"
                    }
                    Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                // Only shown once GPS faking is on — before that, loadRouteAndWalk's "and walk"
                // half silently no-ops (see WalkViewModel.startWalkingRoute/holdAt), so this
                // button would be functionally identical to the primary one above and just
                // confusing to have twice.
                if (isSimulating) {
                    OutlinedButton(
                        onClick = onLoad,
                        modifier = Modifier.heightIn(min = 26.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, Color(0xFFDDDDDD)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF777777)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("載入", fontSize = 9.sp, maxLines = 1) }
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.heightIn(min = 26.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFDDDDDD)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF777777)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Icon(Icons.Default.Share, "分享", Modifier.size(12.dp)) }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.heightIn(min = 26.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFF5B5B5)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Icon(Icons.Default.DeleteOutline, "刪除", Modifier.size(13.dp)) }
            }
        }
    }
}

private fun estimateDistanceKm(waypoints: List<GeoPoint>): String {
    if (waypoints.size < 2) return "0.0 km"
    var total = 0.0
    for (i in 0 until waypoints.size - 1) total += haversineKm(waypoints[i], waypoints[i + 1])
    return if (total < 1.0) "${(total * 1000).toInt()} m" else "%.1f km".format(total)
}

private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val R = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * R * asin(sqrt(h))
}

private fun timeSince(savedAt: Long): String {
    val diff = System.currentTimeMillis() - savedAt
    val mins = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        mins < 1 -> "剛剛"
        hours < 1 -> "${mins} 分鐘前"
        days < 1 -> "${hours} 小時前"
        days < 30 -> "${days} 天前"
        else -> "${days / 30} 個月前"
    }
}

// ── GeoJSON + bitmap helpers ────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
private fun getLastKnownLocation(context: android.content.Context): android.location.Location? {
    return try {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    } catch (_: Exception) { null }
}

/** getLastKnownLocation() only returns a cached fix, which is often simply absent on a phone
 *  that hasn't used real GPS in a while — falls back to requesting one live update (with a
 *  timeout, since a fix can take a few seconds or never arrive indoors). */
@SuppressLint("MissingPermission")
private suspend fun getFreshLocation(context: android.content.Context): android.location.Location? {
    getLastKnownLocation(context)?.let { return it }
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        ?: return null
    val provider = when {
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    return kotlinx.coroutines.withTimeoutOrNull(8_000L) {
        kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resumeWith(Result.success(location))
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            } catch (_: Exception) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
    }
}

/** Direction arrow. The marker is center-anchored (see the Marker call site above), rotating
 *  around the exact point the avatar sits at — the bitmap itself is built symmetric around its
 *  vertical midpoint (arrow drawn in the top half, bottom half left blank) so the visible
 *  arrowhead orbits just outside the 52dp avatar ring instead of sitting underneath it. */
private fun bearingIndicatorBitmap(context: android.content.Context): Bitmap {
    val dp          = context.resources.displayMetrics.density
    val avatarR     = 26 * dp   // half of the 52dp avatar badge
    val arrowLength = 20 * dp
    val arrowWidth  = 22 * dp
    val gap         = 0.5f * dp // small breathing room between ring edge and arrowhead

    val halfH = avatarR + gap + arrowLength
    val w = arrowWidth.toInt()
    val h = (halfH * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv  = Canvas(bmp)
    val p   = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx  = w / 2f

    // Classic "navigation arrow" kite shape — a slim dart with a concave notch at the back,
    // drawn entirely within the top [0, arrowLength] strip, pointing toward y=0.
    val tip    = 0f
    val baseY  = arrowLength
    val notchY = baseY - arrowLength * 0.32f
    val halfW  = cx * 0.85f
    val path = Path()
    path.moveTo(cx, tip)
    path.lineTo(cx + halfW, baseY)
    path.lineTo(cx, notchY)
    path.lineTo(cx - halfW, baseY)
    path.close()

    p.style = Paint.Style.FILL
    p.color = android.graphics.Color.WHITE
    cv.drawPath(path, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = dp * 1.2f
    p.color = android.graphics.Color.parseColor("#1B7C55")
    cv.drawPath(path, p)

    return bmp
}

private fun userLocationBitmap(context: android.content.Context): Bitmap {
    val dp   = context.resources.displayMetrics.density
    val size = (52 * dp).toInt()
    val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv   = Canvas(bmp)
    val p    = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx   = size / 2f
    val r    = cx - dp

    p.style = Paint.Style.FILL
    p.color = android.graphics.Color.parseColor("#1B7C55")
    cv.drawCircle(cx, cx, r, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = dp * 2f
    p.color = android.graphics.Color.WHITE
    cv.drawCircle(cx, cx, r - dp, p)

    // App-icon character in place of the old person silhouette, filling most of the badge.
    val charSource = BitmapFactory.decodeResource(context.resources, R.drawable.ic_avatar_character)
    val destDiameter = (size * 0.9f).toInt()
    val scaledChar = Bitmap.createScaledBitmap(charSource, destDiameter, destDiameter, true)
    val offset = (size - destDiameter) / 2f
    p.style = Paint.Style.FILL
    cv.drawBitmap(scaledChar, offset, offset, p)

    return bmp
}

private fun createPinBitmap(number: Int): Bitmap {
    val dp = 3
    val w = 36 * dp
    val h = 52 * dp
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    val cx = w / 2f
    val r  = w / 2f - dp
    val cy = r + dp.toFloat()

    p.color = android.graphics.Color.argb(55, 0, 0, 0)
    p.style = Paint.Style.FILL
    cv.drawCircle(cx + dp * 0.8f, cy + dp * 0.8f, r, p)

    p.color = android.graphics.Color.parseColor("#E05A2B")
    cv.drawCircle(cx, cy, r, p)

    val tail = Path()
    tail.moveTo(cx - r * 0.38f, cy + r * 0.70f)
    tail.lineTo(cx, h.toFloat() - dp * 0.5f)
    tail.lineTo(cx + r * 0.38f, cy + r * 0.70f)
    tail.close()
    cv.drawPath(tail, p)

    p.color = android.graphics.Color.WHITE
    cv.drawCircle(cx, cy, r * 0.58f, p)

    p.color = android.graphics.Color.parseColor("#E05A2B")
    p.textAlign = Paint.Align.CENTER
    p.typeface  = Typeface.DEFAULT_BOLD
    p.textSize  = r * (if (number > 9) 0.68f else 0.84f)
    val fm   = p.fontMetrics
    val txtY = cy - (fm.ascent + fm.descent) / 2f
    cv.drawText(number.toString(), cx, txtY, p)

    return bmp
}

/** Same teardrop shape as the numbered waypoint pins but in blue with no number — visually
 *  distinct so a search result never reads as an actual waypoint. */
private fun createSearchPinBitmap(): Bitmap {
    val dp = 3
    val w = 36 * dp
    val h = 52 * dp
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    val cx = w / 2f
    val r  = w / 2f - dp
    val cy = r + dp.toFloat()

    p.color = android.graphics.Color.argb(55, 0, 0, 0)
    p.style = Paint.Style.FILL
    cv.drawCircle(cx + dp * 0.8f, cy + dp * 0.8f, r, p)

    p.color = android.graphics.Color.parseColor("#1E88E5")
    cv.drawCircle(cx, cy, r, p)

    val tail = Path()
    tail.moveTo(cx - r * 0.38f, cy + r * 0.70f)
    tail.lineTo(cx, h.toFloat() - dp * 0.5f)
    tail.lineTo(cx + r * 0.38f, cy + r * 0.70f)
    tail.close()
    cv.drawPath(tail, p)

    p.color = android.graphics.Color.WHITE
    cv.drawCircle(cx, cy, r * 0.34f, p)

    return bmp
}
