package com.pikowalker.app

import android.content.Context
import com.pikowalker.app.model.PureSpot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Loads and caches 純點 from a bundled CSV snapshot (assets/purespots.csv, trimmed from a
 *  public community database — see scripts this repo doesn't track for the original fetch).
 *  This dataset only ever needs refreshing by shipping a new CSV with an app update, so there's
 *  no live sync — parsed once per process and kept in memory, works fully offline. */
class PureSpotRepository(private val context: Context) {

    @Volatile private var cached: List<PureSpot>? = null

    suspend fun loadAll(): List<PureSpot> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // Re-check inside the IO dispatch — two near-simultaneous first calls could both
            // have seen `cached == null` before either finished parsing.
            cached ?: parse().also { cached = it }
        }
    }

    private fun parse(): List<PureSpot> {
        val spots = mutableListOf<PureSpot>()
        context.assets.open("purespots.csv").use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
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
