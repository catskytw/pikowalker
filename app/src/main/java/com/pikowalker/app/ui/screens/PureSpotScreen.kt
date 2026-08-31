package com.pikowalker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pikowalker.app.model.PureSpot
import com.pikowalker.app.ui.theme.ForestGreen
import com.pikowalker.app.ui.theme.ForestGreenDark
import com.pikowalker.app.ui.theme.StoneGray
import com.pikowalker.app.viewmodel.WalkViewModel

/** 純點 — a community-curated database of GPS spots known not to overlap another Pikmin Bloom
 *  player's claimed decor, so seeding a flower/mushroom there doesn't get wasted on a duplicate.
 *  Browsed by 縣市/行政區/種類 here, purely for finding a coordinate — 去此點 hands it off to the
 *  exact same "帶座標過來" pipeline as a shared Google Maps link or geo: link (see
 *  WalkViewModel.setDeepLinkPoint): it jumps to 地圖, flies the camera there, and shows a
 *  confirmable pin — never moves the live fake GPS on its own. */
@Composable
fun PureSpotScreen(viewModel: WalkViewModel, onNavigateToMap: () -> Unit) {
    val allSpots by viewModel.pureSpots.collectAsState()
    val loading by viewModel.pureSpotsLoading.collectAsState()
    val walkState by viewModel.walkState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadPureSpotsIfNeeded() }

    // Held in the ViewModel, not local remember{} state — switching bottom nav tabs disposes and
    // recreates this whole composition, which used to silently reset every search/filter back to
    // blank on every visit.
    val filters by viewModel.pureSpotFilters.collectAsState()
    val searchText = filters.searchText
    val selectedCity = filters.city
    val selectedDistrict = filters.district
    val selectedType = filters.type

    var activePicker by remember { mutableStateOf<PickerKind?>(null) }
    // Collapsed by default and after every search — the filter card and the result list are
    // both fighting for the same limited screen height, and only one is ever needed at once.
    var filtersExpanded by remember { mutableStateOf(false) }
    val hasActiveFilter = selectedCity != null || selectedDistrict != null || selectedType != null
    val focusManager = LocalFocusManager.current

    val cityOptions = remember(allSpots) { countedDistinct(allSpots) { it.city } }
    // Scoped to the chosen city — with no city picked yet there's nothing sensible to list here
    // (every district nationwide would be hundreds of entries), so it stays empty and disabled.
    val districtOptions = remember(allSpots, selectedCity) {
        val city = selectedCity ?: return@remember emptyList()
        countedDistinct(allSpots.filter { it.city == city }) { it.district }
    }
    val typeOptions = remember(allSpots) { countedDistinct(allSpots) { it.type } }
    // Every spot of a given 種類 carries the same emoji in this dataset — one lookup per type
    // name, so the picker can show "🚌 公車站" instead of a bare name nobody can match to the
    // icon they see on the map/cards.
    val typeIcons = remember(allSpots) { allSpots.associate { it.type to it.icon } }

    val filtered = remember(allSpots, searchText, selectedCity, selectedDistrict, selectedType) {
        allSpots.filter { spot ->
            (selectedCity == null || spot.city == selectedCity) &&
                (selectedDistrict == null || spot.district == selectedDistrict) &&
                (selectedType == null || spot.type == selectedType) &&
                (searchText.isBlank() || spot.name.contains(searchText, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F6F2))) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = StoneGray)
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchText.isEmpty()) {
                            Text("搜尋純點名稱", fontSize = 13.sp, color = Color(0xFFAAAAAA))
                        }
                        BasicTextField(
                            value = searchText,
                            onValueChange = { viewModel.setPureSpotSearchText(it) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1A1A1A)),
                            cursorBrush = SolidColor(ForestGreen),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                filtersExpanded = false
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchText.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, "清除搜尋",
                            modifier = Modifier.size(16.dp).clickable {
                                viewModel.setPureSpotSearchText("")
                                filtersExpanded = false
                                focusManager.clearFocus()
                            },
                            tint = StoneGray
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    VerticalDivider(modifier = Modifier.height(20.dp), color = Color(0xFFE0E0E0))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { filtersExpanded = !filtersExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Box {
                            Icon(
                                Icons.Default.Tune, "篩選條件",
                                tint = if (hasActiveFilter) ForestGreen else StoneGray
                            )
                            if (hasActiveFilter) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(ForestGreen)
                                )
                            }
                        }
                    }
                }
            }

            if (filtersExpanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        FilterRow("縣市", selectedCity ?: "全部縣市", enabled = true) {
                            activePicker = PickerKind.CITY
                        }
                        RowDivider()
                        FilterRow(
                            "行政區",
                            selectedDistrict ?: if (selectedCity == null) "請先選縣市" else "全部行政區",
                            enabled = selectedCity != null
                        ) { activePicker = PickerKind.DISTRICT }
                        RowDivider()
                        FilterRow("種類", selectedType ?: "全部種類", enabled = true) {
                            activePicker = PickerKind.TYPE
                        }
                    }
                }
            }

            Text(
                if (loading) "載入純點資料中…" else "共 ${filtered.size} 個純點",
                fontSize = 12.sp, color = StoneGray
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { spot ->
                PureSpotCard(spot, onGo = {
                    // Fake GPS already running: apply the point immediately (same as dragging
                    // the avatar) and just jump over to see it — nothing left to confirm.
                    // Otherwise fall back to the same confirm-first "帶座標過來" pipeline every
                    // other external coordinate (shared link, geo: link) already uses.
                    if (walkState.isSimulating) {
                        viewModel.repositionAvatar(spot.lat, spot.lon)
                        onNavigateToMap()
                    } else {
                        viewModel.setDeepLinkPoint(spot.lat, spot.lon)
                    }
                })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    when (activePicker) {
        PickerKind.CITY -> PickerDialog(
            title = "選擇縣市", options = cityOptions, allLabel = "全部縣市",
            onSelect = { viewModel.setPureSpotCity(it) }, onDismiss = { activePicker = null }
        )
        PickerKind.DISTRICT -> PickerDialog(
            title = "選擇行政區", options = districtOptions, allLabel = "全部行政區",
            onSelect = { viewModel.setPureSpotDistrict(it) }, onDismiss = { activePicker = null }
        )
        PickerKind.TYPE -> PickerDialog(
            title = "選擇種類", options = typeOptions, allLabel = "全部種類",
            iconFor = typeIcons,
            onSelect = { viewModel.setPureSpotType(it) }, onDismiss = { activePicker = null }
        )
        null -> {}
    }
}

private enum class PickerKind { CITY, DISTRICT, TYPE }

/** Counts occurrences of [key] across [spots] and returns the distinct values ranked by
 *  frequency — surfaces the values that actually have data first, pushing any stray/rare data
 *  entries (this dataset is community-submitted and not perfectly clean) toward the bottom
 *  instead of scattering them alphabetically among the values someone's actually looking for. */
private fun countedDistinct(spots: List<PureSpot>, key: (PureSpot) -> String): List<String> =
    spots.asSequence().map(key).filter { it.isNotBlank() }.toList()
        .groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.map { it.key }

@Composable
private fun FilterRow(label: String, valueText: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.weight(1f))
        Text(
            valueText,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) ForestGreen else StoneGray,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
        )
        Icon(Icons.Default.ArrowDropDown, null, tint = StoneGray)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 0.8.dp)
}

