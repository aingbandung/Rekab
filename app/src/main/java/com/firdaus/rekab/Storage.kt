package com.firdaus.rekab

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where clips are written.
 *
 * Uses the app's own folders from getExternalFilesDirs(), which need no storage
 * permission and include USB drives / SD cards when the system mounts them.
 */
object Storage {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    const val LOCKED = "Locked"

    fun roots(ctx: Context): List<File> =
        ctx.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(ctx.filesDir) }

    fun rootLabel(ctx: Context, i: Int, f: File): String {
        val kind = if (i == 0) "Internal" else
            try { if (Environment.isExternalStorageRemovable(f)) "USB/SD" else "Storage $i" }
            catch (_: Exception) { "Storage $i" }
        return "$kind  (${gb(freeBytes(f))} free)  ${f.absolutePath}"
    }

    fun base(ctx: Context): File {
        val r = roots(ctx)
        val idx = Prefs(ctx).storageIndex.coerceIn(0, r.size - 1)
        return File(r[idx], "ReKab").apply { mkdirs() }
    }

    fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun camDir(ctx: Context, cameraId: String) =
        File(base(ctx), "cam_${safe(cameraId)}").apply { mkdirs() }

    fun lockedDir(ctx: Context) = File(base(ctx), LOCKED).apply { mkdirs() }

    fun newClip(ctx: Context, cameraId: String): File =
        File(camDir(ctx, cameraId), "ReKab_cam${safe(cameraId)}_${stamp.format(Date())}.mp4")

    fun freeBytes(f: File): Long = try { StatFs(f.absolutePath).availableBytes } catch (_: Exception) { 0L }

    /** All loop clips (not the locked ones), oldest first. */
    fun loopClips(ctx: Context): List<File> =
        (base(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("cam_") }
            .flatMap { (it.listFiles() ?: emptyArray()).filter { f -> f.isFile && f.name.endsWith(".mp4") } }
            .sortedBy { it.lastModified() }

    /** Deletes oldest loop clips until under the size limit and at least 500 MB is free. */
    fun cleanup(ctx: Context) {
        val limit = Prefs(ctx).maxStorageGb.toLong() * 1024 * 1024 * 1024
        val clips = loopClips(ctx).toMutableList()
        var total = clips.sumOf { it.length() }
        val minFree = 500L * 1024 * 1024
        val now = System.currentTimeMillis()
        while (clips.isNotEmpty() && (total > limit || freeBytes(base(ctx)) < minFree)) {
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
        var n = 0
        val byCam = loopClips(ctx).groupBy { it.parentFile?.name }
        for ((_, files) in byCam) {
            // newest file may still be recording; lock the newest two to cover the moment
            for (f in files.takeLast(2)) {
                val dest = File(lockedDir(ctx), f.name)
                if (f.renameTo(dest)) n++ else if (f.copyTo(dest, true).exists()) n++
            }
        }
        return n
    }

    fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
