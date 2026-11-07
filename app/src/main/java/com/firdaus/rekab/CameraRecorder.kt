package com.firdaus.rekab

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File

/**
 * Records one camera in fixed-length segments (loop recording).
 * Survives the camera being taken by the system (e.g. 360° view when reversing):
 * it closes, waits, and reopens.
 */
class CameraRecorder(
    private val ctx: Context,
    private val info: CamInfo,
    private val withAudio: Boolean,
) {
    private val prefs = Prefs(ctx)
    private val thread = HandlerThread("rec-${info.id}").apply { start() }
    private val handler = Handler(thread.looper)
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var file: File? = null
    @Volatile private var stopped = false
    @Volatile var status: String = "starting"
        private set

    val id get() = info.id

    fun start() = handler.post { open() }

    fun stop() {
        stopped = true
        handler.post {
            endSegment()
            try { device?.close() } catch (_: Exception) {}
            device = null
            status = "stopped"
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (stopped) return
        status = "opening camera"
        try {
            cm.openCamera(info.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (stopped) { d.close(); return }
                    device = d
                    AppLog.i("Camera ${info.id} opened")
                    beginSegment()
                }

                override fun onDisconnected(d: CameraDevice) {
                    AppLog.i("Camera ${info.id} taken by another app/system — will retry")
                    closeAndRetry(d)
                }

                override fun onError(d: CameraDevice, error: Int) {
                    val why = when (error) {
                        ERROR_CAMERA_IN_USE -> "in use"
                        ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
                        ERROR_CAMERA_DISABLED -> "disabled by system policy"
                        ERROR_CAMERA_DEVICE -> "device error"
                        ERROR_CAMERA_SERVICE -> "camera service error"
                        else -> "error $error"
                    }
                    AppLog.i("Camera ${info.id}: $why — will retry")
                    status = "error: $why (retrying)"
                    closeAndRetry(d)
                }
            }, handler)
        } catch (e: SecurityException) {
            status = "no permission"
            AppLog.e("Camera ${info.id}: permission denied", e)
        } catch (e: Exception) {
            status = "cannot open: ${e.message}"
            AppLog.e("Camera ${info.id}: cannot open", e)
            retryLater()
        }
    }

    private fun closeAndRetry(d: CameraDevice) {
        endSegment()
        try { d.close() } catch (_: Exception) {}
        device = null
        retryLater()
    }

    private fun retryLater() {
        if (!stopped) handler.postDelayed({ open() }, 5_000)
    }

    @Suppress("DEPRECATION")
    private fun beginSegment() {
        val d = device ?: return
        if (stopped) return
        val size = info.pickVideoSize(prefs.maxHeight)
        if (size == null) {
            status = "camera has no recordable sizes"
            AppLog.i("Camera ${info.id}: no MediaRecorder sizes, cannot record")
            return
        }
        val fps = info.pickFps()
        val fpsVal = fps?.upper?.coerceAtMost(30) ?: 30
        val out = Storage.newClip(ctx, info.id)
        val bitrate = (size.width.toLong() * size.height * fpsVal / 8).coerceIn(1_000_000L, 12_000_000L).toInt()

        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(out.absolutePath)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoEncodingBitRate(bitrate)
            mr.setVideoFrameRate(fpsVal)
            mr.setVideoSize(size.width, size.height)
            if (withAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioSamplingRate(44_100)
            }
            mr.setMaxDuration(prefs.segmentMinutes * 60_000)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    handler.post { nextSegment() }
                }
            }
            mr.setOnErrorListener { _, what, extra ->
                AppLog.i("Camera ${info.id}: recorder error $what/$extra — restarting segment")
                handler.post { nextSegment() }
            }
            mr.prepare()
        } catch (e: Exception) {
            AppLog.e("Camera ${info.id}: recorder setup failed at ${size.width}x${size.height}", e)
            try { mr.release() } catch (_: Exception) {}
            out.delete()
            status = "recorder setup failed (retrying)"
            handler.postDelayed({ beginSegment() }, 5_000)
            return
        }

        recorder = mr
        file = out
        val s = mr.surface
        surface = s
        try {
            d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (stopped || device == null) { cs.close(); return }
                    session = cs
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(s)
                            if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        }
                        cs.setRepeatingRequest(req.build(), null, handler)
                        mr.start()
                        status = "REC ${size.width}x${size.height} → ${out.name}"
                        AppLog.i("Camera ${info.id}: recording ${out.name}")
                    } catch (e: Exception) {
                        AppLog.e("Camera ${info.id}: could not start recording", e)
                        status = "start failed (retrying)"
                        endSegment()
                        handler.postDelayed({ beginSegment() }, 5_000)
                    }
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    AppLog.i("Camera ${info.id}: session config failed at ${size.width}x${size.height}")
                    status = "session failed (retrying)"
                    endSegment()
                    handler.postDelayed({ beginSegment() }, 5_000)
                }
            }, handler)
        } catch (e: Exception) {
            AppLog.e("Camera ${info.id}: createCaptureSession failed", e)
            endSegment()
            retryAfterClose()
        }
    }

    private fun retryAfterClose() {
        try { device?.close() } catch (_: Exception) {}
        device = null
        retryLater()
    }

    private fun nextSegment() {
        endSegment()
        Storage.cleanup(ctx)
        beginSegment()
    }

    private fun endSegment() {
        val cs = session; session = null
        val mr = recorder; recorder = null
        val f = file; file = null
        try { cs?.stopRepeating() } catch (_: Exception) {}
        try { cs?.abortCaptures() } catch (_: Exception) {}
        if (mr != null) {
            // stop() can throw after max duration (already stopped) or when no frames
            // were written; tiny files are removed below.
            try { mr.stop() } catch (_: Exception) {}
            try { mr.release() } catch (_: Exception) {}
        }
        try { cs?.close() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        if (f != null && f.exists() && f.length() < 1024) f.delete()
    }
}
