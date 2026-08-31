package com.pikowalker.app

import android.content.Context
import com.pikowalker.app.model.PureSpot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Result of a [PureSpotRepository.refreshFromNetwork] call — reports each source independently
 *  since one succeeding while the other fails is a normal, non-fatal outcome (both are someone
 *  else's site, either can be briefly down or change shape without warning). */
data class PureSpotRefreshResult(
    val totalCount: Int,
    val talllkaiCount: Int?,
    val talllkaiError: String?,
    val pikdecorCount: Int?,
    val pikdecorError: String?
)

/** Loads and caches 純點 — normally from a bundled CSV snapshot (assets/purespots.csv), optionally
 *  replaced by a fresher, merged copy the user pulls down themselves via [refreshFromNetwork]
 *  (設定 → 純點資料 → 更新). Parsed once per process and kept in memory — works fully offline
 *  either way; refreshing just swaps which file that one-time parse reads from. */
class PureSpotRepository(private val context: Context) {

    companion object {
        /** The talllkai 純點地圖 site embeds its whole dataset as gzip+base64 inside an
         *  `atob("...")` call on this page, meant for its own map to consume client-side — no
         *  official API; this is the same trick a community fetch script already used to produce
         *  the bundled asset, just run from the phone instead of ahead of time. */
        private const val TALLLKAI_URL = "https://pikmin.talllkai.com/PureSpot/Map"

        /** pikdecor.com's own site calls this to render its map — also no documented public API,
         *  just the same endpoint their frontend uses. */
        private const val PIKDECOR_URL = "https://pikdecor.com/api/pure-spots"

        private const val ASSET_FILENAME = "purespots.csv"
        private const val OVERRIDE_FILENAME = "purespots_override.csv"
        private const val PREFS_NAME = "pikowalker_purespots"
        private const val KEY_LAST_UPDATED_MS = "lastUpdatedMs"

        /** Once the user pulls a fresh copy, the button stays disabled for this long — both
         *  sources are community-maintained sites someone else pays to run, not PikoWalker's own
         *  infrastructure, and this dataset doesn't meaningfully change day to day anyway. */
        const val UPDATE_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000L

        /** pikdecor.com is a global database (its `region` field includes plenty of non-Taiwan
         *  entries) — this app's filters are Taiwan-only, so anything whose region string doesn't
         *  start with one of these gets dropped rather than polluting 縣市 with foreign cities.
         *  臺北/臺中/臺南/臺東 are matched after normalizing 台→臺 (pikdecor mixes both spellings
         *  for the same cities; talllkai's own data always uses 臺), so only one canonical form
         *  ever reaches the 縣市 filter. */
        private val TAIWAN_CITIES = listOf(
            "臺北市", "新北市", "桃園市", "臺中市", "臺南市", "高雄市",
            "基隆市", "新竹市", "新竹縣", "苗栗縣", "彰化縣", "南投縣",
            "雲林縣", "嘉義市", "嘉義縣", "屏東縣", "宜蘭縣", "花蓮縣",
            "臺東縣", "澎湖縣", "金門縣", "連江縣"
        )

        /** Every Taiwan township-level division name ends in exactly one of these four
         *  characters — used to truncate pikdecor's messier `region` values (see
         *  [splitTaiwanRegion]) at the actual end of the district name. */
        private val DISTRICT_SUFFIXES = charArrayOf('區', '市', '鎮', '鄉')

        /** pikdecor's `category` is an English slug with no city/district split and no emoji —
         *  mapped here onto the exact same Chinese labels + emoji talllkai's data already uses,
         *  so merging the two sources doesn't leave 種類 with two entries for the same concept
         *  (e.g. "cafe" showing up separately from talllkai's "咖啡杯"). */
        private val PIKDECOR_CATEGORY_MAP = mapOf(
            "minimart" to ("便利店" to "🏪"),
            "library" to ("圖書館" to "📚"),
            "airport" to ("機場" to "✈️"),
            "curry" to ("咖哩" to "🍛"),
            "bus" to ("公車站" to "🚌"),
            "italian" to ("義式餐廳" to "🍝"),
            "restaurant" to ("餐廳" to "🍴"),
            "park" to ("公園" to "🌳"),
            "themePark" to ("主題樂園" to "🎡"),
            "station" to ("車站" to "🚉"),
            "stadium" to ("體育館" to "🏋️"),
            "electronics" to ("電器行" to "🔌"),
            "waterside" to ("水邊" to "🌊"),
            "hotel" to ("飯店" to "🏨"),
            "university" to ("大學&學院" to "🎓"),
            "stationery" to ("文具店" to "✏️"),
            "sweetshop" to ("甜點店" to "🍰"),
            "roadside" to ("路邊" to "🛣️"),
            "bridge" to ("橋樑" to "🌉"),
            "diy" to ("五金行" to "🔧"),
            "bakery" to ("麵包店" to "🥐"),
            "hairsalon" to ("美容院" to "💅"),
            "postoffice" to ("郵局" to "📮"),
            "cafe" to ("咖啡杯" to "☕"),
            "ramen" to ("拉麵店" to "🍜"),
            "korean" to ("韓國餐廳" to "🥘"),
            "supermarket" to ("超市" to "🛒"),
            "pharmacy" to ("藥局" to "💊"),
            "sushi" to ("壽司" to "🍣"),
            "clothesstore" to ("服裝店" to "👕"),
            "artgallery" to ("美術館" to "🖼️"),
            "forest" to ("森林" to "🌲"),
            "beach" to ("海灘" to "🏖️"),
            "movie" to ("電影院" to "🎬"),
            "mountain" to ("山丘" to "⛰️"),
            "makeup" to ("化妝品" to "💄"),
            "laundry" to ("自助洗衣店&乾洗店" to "🧺"),
            "shrine" to ("神社和寺廟" to "⛩️"),
            "zoo" to ("動物園" to "🦒"),
            "mexican" to ("墨西哥餐廳" to "🌮"),
            "hamburger" to ("漢堡" to "🍔")
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var cached: List<PureSpot>? = null

    fun lastUpdatedAtMs(): Long = prefs.getLong(KEY_LAST_UPDATED_MS, 0L)

    fun canUpdateNow(): Boolean = System.currentTimeMillis() - lastUpdatedAtMs() >= UPDATE_COOLDOWN_MS

    suspend fun loadAll(): List<PureSpot> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // Re-check inside the IO dispatch — two near-simultaneous first calls could both
            // have seen `cached == null` before either finished parsing.
            cached ?: parse(openSource()).also { cached = it }
        }
    }

    /** Fetches both sources and merges them — either one succeeding is enough to update (a
     *  single site being briefly down shouldn't block the other's fresh data), only failing
     *  outright if BOTH do. The bundled asset is never touched, so a bad fetch can't brick
     *  anything; the app just keeps using whatever it already had. No de-duplication between the
     *  two sources — they're independent community databases and may well list the same physical
     *  spot with slightly different coordinates, which isn't worth the complexity of matching by
     *  distance. Caller is responsible for [canUpdateNow] and for recording success against the
     *  cooldown (this only touches the timestamp when at least one source came back). */
    suspend fun refreshFromNetwork(): Result<PureSpotRefreshResult> = withContext(Dispatchers.IO) {
        val talllkai = runCatching { fetchFromTalllkai() }
        // talllkai already ships City/District as separate, clean fields — used as ground truth
        // to split pikdecor's single combined "region" field correctly. Needed because a handful
        // of real district names (平鎮區, 新市區, …) contain another district-level suffix
        // character (鎮/市) as part of the place name itself, before the actual terminal 區 —
        // a naive "cut at the first 區/市/鎮/鄉" rule truncates those wrong (see
        // splitTaiwanRegion's fallback for what happens without this reference data at all).
        val knownDistricts: Map<String, List<String>> = talllkai.getOrNull()
            ?.groupBy({ it.city }, { it.district })
            ?.mapValues { (_, ds) -> ds.distinct().sortedByDescending { it.length } }
            ?: emptyMap()
        val pikdecor = runCatching { fetchFromPikdecor(knownDistricts) }

        val merged = mutableListOf<PureSpot>()
        talllkai.getOrNull()?.let { merged += it }
        pikdecor.getOrNull()?.let { merged += it }

        if (merged.isEmpty()) {
            val error = talllkai.exceptionOrNull() ?: pikdecor.exceptionOrNull()
            return@withContext Result.failure(error ?: IllegalStateException("兩個來源都取得失敗"))
        }

        writeOverrideCsv(merged)
        cached = merged
        prefs.edit().putLong(KEY_LAST_UPDATED_MS, System.currentTimeMillis()).apply()
        Result.success(
            PureSpotRefreshResult(
                totalCount = merged.size,
                talllkaiCount = talllkai.getOrNull()?.size,
                talllkaiError = talllkai.exceptionOrNull()?.message,
                pikdecorCount = pikdecor.getOrNull()?.size,
                pikdecorError = pikdecor.exceptionOrNull()?.message
            )
        )
    }

    // ── talllkai ──────────────────────────────────────────────────────────

    private fun fetchFromTalllkai(): List<PureSpot> {
        val html = fetchText(TALLLKAI_URL)
        val json = extractGzippedJson(html)
            ?: error("找不到內嵌的純點資料，網站結構可能已變更")
        val arr = JSONArray(json)
        val spots = mutableListOf<PureSpot>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val city = o.optString("City")
            val district = o.optString("District")
            // A spot with no city/district is useless for this screen's filters.
            if (city.isBlank() || district.isBlank()) continue
            val lat = o.optDouble("Lat", Double.NaN)
            val lon = o.optDouble("Lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            spots.add(
                PureSpot(
                    id = o.optLong("Id", 0L),
                    name = o.optString("Name"),
                    lat = lat,
                    lon = lon,
                    type = o.optString("Type"),
                    icon = o.optString("Icon"),
                    city = city,
                    district = district
                )
            )
        }
        if (spots.isEmpty()) error("解析結果是空的")
        return spots
    }

    /** Mirrors the community fetch script's own unescaping — the page embeds this as a JS string
     *  literal, so `+`/`/` inside the base64 show up JS-escaped rather than literal. */
    private fun extractGzippedJson(html: String): String? {
        val match = Regex("atob\\(\"([^\"]+)\"\\)").find(html) ?: return null
        val b64 = match.groupValues[1]
            .replace("\\u002B", "+", ignoreCase = true)
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
        val gzipBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(gzipBytes)).bufferedReader(Charsets.UTF_8).readText()
    }

    // ── pikdecor ──────────────────────────────────────────────────────────

    private fun fetchFromPikdecor(knownDistricts: Map<String, List<String>>): List<PureSpot> {
        val json = fetchText(PIKDECOR_URL)
        val spotsArr = org.json.JSONObject(json).optJSONArray("spots")
            ?: error("回應格式不是預期的 {spots: [...]}")
        val spots = mutableListOf<PureSpot>()
        for (i in 0 until spotsArr.length()) {
            val o = spotsArr.getJSONObject(i)
            val region = o.optString("region")
            val (city, district) = splitTaiwanRegion(region, knownDistricts) ?: continue // foreign or unparseable
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            val (typeLabel, icon) = PIKDECOR_CATEGORY_MAP[o.optString("category")] ?: continue
            spots.add(
                PureSpot(
                    // Negated so these can never collide with talllkai's own (positive) Id space
                    // once merged into one list — only matters as a stable LazyColumn key here.
                    id = -o.optLong("id", 0L),
                    name = o.optString("name").ifBlank { o.optString("address") },
                    lat = lat,
                    lon = lng,
                    type = typeLabel,
                    icon = icon,
                    city = city,
                    district = district
                )
            )
        }
        if (spots.isEmpty()) error("解析結果是空的")
        return spots
    }

    /** Splits a combined "縣市+行政區" string like "台中市西屯區" into ("臺中市", "西屯區") —
     *  pikdecor has no separate city/district fields, and that one field is inconsistently messy:
     *  some entries repeat the city name twice ("台中市台中市北區"), some carry a stray leading
     *  space, others fold the village and full street address into the same field ("台中市東區
     *  東門里天乙街50號" — a real spot pikdecor.com actually has), and a rare few are outright
     *  garbage ("201臺灣基隆市", "台中市全區"). [knownDistricts] (talllkai's own already-separated
     *  data, keyed by the same city strings) is checked first and, when it has a match, is
     *  authoritative — this is what correctly handles district names that themselves contain
     *  another suffix character before the real terminal one (平鎮區 contains 鎮, 新市區 contains
     *  市; a naive "cut at the first 區/市/鎮/鄉" rule truncates those wrong). Only falls back to
     *  that naive cut when talllkai has no matching entry (no coverage of this city at all, or
     *  just this specific rural/sparse district) — capped at a plausible district-name length so
     *  that fallback can't produce something like "201臺灣基隆市".
     *
     *  Not fixed here: talllkai's own District field is itself inconsistent for the same real
     *  place (e.g. 屏東縣 has both "麟洛" and "麟洛鄉" from different contributors, and mixes in
     *  village-level 里/村 names alongside proper districts) — that's a pre-existing quality issue
     *  in the community data this repository treats as ground truth, not something introduced by
     *  merging in pikdecor, and would need a real Taiwan administrative-boundary reference to
     *  clean up properly rather than more string-matching heuristics. */
    private fun splitTaiwanRegion(
        region: String,
        knownDistricts: Map<String, List<String>>
    ): Pair<String, String>? {
        val normalized = region.replace('台', '臺').trim()
        val city = TAIWAN_CITIES.firstOrNull { normalized.startsWith(it) } ?: return null
        var rest = normalized.removePrefix(city).trim()
        // Strip a second, repeated copy of any city name some entries carry.
        TAIWAN_CITIES.firstOrNull { rest.startsWith(it) }?.let { rest = rest.removePrefix(it).trim() }
        if (rest.isBlank()) return null

        // Sorted longest-first by the caller, so e.g. "平鎮區" matches before any shorter
        // district name that might otherwise be a prefix of it.
        knownDistricts[city]?.firstOrNull { rest.startsWith(it) }?.let { return city to it }

        // No ground truth for this city, or talllkai just happens to have no coverage of this
        // specific (possibly rural/sparse) district — fall back to the naive cut, but reject
        // anything implausibly long as a district name (the longest real one is 4 characters,
        // e.g. 阿里山鄉) rather than keep obvious garbage like "201臺灣基隆市", and reject the
        // one known placeholder value pikdecor's data actually contains ("全區" — literally "all
        // districts", not a real one).
        val cutIndex = rest.indexOfFirst { it in DISTRICT_SUFFIXES }
        if (cutIndex < 0) return null
        val fallback = rest.substring(0, cutIndex + 1)
        if (fallback.length > 5 || fallback == "全區") return null
        return city to fallback
    }

    // ── shared ────────────────────────────────────────────────────────────

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            check(conn.responseCode == 200) { "伺服器回應異常（HTTP ${conn.responseCode}）" }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun writeOverrideCsv(spots: List<PureSpot>) {
        val sb = StringBuilder("﻿")
        sb.append("Id,Name,Lat,Lon,Type,Icon,City,District\n")
        spots.forEach { s ->
            sb.append(csvEscape(s.id.toString())).append(',')
                .append(csvEscape(s.name)).append(',')
                .append(csvEscape(s.lat.toString())).append(',')
                .append(csvEscape(s.lon.toString())).append(',')
                .append(csvEscape(s.type)).append(',')
                .append(csvEscape(s.icon)).append(',')
                .append(csvEscape(s.city)).append(',')
                .append(csvEscape(s.district)).append('\n')
        }
        File(context.filesDir, OVERRIDE_FILENAME).writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value

    /** The user's own last successful fetch always wins over the version shipped in the APK. */
    private fun openSource(): InputStream {
        val override = File(context.filesDir, OVERRIDE_FILENAME)
        return if (override.exists()) override.inputStream() else context.assets.open(ASSET_FILENAME)
    }

    private fun parse(stream: InputStream): List<PureSpot> {
        val spots = mutableListOf<PureSpot>()
        stream.use {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return@useLines
                iterator.next() // header row (Id,Name,Lat,Lon,Type,Icon,City,District)
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    if (line.isBlank()) continue
                    val f = splitCsvLine(line)
                    if (f.size < 8) continue
                    val lat = f[2].toDoubleOrNull() ?: continue
                    val lon = f[3].toDoubleOrNull() ?: continue
                    spots.add(
                        PureSpot(
                            id = f[0].toLongOrNull() ?: 0L,
                            name = f[1],
                            lat = lat,
                            lon = lon,
                            type = f[4],
                            icon = f[5],
                            city = f[6],
                            district = f[7]
                        )
                    )
                }
            }
        }
        return spots
    }

    /** Minimal CSV split honoring double-quoted fields — the only escaping the source data ever
     *  uses (a field gets quoted only if it contains a comma/quote/newline, doubled quotes
     *  inside). Good enough for this fixed, known-shape file without pulling in a CSV library. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
