package com.firdaus.rekab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service that keeps the selected car cameras streaming directly to the external USB mount point. */
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
            DiskManagerService.ensureStarted(ctx)
            Prefs(ctx).recordingWanted = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).recordingWanted = false
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun statusLines(): List<String> = recorders.map { "Camera ${it.id}: ${it.status}" }

        /**
         * Finalizes every open clip right now and waits for it (max [timeoutMs]).
         * Called before power off or before the USB drive goes away.
         * Does not change the user's "recording wanted" setting.
         */
        fun finalizeAll(reason: String, timeoutMs: Long = 4_000): Boolean {
            val list = recorders
            if (list.isEmpty()) return true
            AppLog.i("$reason — closing ${list.size} clip(s) safely")
            val t0 = System.currentTimeMillis()
            // stop in parallel so several cameras share the same time budget
            val results = list.map { r ->
                val t = Thread { r.stopBlocking(timeoutMs) }
                t.start(); t
            }
            results.forEach { it.join((timeoutMs - (System.currentTimeMillis() - t0)).coerceAtLeast(1)) }
            recorders = emptyList()
            running = false
            AppLog.i("Clips closed in ${System.currentTimeMillis() - t0} ms")
            return true
        }

        fun summary(): String =
            if (!running) "Not recording" else "Streaming ${recorders.size} camera(s) directly to External USB Mount"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var powerReceiverOn = false

    /**
     * Power events from the head unit:
     * - shutdown / power-off: close every clip properly so the last MP4 is not corrupted.
     * - screen off (often the first sign of ACC off / sleep): close the current clip and
     *   start a new one, so everything recorded so far is safely saved.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    AppLog.i("Screen off — saving current clip(s)")
                    recorders.forEach { it.rollSegment() }
                }
                else -> {
                    val r = goAsync()
                    Thread {
                        try {
                            finalizeAll("Head unit powering off (${i?.action?.substringAfterLast('.')})")
                        } finally { r.finish() }
                    }.start()
                }
            }
        }
    }

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
        DiskManagerService.ensureStarted(this)
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
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Validating USB mount & starting…"), type)
        } catch (e: Exception) {
            AppLog.e("Cannot start foreground recording service", e)
            stopSelf(); return
        }
        if (!camOk) {
            AppLog.i("Camera permission not granted — open ReKab and allow camera")
            stopSelf(); return
        }

        // Validate connected external USB drive & write permissions via DiskManagerService
        val mounts = DiskManagerService.triggerProbe(this)
        val activeMount = DiskManagerService.activeMount(this)
        if (activeMount == null || !activeMount.writableVerified) {
            val reason = activeMount?.validationDetail ?: "No external USB drive connected (found ${mounts.size} mount(s))"
            AppLog.i("DiskManagerService blocked recording: $reason")
            stopSelf(); return
        }
        if (!activeMount.hasSufficientSpace) {
            Storage.cleanup(this)
            if (DiskManagerService.queryFreeBytes(activeMount.streamRootDir) < DiskManagerService.MIN_REQUIRED_FREE_BYTES) {
                AppLog.i("DiskManagerService blocked recording: External USB drive is full")
                stopSelf(); return
            }
        }

        val all = CameraProbe.scan(this, probeHidden = true).associateBy { it.id }
        val wanted = prefs.selectedCameras
        if (wanted.isEmpty()) {
            AppLog.i("No camera selected — tick at least one camera")
            stopSelf(); return
        }
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
        registerPowerReceiver()
        recorders = list
        running = true
        list.forEach { it.start() }
        updateNotification("Streaming ${list.size} cam(s) → ${activeMount.mountPoint.absolutePath}")
        AppLog.i("Direct USB stream started → Mount: ${activeMount.mountPoint.absolutePath} (${activeMount.streamRootDir.absolutePath})")
    }

    private fun registerPowerReceiver() {
        if (powerReceiverOn) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
            addAction(Intent.ACTION_REBOOT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, f, android.content.Context.RECEIVER_EXPORTED)
            else registerReceiver(powerReceiver, f)
            powerReceiverOn = true
        } catch (e: Exception) { AppLog.e("Cannot watch power events", e) }
    }

    private fun stopAll() {
        if (powerReceiverOn) {
            try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
            powerReceiverOn = false
        }
        if (running) AppLog.i("Recording stopped")
        // finalize properly (waits up to 4 s) instead of fire-and-forget
        finalizeAll("Stopping")
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
