package com.firdaus.rekab

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ClipItem(
    val file: File,
    val cameraId: String,
    val locked: Boolean,
)

/**
 * Storage facade backed by [DiskManagerService].
 *
 * Strictly routes all camera stream output and clip management to validated
 * external USB hard drive mount points. Car Head Unit internal storage is never used.
 */
object Storage {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    const val LOCKED = "Locked"

    /** Returns validated external USB mounts discovered by [DiskManagerService]. */
    fun externalMounts(ctx: Context): List<ExternalUsbMount> =
        DiskManagerService.currentMounts(ctx)

    /** Returns external USB stream root directories for backwards compatibility. */
    fun externalRoots(ctx: Context): List<File> =
        externalMounts(ctx).filter { it.isReadyForStream }.map { it.streamRootDir.parentFile ?: it.streamRootDir }

    /** True only when an external USB drive is connected, writable, and has sufficient space. */
    fun hasExternalDrive(ctx: Context): Boolean =
        DiskManagerService.activeMount(ctx)?.isReadyForStream == true

    fun mountLabel(m: ExternalUsbMount): String {
        val statusTag = if (m.isReadyForStream) "WRITE OK (${m.probeLatencyMs}ms)" else "READ-ONLY / NO SPACE"
        return "${m.label} [${m.fsType}] · ${gb(m.freeBytes)}/${gb(m.totalBytes)} free · $statusTag · ${m.mountPoint.absolutePath}"
    }

    /** Returns the active ReKab stream directory on the validated external USB mount point, or null if unavailable. */
    fun baseOrNull(ctx: Context): File? {
        val mount = DiskManagerService.activeMount(ctx) ?: return null
        if (!mount.isReadyForStream) return null
        return mount.streamRootDir.apply { mkdirs() }
    }

    fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun camDir(ctx: Context, cameraId: String): File? {
        val b = baseOrNull(ctx) ?: return null
        return File(b, "cam_${safe(cameraId)}").apply { mkdirs() }
    }

    fun lockedDir(ctx: Context): File? {
        val b = baseOrNull(ctx) ?: return null
        return File(b, LOCKED).apply { mkdirs() }
    }

    fun newClip(ctx: Context, cameraId: String): File? {
        val dir = camDir(ctx, cameraId) ?: return null
        return File(dir, "ReKab_cam${safe(cameraId)}_${stamp.format(Date())}.mp4")
    }

    fun freeBytes(f: File): Long = try { StatFs(f.absolutePath).availableBytes } catch (_: Exception) { 0L }

    /** All loop clips (not the locked ones), oldest first. */
    fun loopClips(ctx: Context): List<File> {
        val b = baseOrNull(ctx) ?: return emptyList()
        return (b.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("cam_") }
            .flatMap { (it.listFiles() ?: emptyArray()).filter { f -> f.isFile && f.name.endsWith(".mp4") } }
            .sortedBy { it.lastModified() }
    }

    /** All clips (both locked and loop clips) on the external drive, newest first, for Playback UI. */
    fun allClips(ctx: Context): List<ClipItem> {
        val b = baseOrNull(ctx) ?: return emptyList()
        val items = mutableListOf<ClipItem>()
        val dirs = (b.listFiles() ?: emptyArray()).filter { it.isDirectory }
        for (d in dirs) {
            val isLocked = d.name == LOCKED
            val files = (d.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".mp4") }
            for (f in files) {
                val camId = if (isLocked) {
                    Regex("ReKab_cam([^_]+)_").find(f.name)?.groupValues?.getOrNull(1) ?: "?"
                } else {
                    d.name.removePrefix("cam_")
                }
                items += ClipItem(f, camId, isLocked)
            }
        }
        return items.sortedByDescending { it.file.lastModified() }
    }

    /** Deletes oldest loop clips until under the size limit and at least 500 MB is free. */
    fun cleanup(ctx: Context) {
        val b = baseOrNull(ctx) ?: return
        val limit = Prefs(ctx).maxStorageGb.toLong() * 1024 * 1024 * 1024
        val clips = loopClips(ctx).toMutableList()
        var total = clips.sumOf { it.length() }
        val minFree = 500L * 1024 * 1024
        val now = System.currentTimeMillis()
        while (clips.isNotEmpty() && (total > limit || freeBytes(b) < minFree)) {
            val f = clips.removeAt(0)
            if (now - f.lastModified() < 15_000) break // probably still being written
            val len = f.length()
            if (f.delete()) {
                total -= len
                AppLog.i("Loop: deleted old clip ${f.name}")
            } else break
        }
    }

    /** Moves the newest finished clip of every camera into the Locked folder. */
    fun lockLatest(ctx: Context): Int {
        val lockedFolder = lockedDir(ctx) ?: return 0
        var n = 0
        val byCam = loopClips(ctx).groupBy { it.parentFile?.name }
        for ((_, files) in byCam) {
            for (f in files.takeLast(2)) {
                val dest = File(lockedFolder, f.name)
                if (f.renameTo(dest)) n++ else if (f.copyTo(dest, true).exists()) n++
            }
        }
        return n
    }

    fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
