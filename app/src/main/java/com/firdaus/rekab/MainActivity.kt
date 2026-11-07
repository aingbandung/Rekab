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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.InputType

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var camBox: LinearLayout
    private lateinit var urlText: TextView
    private lateinit var logText: TextView
    private lateinit var previewView: TextureView
    private lateinit var previewLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private var cams: List<CamInfo> = emptyList()

    // preview
    private var pvDevice: CameraDevice? = null
    private var pvSession: CameraCaptureSession? = null
    private var pvSurface: Surface? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        askPermissions()
        if (prefs.phoneAccess) FileServer.start(this, prefs.port)
    }

    override fun onResume() {
        super.onResume()
        if (hasCam()) rescan(probeHidden = true)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        stopPreview()
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
        val scroll = ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#0F172A")) }

        root.addView(text("ReKab by firdaus", 26f, bold = true))
        root.addView(text("Rekam Kamera Bawaan — dashcam from the car's own cameras", 14f, color = "#94A3B8"))

        statusText = text("", 16f, color = "#FACC15").also { it.setPadding(0, dp(10), 0, dp(6)) }
        root.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = button("● START RECORDING", "#DC2626") { startRecording() }
        stopBtn = button("■ STOP", "#334155") { RecorderService.stop(this); toast("Recording stopped") }
        val lockBtn = button("🔒 LOCK LAST CLIPS", "#2563EB") {
            val n = Storage.lockLatest(this); toast("$n clip(s) moved to Locked")
        }
        row.addView(startBtn, weighted()); row.addView(stopBtn, weighted()); row.addView(lockBtn, weighted())
        root.addView(row)

        root.addView(header("Cameras to record"))
        root.addView(text("Tick the cameras to record. Use Preview to see which is which.", 13f, color = "#94A3B8"))
        camBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(camBox)
        root.addView(button("↻ Rescan cameras (including hidden IDs)", "#334155") { rescan(true) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        previewLabel = text("", 13f, color = "#94A3B8")
        previewView = TextureView(this).apply { visibility = View.GONE }
        root.addView(previewLabel)
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        root.addView(header("Recording settings"))
        root.addView(spinnerRow("Video quality", listOf("480p", "720p", "1080p", "Max"), listOf(480, 720, 1080, 0),
            prefs.maxHeight) { prefs.maxHeight = it })
        root.addView(spinnerRow("Clip length", listOf("1 min", "2 min", "3 min", "5 min", "10 min"), listOf(1, 2, 3, 5, 10),
            prefs.segmentMinutes) { prefs.segmentMinutes = it })
        root.addView(spinnerRow("Loop limit (oldest deleted)", listOf("4 GB", "8 GB", "16 GB", "32 GB", "64 GB", "128 GB"),
            listOf(4, 8, 16, 32, 64, 128), prefs.maxStorageGb) { prefs.maxStorageGb = it })
        val roots = Storage.roots(this)
        root.addView(spinnerRow("Save to", roots.mapIndexed { i, f -> Storage.rootLabel(this, i, f) }, roots.indices.toList(),
            prefs.storageIndex) { prefs.storageIndex = it })
        root.addView(check("Record sound from microphone", prefs.recordAudio) { prefs.recordAudio = it })
        root.addView(check("Start recording automatically when the car turns on", prefs.autoStartOnBoot) { prefs.autoStartOnBoot = it })

        root.addView(header("Save to phone"))
        root.addView(text("Connect the head unit to your phone's Wi-Fi hotspot, then open one of these addresses " +
            "in the phone's browser to play or download clips:", 13f, color = "#94A3B8"))
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

    private fun text(s: String, size: Float, bold: Boolean = false, color: String = "#F8FAFC") = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = text(s, 18f, bold = true).also { it.setPadding(0, dp(20), 0, dp(4)) }

    private fun button(s: String, bg: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(bg)); textSize = 15f
        setPadding(dp(14), dp(12), dp(14), dp(12))
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
        row.addView(text(label, 15f), LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
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
        if (cams.isEmpty()) {
            camBox.addView(text("No cameras are visible to apps on this head unit. See the log below; " +
                "the car may only give its cameras to its own system apps.", 15f, color = "#F87171"))
            return
        }
        val sel = prefs.selectedCameras.toMutableSet()
        for (c in cams) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = check(c.label(), c.id in sel) { on ->
                val s = prefs.selectedCameras.toMutableSet()
                if (on) s += c.id else s -= c.id
                prefs.selectedCameras = s
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Preview", "#334155") { startPreview(c) })
            camBox.addView(row)
        }
    }

    private fun startRecording() {
        if (!hasCam()) { askPermissions(); return }
        if (prefs.selectedCameras.isEmpty()) { toast("Tick at least one camera first"); return }
        stopPreview()
        RecorderService.start(this)
        toast("Recording started")
    }

    @SuppressLint("MissingPermission")
    private fun startPreview(c: CamInfo) {
        if (RecorderService.running) { toast("Stop recording first — a camera can only be used once"); return }
        if (!hasCam()) { askPermissions(); return }
        stopPreview()
        previewView.visibility = View.VISIBLE
        previewLabel.text = "Preview: camera ${c.id} (opening…)"
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
                                    previewLabel.text = "Preview: camera ${c.id}  ${size?.width}x${size?.height}  (tap Preview on another camera to switch)"
                                } catch (e: Exception) { AppLog.e("Preview ${c.id}", e) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                previewLabel.text = "Preview: camera ${c.id} failed to configure"
                            }
                        }, ui)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); previewLabel.text = "Preview: camera ${c.id} taken by system" }
                    override fun onError(d: CameraDevice, error: Int) {
                        d.close(); previewLabel.text = "Preview: camera ${c.id} error $error"
                        AppLog.i("Preview camera ${c.id}: error $error")
                    }
                }, ui)
            } catch (e: Exception) {
                AppLog.e("Preview ${c.id}", e)
                previewLabel.text = "Preview: camera ${c.id} cannot open (${e.message})"
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
        val lines = mutableListOf(RecorderService.summary())
        lines += RecorderService.statusLines()
        lines += "Saving to: ${Storage.base(this).absolutePath}  (free ${Storage.gb(Storage.freeBytes(Storage.base(this)))})"
        statusText.text = lines.joinToString("\n")
        startBtn.alpha = if (RecorderService.running) 0.5f else 1f
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
        super.onDestroy()
    }
}
