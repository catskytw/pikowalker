package com.pikowalker.app.debug

import android.content.Context
import android.os.Build
import com.pikowalker.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures uncaught exceptions — including ones during app startup, before the user could ever
 *  reach the debug-log export screen — to a file outside the app's own process lifetime, so a
 *  crash-on-launch is diagnosable even though the app never got far enough to be told to log
 *  anything. Written to app-specific external storage (not cache/internal) so it survives an
 *  app reinstall and is reachable via `adb pull` without root. */
object CrashLogger {
    private const val DIR_NAME = "crash_logs"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context, thread, throwable)
            } catch (_: Throwable) {
                // Never let crash reporting itself block the crash from proceeding normally.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val report = buildString {
            appendLine("PikoWalker 當機紀錄")
            appendLine("時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())}")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）")
            appendLine("執行緒：${thread.name}")
            appendLine("---")
            appendLine(stackTrace)
            appendLine("---")
            appendLine("最近的除錯紀錄：")
            appendLine(DebugLogger.recentEntriesText())
        }
        runCatching { file.writeText(report) }
    }

    private fun crashDir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    /** Most recent crash report file, if any — used to offer a share button in Settings without
     *  requiring the app to have survived long enough to reach the debug-log export flow. */
    fun latestReport(context: Context): File? =
        crashDir(context)?.listFiles()?.maxByOrNull { it.lastModified() }
}
