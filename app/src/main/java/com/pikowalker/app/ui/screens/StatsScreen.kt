package com.pikowalker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pikowalker.app.model.WalkState
import com.pikowalker.app.ui.theme.*
import com.pikowalker.app.viewmodel.WalkViewModel

@Composable
fun StatsScreen(viewModel: WalkViewModel) {
    val state by viewModel.walkState.collectAsState()
    val todaySteps by viewModel.todaySteps.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshTodaySteps() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeadowLight, Color(0xFFF5FBF5), Color.White)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SessionStatusCard(state)

        SectionTitle("本次走路數據")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.DirectionsWalk,
                label = "步數",
                value = "%,d".format(state.steps),
                unit = "步",
                color = ForestGreen
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Straighten,
                label = "距離",
                value = state.distanceKm,
                unit = "公里",
                color = ForestGreenLight
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Timer,
                label = "執行時間",
                value = state.elapsedTime,
                unit = "",
                color = PollenYellow
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed,
                label = "步行速度",
                value = "%.1f".format(state.speedKmh),
                unit = "km/h",
                color = EarthRed
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("今日累計（Health Connect · 所有來源）")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshTodaySteps() }, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Default.Refresh, "重新整理", tint = StoneGray, modifier = Modifier.size(16.dp))
            }
        }
        MetricCard(
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.Today,
            label = "今日總步數",
            value = "%,d".format(todaySteps),
            unit = "步",
            color = Color(0xFF1E88E5)
        )

        SectionTitle("使用說明")
        InfoCard()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = StoneGray,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun SessionStatusCard(state: WalkState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (state.isSimulating) ForestGreen else Color.White
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.isSimulating) Color.White.copy(alpha = 0.2f) else MeadowLight
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(if (state.isWalkingRoute) "🚶" else if (state.isSimulating) "📍" else "💤", fontSize = 22.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        state.isWalkingRoute -> "模擬走路中"
                        state.isSimulating -> "靜止於路標"
                        else -> "待機中"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (state.isSimulating) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (state.isWalkingRoute)
                        "${state.waypointLoopMode.label} · %.1f km/h".format(state.speedKmh)
                    else if (state.isSimulating)
                        "點地圖或開關可停止模擬"
                    else
                        "在地圖上點一下即可開始模擬",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isSimulating) Color.White.copy(alpha = 0.75f) else StoneGray
                )
            }
            if (state.isSimulating) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF90EE90))
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    value,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ForestGreenDark
                )
                if (unit.isNotEmpty()) {
                    Text(unit, fontSize = 12.sp, color = StoneGray, fontWeight = FontWeight.Medium)
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = StoneGray)
        }
    }
}

@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoRow("GPS 模擬", "使用 Android Mock Location API，讓遊戲以為你在走路")
            HorizontalDivider(color = StoneGray.copy(alpha = 0.1f))
            InfoRow("步數同步", "每 30 秒寫入一次 Health Connect，皮克敏 Bloom 可讀取")
            HorizontalDivider(color = StoneGray.copy(alpha = 0.1f))
            InfoRow("背景執行", "通知欄有常駐通知，可隨時從通知停止")
            HorizontalDivider(color = StoneGray.copy(alpha = 0.1f))
            InfoRow("電池優化", "建議關閉 PikoWalker 的電池最佳化，避免被系統終止")
        }
    }
}

@Composable
private fun InfoRow(title: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(ForestGreen)
                .offset(y = 5.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ForestGreen
            )
            Text(desc, style = MaterialTheme.typography.bodySmall, color = StoneGray)
        }
    }
}
