package com.firdaus.rekab

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class ExternalUsbMount(
    val index: Int,
    val label: String,
    val mountPoint: File,
    val streamRootDir: File,
    val fsType: String,
    val mountOptions: String,
    val writableVerified: Boolean,
    val probeLatencyMs: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val validationDetail: String,
) {
    val hasSufficientSpace: Boolean
        get() = freeBytes >= DiskManagerService.MIN_REQUIRED_FREE_BYTES

    val isReadyForStream: Boolean
        get() = writableVerified && hasSufficientSpace
}

data class DirectStreamTarget(
    val clipFile: File,
    val parcelFd: ParcelFileDescriptor,
    val mount: ExternalUsbMount,
) {
    fun closeDescriptorQuietly() {
        try { parcelFd.close() } catch (_: Exception) {}
    }
}

/**
 * Dedicated Disk Management Service that:
 * 1. Detects connected external USB drives via StorageManager, /proc/mounts, and USB/media broadcasts.
 * 2. Validates sufficient write permissions via atomic write + fsync + readback probes on the mount point.
 * 3. Routes camera stream recording directly to the validated external USB mount point via ParcelFileDescriptor.
 */
class DiskManagerService : Service() {

    inner class LocalBinder : Binder() {
        val service: DiskManagerService get() = this@DiskManagerService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val storageEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            AppLog.i("DiskManagerService event: ${action.substringAfterLast('.')}")
            mainHandler.postDelayed({
                val mounts = scanAndValidateExternalDrives(this@DiskManagerService, forceProbe = true)
                if (mounts.none { it.isReadyForStream } && RecorderService.running) {
                    AppLog.i("DiskManagerService: External USB drive lost or unwritable — stopping active recording")
                    RecorderService.stop(this@DiskManagerService)
                }
            }, 600)
        }
    }

    private val healthCheckTask = object : Runnable {
        override fun run() {
            scanAndValidateExternalDrives(this@DiskManagerService, forceProbe = false)
            mainHandler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        registerStorageReceivers()
        scanAndValidateExternalDrives(this, forceProbe = true)
        mainHandler.postDelayed(healthCheckTask, 5_000)
        AppLog.i("DiskManagerService started: monitoring external USB mount points")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESCAN_AND_PROBE) {
            scanAndValidateExternalDrives(this, forceProbe = true)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceRunning = false
        mainHandler.removeCallbacks(healthCheckTask)
        try { unregisterReceiver(storageEventReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun registerStorageReceivers() {
        try {
            val mediaFilter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            registerReceiver(storageEventReceiver, mediaFilter)
        } catch (_: Exception) {}

        try {
            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            registerReceiver(storageEventReceiver, usbFilter)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_RESCAN_AND_PROBE = "com.firdaus.rekab.DISK_RESCAN_AND_PROBE"
        const val MIN_REQUIRED_FREE_BYTES = 100L * 1024L * 1024L // 100 MB minimum to open a new stream segment
        private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        @Volatile var serviceRunning: Boolean = false
            private set

        @Volatile private var cachedMounts: List<ExternalUsbMount> = emptyList()
        @Volatile private var lastScanTimeMs: Long = 0L
        private val listeners = CopyOnWriteArrayList<(List<ExternalUsbMount>) -> Unit>()

        fun ensureStarted(ctx: Context) {
            try {
                ctx.startService(Intent(ctx.applicationContext, DiskManagerService::class.java))
            } catch (_: Exception) {}
        }

        fun triggerProbe(ctx: Context): List<ExternalUsbMount> {
            ensureStarted(ctx)
            return scanAndValidateExternalDrives(ctx.applicationContext, forceProbe = true)
        }

        fun addListener(listener: (List<ExternalUsbMount>) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (List<ExternalUsbMount>) -> Unit) {
            listeners.remove(listener)
        }

        fun currentMounts(ctx: Context): List<ExternalUsbMount> {
            val now = System.currentTimeMillis()
            if (cachedMounts.isEmpty() || now - lastScanTimeMs > 4_000L) {
                return scanAndValidateExternalDrives(ctx.applicationContext, forceProbe = false)
            }
            return cachedMounts
        }

        fun activeMount(ctx: Context): ExternalUsbMount? {
            val mounts = currentMounts(ctx)
            if (mounts.isEmpty()) return null
            val idx = Prefs(ctx).storageIndex.coerceIn(0, mounts.size - 1)
            return mounts[idx]
        }

        /**
         * Validates the active external USB drive and opens a direct file descriptor on its mount point
         * for routing camera MediaRecorder streams.
         */
        @Synchronized
        fun openDirectCameraStreamTarget(ctx: Context, cameraId: String): DirectStreamTarget? {
            val mount = activeMount(ctx) ?: run {
                AppLog.i("DiskManager: Cannot route camera $cameraId — no external USB drive connected")
                return null
            }

            // Re-validate write permission on the external mount point before opening stream
            val probe = verifyWritePermission(mount.streamRootDir)
            if (!probe.first) {
                AppLog.i("DiskManager: External mount ${mount.mountPoint.absolutePath} failed write check (${probe.third})")
                scanAndValidateExternalDrives(ctx, forceProbe = true)
                return null
            }

            val free = queryFreeBytes(mount.streamRootDir)
            if (free < MIN_REQUIRED_FREE_BYTES) {
                Storage.cleanup(ctx)
                val freeAfter = queryFreeBytes(mount.streamRootDir)
                if (freeAfter < MIN_REQUIRED_FREE_BYTES) {
                    AppLog.i("DiskManager: External mount ${mount.mountPoint.absolutePath} has insufficient space (${Storage.gb(freeAfter)})")
                    return null
                }
            }

            val safeCam = Storage.safe(cameraId)
            val camFolder = File(mount.streamRootDir, "cam_$safeCam").apply { mkdirs() }
            val clipFile = File(camFolder, "ReKab_cam${safeCam}_${stamp.format(Date())}.mp4")

            return try {
                val pfd = ParcelFileDescriptor.open(
                    clipFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
                DirectStreamTarget(clipFile, pfd, mount)
            } catch (e: Exception) {
                AppLog.e("DiskManager: Failed to open direct stream descriptor on ${clipFile.absolutePath}", e)
                null
            }
        }

        @Synchronized
        fun scanAndValidateExternalDrives(ctx: Context, forceProbe: Boolean): List<ExternalUsbMount> {
            val procMounts = parseProcMounts()
            val candidates = LinkedHashMap<String, Pair<String, File>>() // canonicalMountPoint -> (label, candidateDir)

            // 1. StorageManager removable volumes (USB OTG, USB HDD, SD)
            try {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (vol in sm.storageVolumes) {
                    if (!vol.isRemovable || vol.isPrimary) continue
                    if (vol.state != Environment.MEDIA_MOUNTED) continue
                    val volDir: File? = if (Build.VERSION.SDK_INT >= 30) {
                        vol.directory
                    } else {
                        try {
                            val m = vol.javaClass.getMethod("getPathFile")
                            m.invoke(vol) as? File
                        } catch (_: Exception) {
                            try {
                                val m2 = vol.javaClass.getMethod("getPath")
                                (m2.invoke(vol) as? String)?.let { File(it) }
                            } catch (_: Exception) { null }
                        }
                    }
                    if (volDir != null && volDir.exists()) {
                        val key = canonicalOrAbs(volDir)
                        val desc = vol.getDescription(ctx)?.takeIf { it.isNotBlank() } ?: "USB Drive (${volDir.name})"
                        candidates[key] = desc to volDir
                    }
                }
            } catch (_: Exception) {}

            // 2. Context.getExternalFilesDirs(null) secondary removable volumes
            try {
                val extDirs = ctx.getExternalFilesDirs(null)
                for ((i, dir) in extDirs.withIndex()) {
                    if (dir == null) continue
                    val isRemovable = try { Environment.isExternalStorageRemovable(dir) } catch (_: Exception) { i > 0 }
                    val isEmulated = try { Environment.isExternalStorageEmulated(dir) } catch (_: Exception) { i == 0 }
                    val state = try { Environment.getExternalStorageState(dir) } catch (_: Exception) { Environment.MEDIA_MOUNTED }
                    if ((i > 0 || (isRemovable && !isEmulated)) && state == Environment.MEDIA_MOUNTED) {
                        val mountRoot = resolveMountPointForDir(dir, procMounts)
                        val key = canonicalOrAbs(mountRoot)
                        if (!candidates.containsKey(key)) {
                            candidates[key] = "External USB Storage (${mountRoot.name})" to dir
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. Scan /proc/mounts and car head unit USB mount roots (/mnt/media_rw, /storage/usb*, /mnt/usb_storage)
            val huRoots = listOf(File("/mnt/media_rw"), File("/storage"), File("/mnt/usb_storage"))
            for (parent in huRoots) {
                val children = try { parent.listFiles() } catch (_: Exception) { null } ?: continue
                for (child in children) {
                    val name = child.name.lowercase(Locale.US)
                    if (name == "emulated" || name == "self" || name == "sdcard0" || name == "enc_emulated") continue
                    val looksUsb = name.startsWith("usb") || name.startsWith("udisk") ||
                        name.startsWith("sda") || name.startsWith("sdb") ||
                        name.startsWith("ext") || name.matches(Regex("[0-9a-f]{4}-[0-9a-f]{4}"))
                    if (!looksUsb) continue
                    if (child.isDirectory && queryTotalBytes(child) > 0L) {
                        val key = canonicalOrAbs(child)
                        if (!candidates.containsKey(key)) {
                            candidates[key] = "USB Mount (${child.name})" to child
                        }
                    }
                }
            }

            val results = mutableListOf<ExternalUsbMount>()
            var index = 0
            for ((mountKey, pair) in candidates) {
                val (label, baseCandidate) = pair
                val mountPoint = resolveMountPointForDir(baseCandidate, procMounts)
                val procEntry = findProcMountEntry(mountPoint, procMounts)
                val fsType = procEntry?.second ?: "usb-fs"
                val mountOpts = procEntry?.third ?: "rw"

                // Resolve best writable stream directory on this external USB mount point:
                // Prefer direct <mountPoint>/ReKab if writable, else <mountPoint>/Android/data/<pkg>/files/ReKab
                val streamDir = resolveWritableStreamDirectory(ctx, mountPoint, baseCandidate)
                val (writableOk, latencyMs, detail) = verifyWritePermission(streamDir)
                val free = queryFreeBytes(streamDir)
                val total = queryTotalBytes(streamDir)

                val mountInfo = ExternalUsbMount(
                    index = index++,
                    label = label,
                    mountPoint = mountPoint,
                    streamRootDir = streamDir,
                    fsType = fsType,
                    mountOptions = mountOpts,
                    writableVerified = writableOk,
                    probeLatencyMs = latencyMs,
                    freeBytes = free,
                    totalBytes = total,
                    validationDetail = detail,
                )
                results += mountInfo
                if (forceProbe) {
                    AppLog.i(
                        "DiskManager probe [${mountInfo.label} @ ${mountPoint.absolutePath}]: " +
                            "${if (writableOk) "WRITE OK (${latencyMs}ms)" else "WRITE FAILED"} · $detail"
                    )
                }
            }

            cachedMounts = results
            lastScanTimeMs = System.currentTimeMillis()
            for (l in listeners) {
                try { l(results) } catch (_: Exception) {}
            }
            return results
        }

        private fun resolveWritableStreamDirectory(ctx: Context, mountPoint: File, candidate: File): File {
            val candidatesToTry = LinkedHashSet<File>()
            // 1. Direct mount point root: <mountPoint>/ReKab
            candidatesToTry += File(mountPoint, "ReKab")
            // 2. If candidate is already an app-specific files dir
            if (candidate.absolutePath.contains("/Android/data/")) {
                candidatesToTry += File(candidate, "ReKab")
            }
            // 3. App-specific external directory on the USB mount point
            candidatesToTry += File(mountPoint, "Android/data/${ctx.packageName}/files/ReKab")
            // 4. Matching entry from getExternalFilesDirs(null) under this mountPoint
            val mountPrefix = canonicalOrAbs(mountPoint) + File.separator
            ctx.getExternalFilesDirs(null).filterNotNull().forEach { d ->
                if (canonicalOrAbs(d).startsWith(mountPrefix)) {
                    candidatesToTry += File(d, "ReKab")
                }
            }

            for (dir in candidatesToTry) {
                try {
                    if (dir.exists() || dir.mkdirs()) {
                        val (ok, _, _) = verifyWritePermission(dir)
                        if (ok) return dir
                    }
                } catch (_: Exception) {}
            }
            return File(mountPoint, "Android/data/${ctx.packageName}/files/ReKab").apply { mkdirs() }
        }

        /**
         * Performs an atomic hardware write/fsync/read/delete probe to verify real write permission
         * on the external USB drive mount point.
         */
        fun verifyWritePermission(targetDir: File): Triple<Boolean, Long, String> {
            val t0 = System.currentTimeMillis()
            return try {
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    return Triple(false, 0L, "Cannot create directory ${targetDir.absolutePath}")
                }
                if (!targetDir.canWrite()) {
                    return Triple(false, 0L, "Directory marked read-only: ${targetDir.absolutePath}")
                }
                val probeFile = File(targetDir, ".rekab_write_probe_${System.nanoTime()}.tmp")
                val payload = "REKAB_USB_STREAM_WRITE_OK_${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
                FileOutputStream(probeFile).use { fos ->
                    fos.write(payload)
                    fos.flush()
                    try { fos.fd.sync() } catch (_: Exception) {}
                }
                val readBack = ByteArray(payload.size)
                val readLen = FileInputStream(probeFile).use { fis -> fis.read(readBack) }
                probeFile.delete()
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (readLen == payload.size && readBack.contentEquals(payload)) {
                    Triple(true, elapsed, "Write/fsync verified on ${targetDir.absolutePath}")
                } else {
                    Triple(false, elapsed, "Readback verification mismatch on ${targetDir.absolutePath}")
                }
            } catch (e: Exception) {
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                Triple(false, elapsed, "Write denied (${e.javaClass.simpleName}: ${e.message})")
            }
        }

        private fun parseProcMounts(): List<Triple<String, String, String>> {
            val list = mutableListOf<Triple<String, String, String>>()
            try {
                File("/proc/mounts").forEachLine { line ->
                    val parts = line.split(" ")
                    if (parts.size >= 4) {
                        val mountPath = parts[1].replace("\\040", " ")
                        val fsType = parts[2]
                        val opts = parts[3]
                        list += Triple(mountPath, fsType, opts)
                    }
                }
            } catch (_: Exception) {}
            return list
        }

        private fun resolveMountPointForDir(dir: File, procMounts: List<Triple<String, String, String>>): File {
            val path = canonicalOrAbs(dir)
            val idx = path.indexOf("/Android/data/")
            if (idx > 0) {
                return File(path.substring(0, idx))
            }
            val matched = procMounts
                .filter { (mp, _, _) -> mp != "/" && mp != "/storage" && (path == mp || path.startsWith("$mp/")) }
                .maxByOrNull { it.first.length }
            return if (matched != null) File(matched.first) else dir
        }

        private fun findProcMountEntry(
            mountPoint: File,
            procMounts: List<Triple<String, String, String>>,
        ): Triple<String, String, String>? {
            val p = canonicalOrAbs(mountPoint)
            return procMounts
                .filter { (mp, _, _) -> mp != "/" && (p == mp || p.startsWith("$mp/") || mp.startsWith("$p/")) }
                .maxByOrNull { it.first.length }
        }

        private fun canonicalOrAbs(f: File): String =
            try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

        fun queryFreeBytes(f: File): Long =
            try { StatFs(f.absolutePath).availableBytes } catch (_: Exception) { 0L }

        fun queryTotalBytes(f: File): Long =
            try { StatFs(f.absolutePath).totalBytes } catch (_: Exception) { 0L }
    }
}
