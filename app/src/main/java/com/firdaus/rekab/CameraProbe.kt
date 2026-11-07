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
    val level: String,
    val videoSizes: List<Size>,
    val previewSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val physicalIds: Set<String>,
) {
    val maxSize: Size? get() = videoSizes.maxByOrNull { it.width.toLong() * it.height }

    fun label(): String {
        val sz = maxSize?.let { "${it.width}x${it.height}" } ?: "no video sizes"
        val h = if (hidden) "  [hidden ID]" else ""
        val phys = if (physicalIds.isNotEmpty()) "  physical: ${physicalIds.joinToString()}" else ""
        return "Camera $id — $facing — max $sz — $level$h$phys"
    }

    /** Largest size not taller than maxHeight (0 = no limit). Prefers 16:9 / 4:3 normal shapes. */
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

    /** Lists cameras the system reports, plus any hidden numeric IDs 0..31 that answer. */
    fun scan(ctx: Context, probeHidden: Boolean): List<CamInfo> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val listed = try { cm.cameraIdList.toList() } catch (e: Exception) {
            AppLog.e("cameraIdList failed", e); emptyList()
        }
        AppLog.i("System lists ${listed.size} camera(s): ${listed.joinToString().ifEmpty { "none" }}")
        val out = mutableListOf<CamInfo>()
        val seen = HashSet<String>()
        for (id in listed) describe(cm, id, false)?.let { out += it; seen += id }

        // physical cameras behind logical ones
        for (c in out.toList()) for (p in c.physicalIds) if (seen.add(p)) describe(cm, p, true)?.let { out += it }

        if (probeHidden) {
            for (n in 0..31) {
                val id = n.toString()
                if (id in seen) continue
                describe(cm, id, true)?.let {
                    out += it; seen += id
                    AppLog.i("Found hidden camera ID $id")
                }
            }
        }
        if (out.isEmpty()) AppLog.i("No cameras are visible to apps on this unit.")
        return out
    }

    private fun describe(cm: CameraManager, id: String, hidden: Boolean): CamInfo? {
        return try {
            val ch = cm.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown facing"
            }
            val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "level ?"
            }
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val video = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            val prev = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
            val phys = if (Build.VERSION.SDK_INT >= 28) ch.physicalCameraIds else emptySet()
            CamInfo(id, hidden, facing, level, video, prev, fps, phys)
        } catch (e: IllegalArgumentException) {
            null // ID does not exist
        } catch (e: Exception) {
            if (!hidden) AppLog.e("Camera $id: cannot read details", e)
            null
        }
    }
}
