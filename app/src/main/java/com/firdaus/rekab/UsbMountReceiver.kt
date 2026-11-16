package com.firdaus.rekab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Wakes ReKab when a USB drive is mounted, even if the app is not open,
 * and starts recording if "auto-record when USB drive is plugged in" is on.
 * The head unit only sends this while it is powered, i.e. while the car is on.
 */
class UsbMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        if (DiskManagerService.serviceRunning) return // the running service handles it
        AppLog.i("USB drive mounted: ${intent.data?.path ?: "?"}")
        DiskManagerService.ensureStarted(app)
        val pending = goAsync()
        // give the drive a moment to become writable
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                DiskManagerService.triggerProbe(app)
                DiskManagerService.autoStartIfReady(app, "USB drive connected")
            } catch (e: Exception) {
                AppLog.e("USB auto-record failed", e)
            } finally {
                pending.finish()
            }
        }, 3_000)
    }
}
