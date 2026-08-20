package com.pikowalker.app.debug

import android.os.Handler
import android.os.Looper
import android.os.Process

/** Detects a fully wedged main thread and kills the process outright.
 *
 *  MapLibre's native renderer has been observed to deadlock the main thread on resume from a
 *  long background stint (main thread stuck forever inside `MapRenderer::update`'s native
 *  mutex) — confirmed live via repeated `kill -3` thread dumps 30+ minutes apart, showing zero
 *  progress. Android's own ANR mechanism doesn't save the user here: it only logs and (if the
 *  UI happens to be visible) offers a Wait/Close dialog — it does not kill the process outright,
 *  and a foreground service like [com.pikowalker.app.service.MockLocationService] is specifically
 *  shielded from being killed by the system. Even the user manually tapping 強制停止 in Settings
 *  was observed to silently fail against this exact wedge. So the app has to protect itself.
 *
 *  The heartbeat write happens on the main thread and is expected to stop the moment it wedges —
 *  that's the signal, not a bug. The check and the kill both run on a separate daemon thread that
 *  never touches MapLibre/GL/Compose, so it keeps ticking even while main is fully stuck. Killing
 *  the process doesn't relaunch the UI — the user still has to tap the icon again — but the next
 *  launch is then a genuinely fresh process instead of the same wedged one Settings couldn't stop. */
object MainThreadWatchdog {
    private const val HEARTBEAT_INTERVAL_MS = 5_000L
    private const val FREEZE_THRESHOLD_MS = 20_000L

    @Volatile private var lastHeartbeat = System.currentTimeMillis()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true

        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var beat: Runnable
        beat = Runnable {
            lastHeartbeat = System.currentTimeMillis()
            mainHandler.postDelayed(beat, HEARTBEAT_INTERVAL_MS)
        }
        mainHandler.post(beat)

        Thread({
            while (true) {
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
                if (System.currentTimeMillis() - lastHeartbeat > FREEZE_THRESHOLD_MS) {
                    Process.killProcess(Process.myPid())
                }
            }
        }, "MainThreadWatchdog").apply { isDaemon = true }.start()
    }
}
