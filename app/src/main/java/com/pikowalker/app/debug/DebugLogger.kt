package com.pikowalker.app.debug

import android.content.Context
import android.os.Build
import com.pikowalker.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/** An app-maintained rolling log, separate from Logcat — a sideloaded app can't read its own
 *  Logcat output on modern Android, so this is the only way for a user to hand us a record of
 *  what actually happened around the moment they noticed a problem (e.g. 定位飄走). Only
 *  collects anything while [enabled], and only ever keeps the most recent [RETENTION_MS]. */
object DebugLogger {
    private const val PREFS = "pikowalker_debug"
    private const val KEY_ENABLED = "debug_enabled"
    private const val RETENTION_MS = 10 * 60 * 1000L

    @Volatile
    var enabled: Boolean = false
        private set

    private val entries = ConcurrentLinkedDeque<Pair<Long, String>>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.TAIWAN)

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) log("Debug", "除錯模式已開啟")
    }

    fun log(tag: String, message: String) {
        android.util.Log.d("Piko-$tag", message)
        if (!enabled) return
        val now = System.currentTimeMillis()
        entries.addLast(now to "[${timeFormat.format(Date(now))}] $tag: $message")
        pruneOlderThan(now)
    }

    private fun pruneOlderThan(now: Long) {
        while (true) {
            val head = entries.peekFirst() ?: break
            if (now - head.first > RETENTION_MS) entries.pollFirst() else break
        }
    }

    private fun snapshotText(minutes: Int): String {
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        val lines = entries.filter { it.first >= cutoff }.map { it.second }
        return if (lines.isEmpty())
            "（沒有紀錄 —— 可能除錯模式剛開啟，或這段時間內沒有偽造GPS活動）"
        else lines.joinToString("\n")
    }

    /** Writes a snapshot of the last [minutes] to a shareable file in cache storage and returns
     *  it, ready to hand to a share Intent via FileProvider. */
    fun exportToFile(context: Context, minutes: Int = 5): File {
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
        return file
    }
}
