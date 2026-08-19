package com.pikowalker.app.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.pikowalker.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures exceptions to a file outside the app's own process lifetime, so a failure is
 *  diagnosable even if the app never got far enough (or never survived long enough) to be told
 *  to log anything through the ordinary in-memory [DebugLogger]. Written to app-specific external
 *  storage (not cache/internal) so it survives an app reinstall and is reachable via `adb pull`
 *  without root.
 *
 *  Two kinds of report share this storage:
 *  - **Fatal** ("crash_" prefix, via [install]): a genuinely uncaught exception that took the
 *    whole app down.
 *  - **Caught** ("caught_" prefix, via [writeCaughtReport]): an exception a resilience boundary
 *    (e.g. [com.pikowalker.app.service.MockLocationService]'s per-tick try/catch) already
 *    recovered from, so the app kept running — but the failure is still worth keeping evidence
 *    of, since otherwise it only ever existed in the current session's live [DebugLogger] buffer
 *    and vanishes the moment the app restarts. */
object CrashLogger {
    private const val DIR_NAME = "crash_logs"
    private const val MAX_REPORTS = 5

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context, "crash", throwable, thread)
            } catch (_: Throwable) {
                // Never let crash reporting itself block the crash from proceeding normally.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** For an exception a resilience boundary already caught and recovered from — the app did
     *  NOT crash, but the failure is written to disk anyway so it isn't lost on next restart. */
    fun writeCaughtReport(context: Context, tag: String, throwable: Throwable) {
        runCatching { writeReport(context, "caught", throwable, thread = null, tag = tag) }
    }

    /** Call once at process startup. An ANR never runs any of PikoWalker's own code — the
     *  system freezes then kills the process from outside — so [install] and [writeCaughtReport]
     *  can never see one. [ActivityManager.getHistoricalProcessExitReasons] is the only way to
     *  learn about it after the fact, and only from API 30 onward; older devices just never get
     *  this report. */
    fun checkForPreviousAnr(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val anr = am.getHistoricalProcessExitReasons(null, 0, 5)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR } ?: return
            writeAnrReport(context, anr)
        }
    }

    private fun writeAnrReport(context: Context, info: ApplicationExitInfo) {
        val dir = crashDir(context) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(info.timestamp))
        val file = File(dir, "anr_$stamp.txt")
        if (file.exists()) return // already recorded this exact exit — avoid re-reporting every launch

        val trace = runCatching { info.traceInputStream?.use { it.bufferedReader().readText() } }.getOrNull()

        val report = buildString {
            appendLine("PikoWalker 沒有回應紀錄（ANR，App 被系統強制關閉）")
            appendLine("時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date(info.timestamp))}")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）")
            appendLine("系統說明：${info.description}")
            appendLine("---")
            appendLine(trace ?: "（系統沒有保留這次的 ANR trace）")
        }
        runCatching { file.writeText(report) }
        runCatching { pruneOldReports(dir) }
    }

    private fun writeReport(context: Context, prefix: String, throwable: Throwable, thread: Thread?, tag: String? = null) {
        val dir = crashDir(context) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${prefix}_$stamp.txt")

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val report = buildString {
            appendLine(if (thread != null) "PikoWalker 當機紀錄" else "PikoWalker 攔截到的異常（App 已自動恢復，非當機）")
            if (tag != null) appendLine("來源：$tag")
            appendLine("時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())}")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）")
            if (thread != null) appendLine("執行緒：${thread.name}")
            appendLine("---")
            appendLine(stackTrace)
            appendLine("---")
            appendLine("最近的除錯紀錄：")
            appendLine(DebugLogger.recentEntriesText())
        }
        runCatching { file.writeText(report) }
        runCatching { pruneOldReports(dir) }
    }

    /** Keeps only the most recent [MAX_REPORTS] total (fatal + caught + ANR combined) — this
     *  directory lives in app-specific external storage and survives reinstalls, so without a
     *  cap it would grow forever. */
    private fun pruneOldReports(dir: File) {
        val files = dir.listFiles() ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS)
            .forEach { it.delete() }
    }

    private fun crashDir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    /** Most recent genuine crash report, if any — used to offer a share button in Settings
     *  without requiring the app to have survived long enough to reach the debug-log export flow. */
    fun latestReport(context: Context): File? =
        crashDir(context)?.listFiles()?.filter { it.name.startsWith("crash_") }?.maxByOrNull { it.lastModified() }

    /** Most recent caught-but-recovered report, if any — shown separately from [latestReport]
     *  since the app didn't actually crash for these. */
    fun latestCaughtReport(context: Context): File? =
        crashDir(context)?.listFiles()?.filter { it.name.startsWith("caught_") }?.maxByOrNull { it.lastModified() }

    /** Most recent ANR report, if any — see [checkForPreviousAnr]. */
    fun latestAnrReport(context: Context): File? =
        crashDir(context)?.listFiles()?.filter { it.name.startsWith("anr_") }?.maxByOrNull { it.lastModified() }

    /** Called once the user has shared a report — deletes it so Settings stops showing a stale
     *  prompt for something already sent. */
    fun deleteReport(file: File) {
        runCatching { file.delete() }
    }
}
