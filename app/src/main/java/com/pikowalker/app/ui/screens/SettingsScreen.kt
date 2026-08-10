package com.pikowalker.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pikowalker.app.BuildConfig
import com.pikowalker.app.model.SavedRoute
import com.pikowalker.app.model.ScheduleConfig
import com.pikowalker.app.schedule.ScheduleManager
import com.pikowalker.app.settings.AppSettings
import com.pikowalker.app.ui.theme.*
import com.pikowalker.app.update.ApkInstaller
import com.pikowalker.app.update.UpdateCheckResult
import com.pikowalker.app.update.UpdateChecker
import com.pikowalker.app.update.ApkDownloader
import com.pikowalker.app.update.UpdateUiState
import com.pikowalker.app.viewmodel.WalkViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: WalkViewModel, onRequestHcPermission: () -> Unit) {
    val state by viewModel.walkState.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    val scheduleConfig by viewModel.scheduleConfig.collectAsState()
    val todaySteps by viewModel.todaySteps.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshTodaySteps() }
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Re-checked on every resume (not just first composition) — these can change out from
    // under the app via system Settings while the user is on this screen.
    var batteryExempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    var isMockLocationApp by remember { mutableStateOf(checkIsMockLocationApp(context)) }
    var hasLocation by remember { mutableStateOf(checkLocationPermission(context)) }
    var hasNotification by remember { mutableStateOf(checkNotificationPermission(context)) }
    var canScheduleExact by remember { mutableStateOf(ScheduleManager.canScheduleExact(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
                isMockLocationApp = checkIsMockLocationApp(context)
                hasLocation = checkLocationPermission(context)
                hasNotification = checkNotificationPermission(context)
                canScheduleExact = ScheduleManager.canScheduleExact(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F6F2))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 頁面標題 ──────────────────────────────────────────────────────────
        Text(
            "設定與授權",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ForestGreenDark
        )

        // ── 基本權限 ──────────────────────────────────────────────────────────
        SettingSection(title = "基本權限") {
            PermissionRow(
                icon = Icons.Default.LocationOn,
                iconTint = Color(0xFF2E7D32),
                label = "位置權限",
                description = "模擬 GPS 位置所需",
                granted = hasLocation,
                onAction = {
                    launchIntent(context, Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ))
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Default.Notifications,
                iconTint = Color(0xFF1565C0),
                label = "通知權限",
                description = "背景執行常駐通知",
                granted = hasNotification,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        launchIntent(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    } else {
                        launchIntent(context, Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }
                }
            )
        }

        // ── 模擬位置 ──────────────────────────────────────────────────────────
        SettingSection(title = "模擬位置（開發人員選項）") {
            PermissionRow(
                icon = Icons.Default.DeveloperMode,
                iconTint = Color(0xFF6A1B9A),
                label = "虛擬位置應用程式",
                description = "需選取 PikoWalker 作為模擬來源",
                granted = isMockLocationApp,
                actionLabel = "前往設定",
                onAction = if (!isMockLocationApp) {
                    {
                        try {
                            launchIntent(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            launchIntent(context, Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                } else null
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF3E0))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.WarningAmber, null,
                    modifier = Modifier.size(15.dp).padding(top = 1.dp),
                    tint = Color(0xFFF57C00))
                Text(
                    "開發人員選項 → 選取模擬位置應用程式 → PikoWalker\n未設定時，啟動模擬後會立即停止",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF6D4C00)
                )
            }
        }

        // ── Health Connect ────────────────────────────────────────────────────
        SettingSection(title = "Health Connect（步數同步）") {
            PermissionRow(
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFD32F2F),
                label = "Health Connect 可用",
                description = "步數透過 HC 同步至 Google Fit",
                granted = state.healthConnectAvailable,
                onAction = null
            )
            RowDivider()
            PermissionRow(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                iconTint = ForestGreen,
                label = "步數讀寫權限",
                description = "允許 PikoWalker 寫入步數至 HC",
                granted = state.hasHealthPermission,
                actionLabel = if (state.hasHealthPermission) "重新授權" else "授予權限",
                onAction = if (state.healthConnectAvailable) onRequestHcPermission else null
            )
            RowDivider()
            WriteStepsToggleRow(hcPermitted = state.hasHealthPermission)
        }

        // ── 手動校正今日步數 ──────────────────────────────────────────────────
        SettingSection(title = "手動校正今日步數") {
            StepCorrectionContent(
                todaySteps = todaySteps,
                onApply = { viewModel.applyTodayStepsTarget(it) }
            )
        }

        // ── 排程自動開始 ──────────────────────────────────────────────────────
        SettingSection(title = "排程自動開始") {
            ScheduleContent(
                config = scheduleConfig,
                savedRoutes = savedRoutes,
                canScheduleExact = canScheduleExact,
                onUpdate = viewModel::updateSchedule,
                onRequestExactAlarmPermission = {
                    launchIntent(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    })
                }
            )
        }

        // ── 電池最佳化 ────────────────────────────────────────────────────────
        SettingSection(title = "電池最佳化") {
            PermissionRow(
                icon = Icons.Default.BatteryChargingFull,
                iconTint = Color(0xFF00838F),
                label = "電池最佳化",
                description = if (batteryExempt)
                    "背景偽造GPS不會被系統強制暫停"
                else
                    "背景可能被系統暫停，導致定位飄回真實位置",
                granted = batteryExempt,
                grantedLabel = "已停用",
                actionLabel = "去停用",
                urgent = true,
                onAction = if (!batteryExempt) {
                    {
                        try {
                            launchIntent(context, Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            ))
                        } catch (_: Exception) {
                            launchIntent(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                } else null
            )
        }

        // ── 除錯 ─────────────────────────────────────────────────────────────
        SettingSection(title = "除錯") {
            DebugSectionContent()
        }

        // ── 版本資訊 ──────────────────────────────────────────────────────────
        SettingSection(title = "版本資訊") {
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            UpdateCheckContent()
        }
    }
}

