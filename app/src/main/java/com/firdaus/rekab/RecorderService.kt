package com.firdaus.rekab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service that keeps the selected cameras recording. */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.firdaus.rekab.START"
        const val ACTION_STOP = "com.firdaus.rekab.STOP"
        private const val CHANNEL = "rekab_rec"
        private const val NOTIF_ID = 7

        @Volatile private var recorders: List<CameraRecorder> = emptyList()
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            Prefs(ctx).recordingWanted = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).recordingWanted = false
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun statusLines(): List<String> = recorders.map { "Camera ${it.id}: ${it.status}" }

        fun summary(): String =
            if (!running) "Not recording" else "Recording ${recorders.size} camera(s)"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs(this).recordingWanted) {
            stopSelf(); return START_NOT_STICKY
        }
        if (running) return START_STICKY
        begin()
        return START_STICKY
    }

    private fun begin() {
        val prefs = Prefs(this)
        val camOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val audio = prefs.recordAudio && micOk

        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (audio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), type)
        } catch (e: Exception) {
            AppLog.e("Cannot start foreground recording service", e)
            stopSelf(); return
        }
        if (!camOk) {
            AppLog.i("Camera permission not granted — open ReKab and allow camera")
            stopSelf(); return
        }

        val wanted = prefs.selectedCameras
        if (wanted.isEmpty()) {
            AppLog.i("No camera selected — tick at least one camera")
            stopSelf(); return
        }
        val all = CameraProbe.scan(this, probeHidden = true).associateBy { it.id }
        val list = mutableListOf<CameraRecorder>()
        var micGiven = false
        for (id in wanted.sorted()) {
            val info = all[id]
            if (info == null) { AppLog.i("Selected camera $id not found now — skipped"); continue }
            val useMic = audio && !micGiven
            if (useMic) micGiven = true
            list += CameraRecorder(this, info, useMic)
        }
        if (list.isEmpty()) { stopSelf(); return }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReKab:rec").apply { setReferenceCounted(false); acquire() }

        if (prefs.phoneAccess) FileServer.start(this, prefs.port)
        Storage.cleanup(this)
        recorders = list
        running = true
        list.forEach { it.start() }
        updateNotification("Recording ${list.size} camera(s): ${list.joinToString { it.id }}")
        AppLog.i("Recording started → ${Storage.base(this).absolutePath}")
    }

    private fun stopAll() {
        if (running) AppLog.i("Recording stopped")
        recorders.forEach { it.stop() }
        recorders = emptyList()
        running = false
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
