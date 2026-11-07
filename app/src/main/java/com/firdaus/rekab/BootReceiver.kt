package com.firdaus.rekab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Starts recording after the head unit boots, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        if (!p.autoStartOnBoot || p.selectedCameras.isEmpty()) return
        AppLog.i("Boot: auto-starting recording in 10 s")
        val pending = goAsync()
        // give the car's camera system time to come up
        Handler(Looper.getMainLooper()).postDelayed({
            try { RecorderService.start(context.applicationContext) }
            catch (e: Exception) { AppLog.e("Boot auto-start failed", e) }
            pending.finish()
        }, 10_000)
    }
}
