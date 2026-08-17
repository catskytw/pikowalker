package com.pikowalker.app.debug

import android.content.Context
import android.os.Build
import com.pikowalker.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** An app-maintained rolling log, separate from Logcat — a sideloaded app can't read its own
 *  Logcat output on modern Android, so this is the only way for a user to hand us a record of
 *  what actually happened around the moment they noticed a problem (e.g. 定位飄走).
 *
 *  Always collects (bounded to the most recent [RETENTION_MS], and hard-capped at
 *  [MAX_BYTES] regardless of age as a backstop against runaway logging) — an earlier version
 *  only buffered while a "除錯模式" switch was on, which meant the one time it actually mattered
 *  (an unexpected crash) there was nothing recorded, because nobody turns on a debug switch
 *  before they know something's about to go wrong. Nothing leaves the device unless the user
 *  explicitly exports/shares it, so always-on local buffering costs nothing privacy-wise. */
object DebugLogger {
    private const val RETENTION_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 1 * 1024 * 1024L
    private const val MAX_EXPORTS = 5

    private val entries = ConcurrentLinkedDeque<Pair<Long, String>>()
    private val totalBytes = AtomicLong(0)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.TAIWAN)

    fun log(tag: String, message: String) {
        android.util.Log.d("Piko-$tag", message)
        val now = System.currentTimeMillis()
        val line = "[${timeFormat.format(Date(now))}] $tag: $message"
        entries.addLast(now to line)
        totalBytes.addAndGet(line.toByteArray(Charsets.UTF_8).size.toLong())
        prune(now)
    }

    private fun prune(now: Long) {
        while (true) {
            val head = entries.peekFirst() ?: break
            val expired = now - head.first > RETENTION_MS
            val overBudget = totalBytes.get() > MAX_BYTES
            if (!expired && !overBudget) break
            entries.pollFirst()
            totalBytes.addAndGet(-head.second.toByteArray(Charsets.UTF_8).size.toLong())
        }
    }

    private fun snapshotText(minutes: Int): String {
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        val lines = entries.filter { it.first >= cutoff }.map { it.second }
        return if (lines.isEmpty())
            "（沒有紀錄 —— 這段時間內沒有偽造GPS相關活動）"
        else lines.joinToString("\n")
    }

    /** Writes a snapshot of the last [minutes] to a shareable file in cache storage and returns
     *  it, ready to hand to a share Intent via FileProvider. Older exports beyond
     *  [MAX_EXPORTS] are deleted so repeated use doesn't quietly pile up files in cache. */
    fun exportToFile(context: Context, minutes: Int = 10): File {
        val dir = File(context.cacheDir, "debug_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "pikowalker_debug_$stamp.txt")
        val header = buildString {
            appendLine("PikoWalker 除錯紀錄")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）")
            appendLine("涵蓋範圍：最近 $minutes 分鐘")
            appendLine("---")
        }
        file.writeText(header + snapshotText(minutes))
        runCatching {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_EXPORTS)?.forEach { it.delete() }
        }
        return file
    }

    /** Recent in-memory entries as plain text — used to attach whatever context exists to a
     *  crash report (see [com.pikowalker.app.debug.CrashLogger]). */
    fun recentEntriesText(minutes: Int = 10): String = snapshotText(minutes)
}
