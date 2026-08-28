package com.pikowalker.app.debug

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.pikowalker.app.BuildConfig
import com.pikowalker.app.schedule.ScheduleManager
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

    // Set by PikStepApp's ComponentCallbacks2 — the most recent onTrimMemory level, so a failure
    // diagnostic can report it even when the failure doesn't happen to coincide with a fresh
    // callback.
    @Volatile var lastTrimMemoryLevel: Int? = null

    fun log(tag: String, message: String) {
        android.util.Log.d("Piko-$tag", message)
        // Mirrored to Crashlytics as a breadcrumb — attaches to whatever recordException/crash
        // fires next, giving the same cross-subsystem timeline correlation (Activity lifecycle
        // vs. Location events) that previously required the user to notice, export, and share
        // this log by hand. Includes real/simulated lat-lng from the periodic Service snapshots.
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log("$tag: $message") }
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

    /** Recent in-memory entries as plain text — used to attach whatever context exists to a
     *  crash report (see [com.pikowalker.app.debug.CrashLogger]). */
    fun recentEntriesText(minutes: Int = 10): String = snapshotText(minutes)

    /** Everything worth sharing in one file: the recent debug log, plus whatever crash/caught/
     *  ANR reports [CrashLogger] currently has on disk — folded in and then deleted, so the user
     *  has one button and one file instead of juggling several separate share prompts. */
    fun exportCombinedReport(context: Context, minutes: Int = 10): File {
        val dir = File(context.cacheDir, "debug_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "pikowalker_debug_$stamp.txt")

        val text = buildString {
            appendLine("PikoWalker 除錯紀錄")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）")
            appendLine("授權狀態：${permissionSnapshot(context)}")
            appendLine("涵蓋範圍：最近 $minutes 分鐘")
            appendLine("---")
            appendLine(snapshotText(minutes))

            val extraReports = listOfNotNull(
                CrashLogger.latestReport(context),
                CrashLogger.latestCaughtReport(context),
                CrashLogger.latestAnrReport(context)
            )
            extraReports.forEach { report ->
                appendLine()
                appendLine("=====================================")
                appendLine(runCatching { report.readText() }.getOrDefault("（讀取 ${report.name} 失敗）"))
            }
            extraReports.forEach { CrashLogger.deleteReport(it) }
        }
        file.writeText(text)
        runCatching {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_EXPORTS)?.forEach { it.delete() }
        }
        return file
    }

    /** Same checks as the 設定與授權 screen, collapsed into one line — lets a shared export show
     *  at a glance whether a reported bug might just be a permission the user never granted,
     *  instead of having to ask them to go re-check Settings after the fact. */
    private fun permissionSnapshot(context: Context): String {
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        else true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val isSimulating = (context.applicationContext as? com.pikowalker.app.PikStepApp)
            ?.walkRepository?.currentState?.isSimulating == true
        val isMockLocationApp = checkIsMockLocationApp(context, isSimulating)
        val canScheduleExact = ScheduleManager.canScheduleExact(context)
        return "位置權限=$hasLocation 通知權限=$hasNotification 虛擬位置應用程式=$isMockLocationApp " +
            "電池最佳化排除=$batteryExempt 精確鬧鐘=$canScheduleExact"
    }

    // See the identical guard in SettingsScreen.kt's copy of this function for why: probing via
    // addTestProvider()+removeTestProvider() while a session is already running tears down the
    // provider LocationSimulator is actively feeding, which reads to the user as GPS briefly
    // jumping/dropping — exactly what exporting a debug log mid-walk would otherwise cause.
    @SuppressLint("MissingPermission")
    private fun checkIsMockLocationApp(context: Context, isSimulating: Boolean): Boolean {
        if (isSimulating) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false, false,
                true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
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
}
