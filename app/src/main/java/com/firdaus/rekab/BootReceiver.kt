package com.firdaus.rekab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Starts DiskManagerService and recording after the head unit boots, if enabled and USB drive is writable. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        DiskManagerService.ensureStarted(appCtx)
        val p = Prefs(appCtx)
        if (!p.autoStartOnBoot) return
        AppLog.i("Boot: starting DiskManagerService and scanning car cameras in 10 s")
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                CameraProbe.scan(appCtx, probeHidden = true)
                val mounts = DiskManagerService.triggerProbe(appCtx)
                val active = DiskManagerService.activeMount(appCtx)
                if (active == null || !active.isReadyForStream) {
                    AppLog.i("Boot: skipped auto-record — no writable external USB mount (detected ${mounts.size})")
                } else if (Prefs(appCtx).selectedCameras.isNotEmpty()) {
                    RecorderService.start(appCtx)
                }
            } catch (e: Exception) {
                AppLog.e("Boot auto-start failed", e)
            }
            pending.finish()
        }, 10_000)
    }
}
