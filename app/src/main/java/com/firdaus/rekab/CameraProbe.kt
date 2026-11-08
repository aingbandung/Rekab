package com.firdaus.rekab

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size

data class CamInfo(
    val id: String,
    val hidden: Boolean,
    val facing: String,
    val role: String,
    val level: String,
    val videoSizes: List<Size>,
    val previewSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val physicalIds: Set<String>,
) {
    val maxSize: Size? get() = videoSizes.maxByOrNull { it.width.toLong() * it.height }

    fun label(): String {
        val sz = maxSize?.let { "${it.width}x${it.height}" } ?: "no video sizes"
        val h = if (hidden) " [hidden]" else ""
        val phys = if (physicalIds.isNotEmpty()) " (physical: ${physicalIds.joinToString()})" else ""
        return "Cam $id ($role) — $facing — $sz — $level$h$phys"
    }

    /** Largest size not taller than maxHeight (0 = no limit). */
    fun pickVideoSize(maxHeight: Int): Size? {
        val ok = videoSizes.filter { maxHeight == 0 || it.height <= maxHeight }
        return (ok.ifEmpty { videoSizes.sortedBy { it.height }.take(1) })
            .maxByOrNull { it.width.toLong() * it.height }
    }

    fun pickPreviewSize(): Size? {
        val ok = previewSizes.filter { it.height <= 720 }
        return (ok.ifEmpty { previewSizes }).maxByOrNull { it.width.toLong() * it.height }
    }

    /** A fixed or range FPS that reaches 30 if possible, else the highest. */
    fun pickFps(): Range<Int>? =
        fpsRanges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: fpsRanges.maxByOrNull { it.upper }
}

object CameraProbe {

    /**
     * Auto-detects all car cameras: system-reported cameras, physical cameras behind logical
     * multi-cameras, and hidden numeric IDs 0..31 used by car head unit 360°/AVM systems.
     */
    fun scan(ctx: Context, probeHidden: Boolean = true): List<CamInfo> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val listed = try { cm.cameraIdList.toList() } catch (e: Exception) {
            AppLog.e("cameraIdList failed", e); emptyList()
        }
        val out = mutableListOf<CamInfo>()
        val seen = HashSet<String>()
        for (id in listed) describe(cm, id, false, out.size)?.let { out += it; seen += id }

        // Physical cameras behind logical ones
        for (c in out.toList()) {
            for (p in c.physicalIds) {
                if (seen.add(p)) describe(cm, p, true, out.size)?.let { out += it }
            }
        }

        if (probeHidden) {
            for (n in 0..31) {
                val id = n.toString()
                if (id in seen) continue
                describe(cm, id, true, out.size)?.let {
                    out += it; seen += id
                    AppLog.i("Auto-detected hidden car camera ID $id (${it.role})")
                }
            }
        }

        AppLog.i("Auto-detect found ${out.size} car camera(s): ${out.joinToString { "${it.id}:${it.role}" }.ifEmpty { "none" }}")

        // Auto-select detected recordable cameras on first detection if none selected yet
        val prefs = Prefs(ctx)
        if (!prefs.autoDetectedOnce && out.isNotEmpty() && prefs.selectedCameras.isEmpty()) {
            val recordable = out.filter { it.videoSizes.isNotEmpty() }.map { it.id }.toSet()
            if (recordable.isNotEmpty()) {
                prefs.selectedCameras = recordable
                prefs.autoDetectedOnce = true
                AppLog.i("Auto-selected car cameras for recording: ${recordable.joinToString()}")
            }
        }

        return out
    }

    private fun inferCarRole(id: String, facing: String, index: Int, hidden: Boolean): String {
        return when {
            id == "0" || (index == 0 && facing == "back") -> "Front Dashcam"
            id == "1" || (index == 1 && facing == "front") -> "Rear / Cabin Cam"
            id == "2" -> "Left 360° Cam"
            id == "3" -> "Right 360° Cam"
            facing == "external" -> "External Car Cam"
            hidden -> "AVM / Car Cam $id"
            else -> "Car Cam $id"
        }
    }

    private fun describe(cm: CameraManager, id: String, hidden: Boolean, index: Int): CamInfo? {
        return try {
            val ch = cm.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "car-bus"
            }
            val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "OEM"
            }
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val video = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            val prev = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
            val phys = if (Build.VERSION.SDK_INT >= 28) ch.physicalCameraIds else emptySet()
            val role = inferCarRole(id, facing, index, hidden)
            CamInfo(id, hidden, facing, role, level, video, prev, fps, phys)
        } catch (e: IllegalArgumentException) {
            null // ID does not exist
        } catch (e: Exception) {
            if (!hidden) AppLog.e("Camera $id: cannot read details", e)
            null
        }
    }
}