/** A plain AlertDialog rather than a dropdown menu — with up to 169 districts or 41 types in
 *  this dataset, a DropdownMenu simply runs off the bottom of the screen with no way to reach
 *  the tail of the list. This scrolls properly regardless of length, and adding a search field
 *  turns "scroll through 169 items" into "type 3 characters" for the long lists specifically. */
@Composable
private fun PickerDialog(
    title: String,
    options: List<String>,
    allLabel: String,
    iconFor: Map<String, String> = emptyMap(),
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(options, search) {
        if (search.isBlank()) options else options.filter { it.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Color(0xFF1A1A1A),
        textContentColor = Color(0xFF1A1A1A),
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (options.size > 8) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("搜尋", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = StoneGray) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ForestGreen,
                            cursorColor = ForestGreen,
                            unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedTextColor = Color(0xFF1A1A1A),
                            unfocusedTextColor = Color(0xFF1A1A1A),
                            focusedPlaceholderColor = StoneGray,
                            unfocusedPlaceholderColor = StoneGray
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (search.isBlank()) {
                        PickerRow(allLabel, highlighted = true) { onSelect(null); onDismiss() }
                        if (filtered.isNotEmpty()) RowDivider()
                    }
                    filtered.forEachIndexed { index, option ->
                        PickerRow(option, icon = iconFor[option]) { onSelect(option); onDismiss() }
                        if (index < filtered.lastIndex) RowDivider()
                    }
                    if (filtered.isEmpty() && search.isNotBlank()) {
                        Text(
                            "沒有符合的結果", fontSize = 13.sp, color = StoneGray,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = ForestGreen) }
        }
    )
}

@Composable
private fun PickerRow(
    label: String,
    icon: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Text(icon, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
        }
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlighted) ForestGreen else Color(0xFF1A1A1A)
        )
    }
}

@Composable
private fun PureSpotCard(spot: PureSpot, onGo: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ForestGreen.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(spot.icon.ifBlank { "📍" }, fontSize = 18.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        spot.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("${spot.city} · ${spot.district}", fontSize = 12.sp, color = StoneGray)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE8F5E9))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(spot.type, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ForestGreen)
                }
                Button(
                    onClick = onGo,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.PinDrop, null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("去此點", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}