private fun checkLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

private fun checkNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    else true

private fun launchIntent(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun WriteStepsToggleRow(hcPermitted: Boolean) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.writeStepsEnabled) }
    val grayed = Color(0xFFBDBDBD)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "寫入步數", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (hcPermitted) Color(0xFF1A1A1A) else grayed
            )
            Text(
                if (hcPermitted) "關閉後只移動GPS位置，不會把步數寫入 Health Connect"
                else "需先授予上方「步數讀寫權限」才能使用",
                fontSize = 12.sp, color = if (hcPermitted) Color(0xFF757575) else grayed, lineHeight = 16.sp
            )
        }
        Switch(
            checked = enabled && hcPermitted,
            onCheckedChange = {
                enabled = it
                AppSettings.setWriteStepsEnabled(context, it)
            },
            enabled = hcPermitted,
            colors = SwitchDefaults.colors(checkedTrackColor = ForestGreen)
        )
    }
}

@Composable
private fun DebugSectionContent() {
    val context = LocalContext.current
    var debugEnabled by remember { mutableStateOf(com.pikowalker.app.debug.DebugLogger.enabled) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("除錯模式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
            Text(
                "記錄定位模擬相關的事件，方便回報問題時提供紀錄",
                fontSize = 12.sp, color = Color(0xFF757575), lineHeight = 16.sp
            )
        }
        Switch(
            checked = debugEnabled,
            onCheckedChange = {
                debugEnabled = it
                com.pikowalker.app.debug.DebugLogger.setEnabled(context, it)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = ForestGreen)
        )
    }

    OutlinedButton(
        onClick = {
            val file = com.pikowalker.app.debug.DebugLogger.exportToFile(context)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PikoWalker 除錯紀錄")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享除錯紀錄"))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
    ) {
        Text("匯出並分享除錯紀錄", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UpdateCheckContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun startDownload(info: UpdateCheckResult.Available) {
        scope.launch {
            uiState = UpdateUiState.Downloading(info, 0f)
            try {
                val file = ApkDownloader.download(context, info.assetApiUrl, info.assetName) { progress ->
                    uiState = UpdateUiState.Downloading(info, progress)
                }
                uiState = UpdateUiState.ReadyToInstall(file.absolutePath)
            } catch (e: Exception) {
                uiState = UpdateUiState.Error(e.message ?: "下載失敗")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (val state = uiState) {
            is UpdateUiState.Idle -> {
                OutlinedButton(
                    onClick = {
                        uiState = UpdateUiState.Checking
                        scope.launch {
                            uiState = when (val result = UpdateChecker.checkForUpdate()) {
                                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                                is UpdateCheckResult.Available -> UpdateUiState.Available(result)
                                is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                ) { Text("檢查更新") }
            }
            is UpdateUiState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("檢查中...", fontSize = 14.sp, color = Color(0xFF757575))
                }
            }
            is UpdateUiState.UpToDate -> {
                Text("已是最新版本", fontSize = 14.sp, color = Color(0xFF2E7D32), modifier = Modifier.padding(vertical = 8.dp))
            }
            is UpdateUiState.Available -> {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("發現新版本 v${state.info.version}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                    if (state.info.notes.isNotBlank()) {
                        Text(
                            state.info.notes, fontSize = 12.sp, color = Color(0xFF757575),
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { startDownload(state.info) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    ) { Text("下載並安裝") }
                }
            }
            is UpdateUiState.Downloading -> {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("下載中 ${(state.progress * 100).toInt()}%", fontSize = 14.sp, color = Color(0xFF757575))
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
            }
            is UpdateUiState.ReadyToInstall -> {
                Button(
                    onClick = {
                        if (ApkInstaller.canInstallUnknownApps(context)) {
                            ApkInstaller.install(context, java.io.File(state.apkPath))
                        } else {
                            launchIntent(context, ApkInstaller.requestInstallPermissionIntent(context))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                ) { Text("安裝新版本") }
            }
            is UpdateUiState.Error -> {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(state.message, fontSize = 13.sp, color = Color(0xFFC62828))
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { uiState = UpdateUiState.Idle },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)
                    ) { Text("重試") }
                }
            }
        }
    }
}

@Composable
private fun ScheduleContent(
    config: ScheduleConfig,
    savedRoutes: List<SavedRoute>,
    canScheduleExact: Boolean,
    onUpdate: (enabled: Boolean, hour: Int, minute: Int, routeId: String?) -> Unit,
    onRequestExactAlarmPermission: () -> Unit
) {
    val context = LocalContext.current

    if (savedRoutes.isEmpty()) {
        Text(
            "尚無已存路線，請先到地圖頁儲存一個位置或路線",
            fontSize = 12.sp, color = Color(0xFF757575),
            modifier = Modifier.padding(vertical = 12.dp)
        )
        return
    }

    val selectedRoute = savedRoutes.find { it.id == config.routeId } ?: savedRoutes.first()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("每天自動開始", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
            Text("時間到會自動開啟偽造GPS", fontSize = 12.sp, color = Color(0xFF757575))
        }
        Switch(
            checked = config.enabled,
            onCheckedChange = { onUpdate(it, config.hour, config.minute, config.routeId ?: selectedRoute.id) },
            colors = SwitchDefaults.colors(checkedTrackColor = ForestGreen)
        )
    }

    RowDivider()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TimePickerDialog(
                    context,
                    { _, h, m -> onUpdate(config.enabled, h, m, config.routeId ?: selectedRoute.id) },
                    config.hour, config.minute, true
                ).show()
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("時間", fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.weight(1f))
        Text(
            "%02d:%02d".format(config.hour, config.minute),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ForestGreen
        )
    }

    RowDivider()

    var dropdownExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { dropdownExpanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("路線", fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.weight(1f))
            Text(
                selectedRoute.name,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ForestGreen,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = StoneGray)
        }
        DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
            savedRoutes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(route.name) },
                    onClick = {
                        dropdownExpanded = false
                        onUpdate(config.enabled, config.hour, config.minute, route.id)
                    }
                )
            }
        }
    }

    if (!canScheduleExact) {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFFFF3E0))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.WarningAmber, null,
                modifier = Modifier.size(15.dp).padding(top = 1.dp),
                tint = Color(0xFFF57C00)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("尚未取得精確鬧鐘權限，排程時間可能會有誤差", fontSize = 12.sp, color = Color(0xFF6D4C00))
                TextButton(onClick = onRequestExactAlarmPermission, contentPadding = PaddingValues(0.dp)) {
                    Text("前往授權", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ForestGreen)
                }
            }
        }
    }
}

