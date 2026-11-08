package com.firdaus.rekab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var driveBannerText: TextView
    private lateinit var diskServiceInfoText: TextView
    private lateinit var driveSpinnerContainer: LinearLayout
    private lateinit var camBox: LinearLayout
    private lateinit var urlText: TextView
    private lateinit var logText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    // Display mode switcher: LIVE vs PLAYBACK
    private var isPlaybackMode = false
    private lateinit var liveModeBtn: Button
    private lateinit var playbackModeBtn: Button
    private lateinit var livePanel: LinearLayout
    private lateinit var playbackPanel: LinearLayout
    private lateinit var liveCamSwitchRow: LinearLayout
    private lateinit var previewView: TextureView
    private lateinit var previewLabel: TextView

    // Playback UI views
    private lateinit var videoView: VideoView
    private lateinit var playbackStatusLabel: TextView
    private lateinit var clipsListBox: LinearLayout
    private var currentPlayingClip: ClipItem? = null

    private var cams: List<CamInfo> = emptyList()
    private var lastMountsSignature = ""

    // Live preview state
    private var activePreviewCam: CamInfo? = null
    private var pvDevice: CameraDevice? = null
    private var pvSession: CameraCaptureSession? = null
    private var pvSurface: Surface? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val diskMountListener: (List<ExternalUsbMount>) -> Unit = {
        ui.post {
            refreshExternalDriveUi(forceRebuild = true)
            if (isPlaybackMode) refreshPlaybackClips()
        }
    }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            ui.post {
                if (hasCam() && cams.none { it.id == cameraId }) {
                    AppLog.i("Car camera $cameraId connected — auto-detecting")
                    rescan(probeHidden = true)
                }
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            // Normal when opened for preview or recording
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        DiskManagerService.ensureStarted(this)
        setContentView(buildUi())
        askPermissions()
        if (prefs.phoneAccess) FileServer.start(this, prefs.port)
    }

    override fun onResume() {
        super.onResume()
        DiskManagerService.ensureStarted(this)
        DiskManagerService.addListener(diskMountListener)
        registerCameraCallback()
        refreshExternalDriveUi(forceRebuild = true)
        if (hasCam()) rescan(probeHidden = true)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        DiskManagerService.removeListener(diskMountListener)
        unregisterCameraCallback()
        stopPreview()
        stopPlaybackVideo()
    }

    private fun registerCameraCallback() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.registerAvailabilityCallback(cameraAvailabilityCallback, ui)
        } catch (_: Exception) {}
    }

    private fun unregisterCameraCallback() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Exception) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        root.addView(text("ReKab by firdaus", 26f, bold = true))
        root.addView(text("Rekam Kamera Bawaan — auto-detect car cameras · direct USB mount stream routing", 14f, color = "#94A3B8"))

        driveBannerText = text("", 14f, bold = true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val bannerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(driveBannerText)
        }
        root.addView(bannerWrap)

        statusText = text("", 15f, color = "#FACC15").also { it.setPadding(0, dp(6), 0, dp(6)) }
        root.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = button("● START RECORDING", "#DC2626") { startRecording() }
        stopBtn = button("■ STOP", "#334155") { RecorderService.stop(this); toast("Recording stopped") }
        val lockBtn = button("🔒 LOCK LAST CLIPS", "#2563EB") {
            if (!Storage.hasExternalDrive(this)) {
                toast("Plug in writable external USB drive first")
            } else {
                val n = Storage.lockLatest(this)
                toast("$n clip(s) moved to Locked on External USB")
                if (isPlaybackMode) refreshPlaybackClips()
            }
        }
        row.addView(startBtn, weighted())
        row.addView(stopBtn, weighted())
        row.addView(lockBtn, weighted())
        root.addView(row)

        // ------------------------------------------------------------------ Camera UI Display (Live <-> Playback)
        root.addView(header("Camera Monitor Display (Live / Playback)"))
        root.addView(text("Switch display between Live Car Camera View and Recorded Clip Playback from External USB Mount.", 13f, color = "#94A3B8"))

        val modeSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }
        liveModeBtn = button("🔴 LIVE CAMERA VIEW", "#2563EB") { switchDisplayMode(playback = false) }
        playbackModeBtn = button("▶ CLIP PLAYBACK", "#1E293B") { switchDisplayMode(playback = true) }
        modeSwitchRow.addView(liveModeBtn, weighted())
        modeSwitchRow.addView(playbackModeBtn, weighted())
        root.addView(modeSwitchRow)

        // LIVE PANEL
        livePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        liveCamSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        livePanel.addView(liveCamSwitchRow)
        previewLabel = text("Select a camera below or tap a camera button to view live feed.", 13f, color = "#38BDF8")
        livePanel.addView(previewLabel)
        previewView = TextureView(this).apply {
            visibility = View.GONE
        }
        livePanel.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply {
            topMargin = dp(6)
        })
        root.addView(livePanel)

        // PLAYBACK PANEL
        playbackPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        playbackStatusLabel = text("Select a recorded clip from the External USB Mount to play.", 13f, color = "#38BDF8")
        playbackPanel.addView(playbackStatusLabel)

        val videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        videoView = VideoView(this).apply {
            val mc = MediaController(this@MainActivity)
            mc.setAnchorView(this)
            setMediaController(mc)
            setOnCompletionListener {
                playbackStatusLabel.text = "Playback finished: ${currentPlayingClip?.file?.name ?: ""}"
            }
            setOnErrorListener { _, what, extra ->
                playbackStatusLabel.text = "Cannot play clip (codec/error $what/$extra)"
                true
            }
        }
        videoContainer.addView(
            videoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.CENTER)
        )
        playbackPanel.addView(videoContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        val playbackCtrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playbackCtrlRow.addView(button("⏯ Play / Pause", "#334155") {
            val clip = currentPlayingClip
            if (clip == null) {
                toast("Tap a clip below to play")
            } else if (videoView.isPlaying) {
                videoView.pause()
                playbackStatusLabel.text = "Paused: ${clip.file.name}"
            } else {
                videoView.start()
                playbackStatusLabel.text = "Playing: ${clip.file.name}"
            }
        }, weighted())
        playbackCtrlRow.addView(button("🔒 Lock Clip", "#2563EB") {
            lockCurrentPlaybackClip()
        }, weighted())
        playbackCtrlRow.addView(button("🗑 Delete Clip", "#7F1D1D") {
            deleteCurrentPlaybackClip()
        }, weighted())
        playbackCtrlRow.addView(button("↻ Refresh Clips", "#334155") {
            refreshPlaybackClips()
        }, weighted())
        playbackPanel.addView(playbackCtrlRow)

        clipsListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        playbackPanel.addView(clipsListBox)
        root.addView(playbackPanel)

        // ------------------------------------------------------------------ Disk Management Service
        root.addView(header("Disk Management Service (USB Mount & Write Validation)"))
        root.addView(text("Monitors connected USB drives, verifies block-level write permissions (fsync probe), and routes camera MediaRecorder file descriptors directly to the external USB mount point.", 13f, color = "#94A3B8"))

        diskServiceInfoText = text("", 13f, color = "#E2E8F0").apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(diskServiceInfoText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        driveSpinnerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(driveSpinnerContainer)

        val diskBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        diskBtnRow.addView(button("✓ Validate Write Permissions & Probe USB", "#2563EB") {
            val mounts = DiskManagerService.triggerProbe(this)
            refreshExternalDriveUi(forceRebuild = true)
            val active = DiskManagerService.activeMount(this)
            if (active != null && active.writableVerified) {
                toast("Write probe OK (${active.probeLatencyMs} ms) on ${active.mountPoint.absolutePath}")
            } else {
                toast("No writable external USB drive (${mounts.size} mount(s) found)")
            }
        }, weighted())
        diskBtnRow.addView(button("↻ Rescan USB Mounts", "#334155") {
            DiskManagerService.triggerProbe(this)
            refreshExternalDriveUi(forceRebuild = true)
        }, weighted())
        root.addView(diskBtnRow)

        // ------------------------------------------------------------------ Auto-detected Car Cameras
        root.addView(header("Auto-Detected Car Cameras"))
        root.addView(text("Car cameras (Front, Rear, Left/Right 360° AVM, and hidden OEM IDs) are detected automatically.", 13f, color = "#94A3B8"))
        camBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(camBox)
        root.addView(
            button("↻ Auto-Detect Car Cameras Now (Scan IDs 0–31)", "#334155") { rescan(true) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )

        // ------------------------------------------------------------------ Recording Settings
        root.addView(header("Stream & Loop Recording Settings"))
        root.addView(spinnerRow("Video quality", listOf("480p", "720p", "1080p", "Max"), listOf(480, 720, 1080, 0),
            prefs.maxHeight) { prefs.maxHeight = it })
        root.addView(spinnerRow("Clip length", listOf("1 min", "2 min", "3 min", "5 min", "10 min"), listOf(1, 2, 3, 5, 10),
            prefs.segmentMinutes) { prefs.segmentMinutes = it })
        root.addView(spinnerRow("Loop limit (oldest deleted)", listOf("4 GB", "8 GB", "16 GB", "32 GB", "64 GB", "128 GB", "256 GB", "512 GB"),
            listOf(4, 8, 16, 32, 64, 128, 256, 512), prefs.maxStorageGb) { prefs.maxStorageGb = it })
        root.addView(check("Record sound from microphone", prefs.recordAudio) { prefs.recordAudio = it })
        root.addView(check("Start recording automatically when the car turns on (requires writable USB mount)", prefs.autoStartOnBoot) { prefs.autoStartOnBoot = it })

        root.addView(header("Save to phone (Wi-Fi Server)"))
        root.addView(text("Connect the head unit to your phone's Wi-Fi hotspot, then open one of these addresses in the phone's browser to play or download clips from the external USB mount:", 13f, color = "#94A3B8"))
        root.addView(check("Allow phone access over Wi-Fi", prefs.phoneAccess) {
            prefs.phoneAccess = it
            if (it) FileServer.start(this, prefs.port) else FileServer.stop()
        })
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        portRow.addView(text("Port ", 15f))
        val portEdit = EditText(this).apply {
            setText(prefs.port.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE)
        }
        portRow.addView(portEdit, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        portRow.addView(button("Apply", "#334155") {
            val p = portEdit.text.toString().toIntOrNull()
            if (p != null && p in 1024..65535) {
                prefs.port = p
                if (prefs.phoneAccess) FileServer.start(this, p)
            } else toast("Port must be 1024–65535")
        })
        root.addView(portRow)
        urlText = text("", 20f, bold = true, color = "#38BDF8").also { it.setTextIsSelectable(true) }
        root.addView(urlText)

        root.addView(header("Log"))
        logText = text("", 12f, color = "#CBD5E1").apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(logText)
        return scroll
    }

    private fun switchDisplayMode(playback: Boolean) {
        isPlaybackMode = playback
        if (playback) {
            stopPreview()
            liveModeBtn.setBackgroundColor(Color.parseColor("#1E293B"))
            playbackModeBtn.setBackgroundColor(Color.parseColor("#2563EB"))
            livePanel.visibility = View.GONE
            playbackPanel.visibility = View.VISIBLE
            refreshPlaybackClips()
        } else {
            stopPlaybackVideo()
            liveModeBtn.setBackgroundColor(Color.parseColor("#2563EB"))
            playbackModeBtn.setBackgroundColor(Color.parseColor("#1E293B"))
            playbackPanel.visibility = View.GONE
            livePanel.visibility = View.VISIBLE
            val targetCam = activePreviewCam ?: cams.firstOrNull()
            if (targetCam != null && !RecorderService.running) {
                startPreview(targetCam)
            }
        }
    }

    private fun refreshPlaybackClips() {
        clipsListBox.removeAllViews()
        if (!Storage.hasExternalDrive(this)) {
            playbackStatusLabel.text = "⚠️ Plug in a writable External USB Drive to browse and play recorded clips."
            clipsListBox.addView(text("No writable external USB mount detected. Recording and playback use external USB storage only.", 14f, color = "#F87171"))
            return
        }
        val clips = Storage.allClips(this)
        if (clips.isEmpty()) {
            playbackStatusLabel.text = "No recorded clips found on External USB Mount yet."
            clipsListBox.addView(text("Start recording to stream clips directly onto the external USB mount.", 14f, color = "#94A3B8"))
            return
        }

        val df = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)
        clipsListBox.addView(text("Recorded Clips on External USB Mount (${clips.size}):", 14f, bold = true, color = "#E2E8F0"))

        for (item in clips.take(30)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(
                    if (currentPlayingClip?.file?.absolutePath == item.file.absolutePath)
                        Color.parseColor("#1E3A8A")
                    else
                        Color.parseColor("#0F172A")
                )
            }
            val mb = String.format(Locale.US, "%.1f MB", item.file.length() / 1048576.0)
            val lockIcon = if (item.locked) "🔒 " else ""
            val infoText = text(
                "$lockIcon${item.file.name}\nCam ${item.cameraId} · ${df.format(Date(item.file.lastModified()))} · $mb",
                13f,
                color = "#F8FAFC"
            )
            row.addView(infoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("▶ Play", "#2563EB") {
                playClipInMonitor(item)
            })
            clipsListBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }

        if (currentPlayingClip == null && clips.isNotEmpty()) {
            playClipInMonitor(clips.first())
        }
    }

    private fun playClipInMonitor(item: ClipItem) {
        if (!item.file.exists()) {
            toast("Clip file no longer exists")
            refreshPlaybackClips()
            return
        }
        currentPlayingClip = item
        try {
            videoView.setVideoURI(Uri.fromFile(item.file))
            videoView.requestFocus()
            videoView.start()
            val lockTag = if (item.locked) " [LOCKED]" else ""
            playbackStatusLabel.text = "▶ Playing: ${item.file.name} (Cam ${item.cameraId})$lockTag"
            AppLog.i("Playback started: ${item.file.name}")
            refreshPlaybackClips()
        } catch (e: Exception) {
            AppLog.e("Playback failed for ${item.file.name}", e)
            playbackStatusLabel.text = "Error playing ${item.file.name}: ${e.message}"
        }
    }

    private fun stopPlaybackVideo() {
        try {
            if (this::videoView.isInitialized && videoView.isPlaying) {
                videoView.stopPlayback()
            }
        } catch (_: Exception) {}
    }

    private fun lockCurrentPlaybackClip() {
        val clip = currentPlayingClip
        if (clip == null) {
            toast("Select a clip first")
            return
        }
        if (clip.locked) {
            toast("Clip is already locked")
            return
        }
        val lockedFolder = Storage.lockedDir(this) ?: return
        val dest = File(lockedFolder, clip.file.name)
        stopPlaybackVideo()
        if (clip.file.renameTo(dest)) {
            AppLog.i("Locked clip from playback: ${dest.name}")
            toast("Clip moved to Locked folder")
            currentPlayingClip = ClipItem(dest, clip.cameraId, true)
            playClipInMonitor(currentPlayingClip!!)
        } else {
            toast("Could not lock clip")
        }
    }

    private fun deleteCurrentPlaybackClip() {
        val clip = currentPlayingClip
        if (clip == null) {
            toast("Select a clip first")
            return
        }
        stopPlaybackVideo()
        val name = clip.file.name
        if (clip.file.delete()) {
            AppLog.i("Deleted clip from playback: $name")
            toast("Deleted $name")
            currentPlayingClip = null
            refreshPlaybackClips()
        } else {
            toast("Could not delete clip")
        }
    }

    private fun refreshExternalDriveUi(forceRebuild: Boolean = false) {
        val mounts = Storage.externalMounts(this)
        val active = DiskManagerService.activeMount(this)
        val sig = mounts.joinToString("|") { "${it.mountPoint.absolutePath}:${it.writableVerified}:${it.freeBytes / (1024 * 1024)}" }

        if (active == null) {
            driveBannerText.text = "⚠️ EXTERNAL USB DRIVE REQUIRED — DiskManagerService: No connected USB drive. Car HU internal storage is disabled."
            driveBannerText.setBackgroundColor(Color.parseColor("#7F1D1D"))
            driveBannerText.setTextColor(Color.parseColor("#FECACA"))
            diskServiceInfoText.text = listOf(
                "DiskManagerService : ${if (DiskManagerService.serviceRunning) "RUNNING (Monitoring USB & /proc/mounts)" else "STARTING"}",
                "Connected USB Drive: NONE DETECTED",
                "Write Permission   : UNVERIFIED (Plug in external USB HDD/SSD)",
                "Stream Routing     : BLOCKED (Car Head Unit internal storage is strictly disabled)"
            ).joinToString("\n")
        } else if (!active.isReadyForStream) {
            driveBannerText.text = "⚠️ USB DRIVE WRITE CHECK FAILED — ${active.validationDetail}"
            driveBannerText.setBackgroundColor(Color.parseColor("#7F1D1D"))
            driveBannerText.setTextColor(Color.parseColor("#FECACA"))
            diskServiceInfoText.text = listOf(
                "DiskManagerService : RUNNING",
                "External Mount Pt  : ${active.mountPoint.absolutePath} (${active.label} · ${active.fsType})",
                "Write Permission   : ❌ FAILED (${active.validationDetail})",
                "Capacity           : ${Storage.gb(active.freeBytes)} free / ${Storage.gb(active.totalBytes)} total",
                "Stream Routing     : BLOCKED until write permissions & free space are available"
            ).joinToString("\n")
        } else {
            driveBannerText.text = "✅ External USB Mount Verified — Direct Stream Routing Active (${Storage.gb(active.freeBytes)} free)"
            driveBannerText.setBackgroundColor(Color.parseColor("#14532D"))
            driveBannerText.setTextColor(Color.parseColor("#BBF7D0"))
            diskServiceInfoText.text = listOf(
                "DiskManagerService : RUNNING (USB & Mount Health Verified)",
                "External Mount Pt  : ${active.mountPoint.absolutePath} [${active.fsType} · ${active.mountOptions}]",
                "Write Permission   : ✅ VERIFIED (atomic fsync probe ${active.probeLatencyMs} ms)",
                "Direct Stream Path : ${active.streamRootDir.absolutePath}/cam_<ID>/ReKab_cam<ID>_<TS>.mp4",
                "Capacity           : ${Storage.gb(active.freeBytes)} free of ${Storage.gb(active.totalBytes)}"
            ).joinToString("\n")
        }

        if (!forceRebuild && sig == lastMountsSignature) return
        lastMountsSignature = sig

        driveSpinnerContainer.removeAllViews()
        if (mounts.isEmpty()) {
            driveSpinnerContainer.addView(
                text("Target USB Mount: No External USB Hard Drive plugged in (Car HU internal storage disabled)", 14f, color = "#F87171")
            )
        } else {
            driveSpinnerContainer.addView(
                spinnerRow(
                    "Active USB Mount Point",
                    mounts.map { Storage.mountLabel(it) },
                    mounts.indices.toList(),
                    prefs.storageIndex.coerceIn(0, mounts.size - 1)
                ) {
                    prefs.storageIndex = it
                    refreshExternalDriveUi(forceRebuild = true)
                }
            )
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: String = "#F8FAFC") = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = text(s, 18f, bold = true).also { it.setPadding(0, dp(20), 0, dp(4)) }

    private fun button(s: String, bg: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(bg)); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun check(s: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = s; isChecked = value; setTextColor(Color.WHITE); textSize = 15f
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun <T> spinnerRow(label: String, names: List<String>, values: List<T>, current: T, onPick: (T) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 15f), LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sp.setSelection(values.indexOf(current).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                (v as? TextView)?.setTextColor(Color.WHITE)
                onPick(values[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ cameras

    private fun rescan(probeHidden: Boolean) {
        cams = CameraProbe.scan(this, probeHidden)
        camBox.removeAllViews()
        liveCamSwitchRow.removeAllViews()

        if (cams.isEmpty()) {
            camBox.addView(text("No cameras are visible to apps on this head unit yet. Waiting for car camera bus…", 15f, color = "#F87171"))
            return
        }

        val sel = prefs.selectedCameras.toMutableSet()
        for (c in cams) {
            val switchBtn = button("Cam ${c.id}: ${c.role}", "#334155") {
                if (isPlaybackMode) switchDisplayMode(playback = false)
                startPreview(c)
            }
            liveCamSwitchRow.addView(switchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = check(c.label(), c.id in sel) { on ->
                val s = prefs.selectedCameras.toMutableSet()
                if (on) s += c.id else s -= c.id
                prefs.selectedCameras = s
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Live View", "#2563EB") {
                if (isPlaybackMode) switchDisplayMode(playback = false)
                startPreview(c)
            })
            camBox.addView(row)
        }
    }

    private fun startRecording() {
        if (!hasCam()) { askPermissions(); return }
        DiskManagerService.triggerProbe(this)
        val activeMount = DiskManagerService.activeMount(this)
        if (activeMount == null || !activeMount.writableVerified) {
            val msg = activeMount?.validationDetail ?: "Please plug in a writable External USB Hard Drive! Recording to Car HU is disabled."
            toast(msg)
            AppLog.i("DiskManagerService blocked recording: $msg")
            refreshExternalDriveUi(forceRebuild = true)
            return
        }
        if (!activeMount.hasSufficientSpace) {
            toast("External USB drive has insufficient free space")
            return
        }
        if (prefs.selectedCameras.isEmpty()) {
            toast("Tick at least one car camera first")
            return
        }
        stopPreview()
        RecorderService.start(this)
        toast("Direct stream recording started → ${activeMount.mountPoint.absolutePath}")
    }

    @SuppressLint("MissingPermission")
    private fun startPreview(c: CamInfo) {
        activePreviewCam = c
        if (RecorderService.running) {
            toast("Stop recording first to open live camera preview, or switch to Playback")
            previewLabel.text = "Camera ${c.id} (${c.role}) is currently streaming directly to External USB."
            return
        }
        if (!hasCam()) { askPermissions(); return }
        stopPreview()
        previewView.visibility = View.VISIBLE
        previewLabel.text = "Live View: Cam ${c.id} (${c.role}) — opening…"
        val go = { st: SurfaceTexture ->
            val size = c.pickPreviewSize()
            if (size != null) st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            pvSurface = surface
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            try {
                cm.openCamera(c.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        pvDevice = d
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                pvSession = s
                                try {
                                    val r = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                                    s.setRepeatingRequest(r.build(), null, null)
                                    previewLabel.text = "🔴 LIVE: Cam ${c.id} (${c.role}) · ${size?.width}x${size?.height}"
                                } catch (e: Exception) { AppLog.e("Preview ${c.id}", e) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                previewLabel.text = "Live View: Cam ${c.id} failed to configure"
                            }
                        }, ui)
                    }
                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        previewLabel.text = "Live View: Cam ${c.id} taken by car system"
                    }
                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        previewLabel.text = "Live View: Cam ${c.id} error $error"
                        AppLog.i("Preview camera ${c.id}: error $error")
                    }
                }, ui)
            } catch (e: Exception) {
                AppLog.e("Preview ${c.id}", e)
                previewLabel.text = "Live View: Cam ${c.id} cannot open (${e.message})"
            }
            Unit
        }
        val st = previewView.surfaceTexture
        if (st != null) {
            go(st)
        } else {
            previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { go(s) }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    private fun stopPreview() {
        try { pvSession?.close() } catch (_: Exception) {}
        try { pvDevice?.close() } catch (_: Exception) {}
        try { pvSurface?.release() } catch (_: Exception) {}
        pvSession = null; pvDevice = null; pvSurface = null
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus() {
        refreshExternalDriveUi(forceRebuild = false)
        val activeMount = DiskManagerService.activeMount(this)
        val lines = mutableListOf(RecorderService.summary())
        lines += RecorderService.statusLines()
        if (activeMount == null) {
            lines += "DiskManagerService: ⚠️ NO EXTERNAL USB DRIVE CONNECTED (Car HU storage disabled)"
        } else if (!activeMount.writableVerified) {
            lines += "DiskManagerService: ❌ USB MOUNT WRITE DENIED (${activeMount.mountPoint.absolutePath})"
        } else {
            lines += "Direct Stream Target: ${activeMount.streamRootDir.absolutePath}  (Mount ${activeMount.mountPoint.absolutePath} · ${Storage.gb(activeMount.freeBytes)} free)"
        }
        statusText.text = lines.joinToString("\n")
        val canStart = !RecorderService.running && activeMount?.isReadyForStream == true
        startBtn.alpha = if (canStart) 1f else 0.5f

        val urls = FileServer.urls()
        urlText.text = when {
            !prefs.phoneAccess -> "Phone access is off"
            urls.isEmpty() -> "Not connected to Wi-Fi yet"
            else -> urls.joinToString("\n")
        }
        logText.text = AppLog.text()
    }

    // ------------------------------------------------------------------ permissions

    private fun hasCam() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCam()) rescan(true) else AppLog.i("Camera permission denied")
    }

    override fun onDestroy() {
        stopPreview()
        stopPlaybackVideo()
        super.onDestroy()
    }
}