@Composable
private fun StepCorrectionContent(todaySteps: Long, onApply: (Long) -> Unit) {
    var input by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf(0L) }

    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text("目前總步數", fontSize = 12.sp, color = Color(0xFF757575))
        Text("%,d".format(todaySteps), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ForestGreenDark)
    }

    RowDivider()

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter(Char::isDigit) },
            label = { Text("設定為") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val target = input.toLongOrNull() ?: return@Button
                pendingTarget = target
                showConfirm = true
            },
            enabled = input.toLongOrNull() != null,
            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
            shape = RoundedCornerShape(8.dp)
        ) { Text("套用") }
    }

    Text(
        "只能新增，或刪除 PikoWalker 自己寫入的部分；其他 App 貢獻的步數無法刪除，調低時可能無法剛好命中目標值",
        fontSize = 11.sp, color = Color(0xFF999999), lineHeight = 15.sp
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("確認調整步數", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("把今日總步數改為 %,d 步？".format(pendingTarget), fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = { onApply(pendingTarget); showConfirm = false; input = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
                ) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        color = Color(0xFFEEEEEE),
        thickness = 0.8.dp
    )
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = ForestGreenDark,
            letterSpacing = 0.2.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    description: String,
    granted: Boolean?,
    actionLabel: String = "前往設定",
    grantedLabel: String = "已授權",
    urgent: Boolean = false,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = iconTint)
        }

        val isUrgentUnresolved = urgent && granted == false
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isUrgentUnresolved) Color(0xFFD32F2F) else Color(0xFF1A1A1A)
            )
            Text(
                description,
                fontSize = 12.sp,
                color = if (isUrgentUnresolved) Color(0xFFD32F2F) else Color(0xFF757575),
                lineHeight = 16.sp
            )
        }

        when {
            granted == true && onAction != null -> {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusBadge(grantedLabel, ForestGreen, Color(0xFFE8F5E9))
                    TextButton(
                        onClick = onAction,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 22.dp)
                    ) {
                        Text(actionLabel, fontSize = 10.sp, color = Color(0xFF9E9E9E))
                    }
                }
            }
            granted == true -> StatusBadge(grantedLabel, ForestGreen, Color(0xFFE8F5E9))
            onAction != null && urgent -> {
                Button(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 34.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            onAction != null -> {
                FilledTonalButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ForestGreen.copy(alpha = 0.12f),
                        contentColor = ForestGreenDark
                    )
                ) {
                    Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            granted == false -> StatusBadge("未設定", EarthRed, Color(0xFFFBE9E7))
        }
    }
}

@Composable
private fun StatusBadge(text: String, textColor: Color, bgColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 11.sp, color = textColor, fontWeight = FontWeight.Bold)
    }
}

@SuppressLint("MissingPermission")
private fun checkIsMockLocationApp(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, false, false, false, false,
            true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE
        )
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        true
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        // Provider already exists = simulation is running = we are the mock app
        true
    }
}
