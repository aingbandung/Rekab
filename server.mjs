import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.PORT_WEB || process.env.DEFAULT_APP_PORT || 3000);

const HTML = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>ReKab by firdaus</title>
  <meta name="description" content="Rekam Kamera Bawaan — dashcam from the car's own cameras with multi-camera loop recording and Wi-Fi clip server.">
  <meta property="og:title" content="ReKab by firdaus">
  <meta property="og:description" content="Rekam Kamera Bawaan — dashcam from the car's own cameras with multi-camera loop recording and Wi-Fi clip server.">
  <style>
    * { box-sizing: border-box; }
    body {
      margin: 0;
      padding: 12px 16px 36px;
      background: #0F172A;
      color: #F8FAFC;
      font-family: Roboto, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      line-height: 1.45;
    }
    .container {
      max-width: 960px;
      margin: 0 auto;
    }
    .top-bar {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      margin: 0;
      font-size: 26px;
      font-weight: 700;
      color: #F8FAFC;
    }
    .subtitle {
      font-size: 14px;
      color: #94A3B8;
      margin-top: 2px;
    }
    .top-actions {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .apk-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: #1E293B;
      border: 1px solid #334155;
      color: #38BDF8;
      text-decoration: none;
      font-size: 13px;
      font-weight: 600;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .apk-badge:hover {
      background: #334155;
    }
    .drive-banner {
      margin-top: 10px;
      padding: 10px 14px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 700;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }
    .drive-banner.ready {
      background: #14532D;
      color: #BBF7D0;
      border: 1px solid #22C55E;
    }
    .drive-banner.missing {
      background: #7F1D1D;
      color: #FECACA;
      border: 1px solid #EF4444;
    }
    .sim-btns {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    }
    .status-box {
      padding: 8px 0 6px;
      font-size: 15px;
      color: #FACC15;
      white-space: pre-line;
    }
    .btn-row {
      display: flex;
      gap: 8px;
      margin-top: 4px;
      flex-wrap: wrap;
    }
    .btn-row button {
      flex: 1;
      min-width: 160px;
    }
    button {
      border: none;
      color: #FFFFFF;
      background: #334155;
      font-size: 14px;
      font-weight: 600;
      padding: 10px 14px;
      cursor: pointer;
      border-radius: 4px;
      transition: filter 0.15s, opacity 0.15s, background 0.15s;
    }
    button:hover {
      filter: brightness(1.1);
    }
    button:active {
      filter: brightness(0.95);
    }
    .btn-start { background: #DC2626; }
    .btn-stop { background: #334155; }
    .btn-lock { background: #2563EB; }
    .section-header {
      font-size: 18px;
      font-weight: 700;
      padding: 20px 0 4px;
      color: #F8FAFC;
    }
    .muted {
      font-size: 13px;
      color: #94A3B8;
      margin-bottom: 6px;
    }
    .mode-switch-row {
      display: flex;
      gap: 8px;
      margin: 6px 0 8px;
    }
    .mode-switch-row button {
      flex: 1;
      padding: 12px;
      font-size: 15px;
    }
    .monitor-panel {
      background: #1E293B;
      border: 1px solid #334155;
      border-radius: 6px;
      padding: 12px;
    }
    .cam-pills {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
      margin-bottom: 8px;
    }
    .cam-pill {
      padding: 7px 12px;
      font-size: 13px;
      border-radius: 4px;
      background: #334155;
    }
    .cam-pill.active {
      background: #2563EB;
    }
    .monitor-status {
      font-size: 13px;
      color: #38BDF8;
      margin-bottom: 6px;
      font-weight: 600;
    }
    .viewport-box {
      position: relative;
      width: 100%;
      height: 320px;
      background: #020617;
      border: 1px solid #0F172A;
      border-radius: 4px;
      overflow: hidden;
    }
    .viewport-box canvas,
    .viewport-box video {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
    }
    .playback-controls {
      display: flex;
      gap: 8px;
      margin-top: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    .playback-controls button {
      flex: 1;
      min-width: 120px;
    }
    .scrub-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-top: 8px;
      font-size: 12px;
      color: #CBD5E1;
      font-family: monospace;
    }
    .scrub-row input[type="range"] {
      flex: 1;
      accent-color: #2563EB;
      cursor: pointer;
    }
    .disk-telemetry-box {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 13px;
      color: #E2E8F0;
      background: #1E293B;
      border: 1px solid #334155;
      border-radius: 6px;
      padding: 12px;
      white-space: pre-wrap;
      margin: 6px 0 10px;
    }
    .cam-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin: 8px 0;
    }
    .cam-row {
      display: flex;
      align-items: center;
      gap: 8px;
      background: rgba(30, 41, 59, 0.65);
      padding: 8px 10px;
      border-radius: 4px;
    }
    .cam-row label {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 14px;
      cursor: pointer;
      color: #FFFFFF;
    }
    input[type="checkbox"] {
      width: 18px;
      height: 18px;
      accent-color: #2563EB;
      cursor: pointer;
    }
    .setting-row {
      display: flex;
      align-items: center;
      padding: 6px 0;
      gap: 12px;
      max-width: 760px;
    }
    .setting-label {
      width: 240px;
      font-size: 15px;
      color: #F8FAFC;
      flex-shrink: 0;
    }
    select, input[type="number"] {
      flex: 1;
      background: #1E293B;
      color: #FFFFFF;
      border: 1px solid #334155;
      padding: 8px 10px;
      font-size: 14px;
      border-radius: 4px;
    }
    .check-row {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 6px 0;
      font-size: 15px;
      color: #FFFFFF;
      cursor: pointer;
    }
    .port-row {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 6px 0;
    }
    .port-row input {
      width: 120px;
      flex: none;
    }
    .url-text {
      font-size: 19px;
      font-weight: 700;
      color: #38BDF8;
      margin: 8px 0;
      white-space: pre-line;
      user-select: all;
    }
    .clip-list-box {
      margin-top: 10px;
      max-height: 220px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .clip-card {
      padding: 8px 12px;
      background: #0F172A;
      border: 1px solid #334155;
      border-radius: 6px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
    }
    .clip-card.playing {
      background: #1E3A8A;
      border-color: #38BDF8;
    }
    .clip-name {
      font-size: 13px;
      word-break: break-all;
      font-weight: 600;
    }
    .clip-meta {
      color: #94A3B8;
      font-size: 12px;
      margin-top: 2px;
    }
    .clip-actions {
      display: flex;
      gap: 6px;
    }
    .clip-actions button {
      padding: 6px 10px;
      font-size: 12px;
      border-radius: 4px;
    }
    .log-box {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      color: #CBD5E1;
      background: #020617;
      border: 1px solid #1E293B;
      border-radius: 4px;
      padding: 10px;
      white-space: pre-wrap;
      max-height: 220px;
      overflow-y: auto;
      user-select: text;
    }
    .toast {
      position: fixed;
      bottom: 20px;
      left: 50%;
      transform: translateX(-50%);
      background: #1E293B;
      color: #F8FAFC;
      border: 1px solid #475569;
      padding: 10px 18px;
      border-radius: 999px;
      font-size: 14px;
      box-shadow: 0 8px 24px rgba(0,0,0,0.5);
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.2s;
      z-index: 100;
    }
    .toast.show {
      opacity: 1;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="top-bar">
      <div>
        <h1>ReKab by firdaus</h1>
        <div class="subtitle">Rekam Kamera Bawaan — auto-detect car cameras · direct USB mount stream routing</div>
      </div>
      <div class="top-actions">
        <a class="apk-badge" href="/download-apk" download="ReKab-debug.apk">⬇ Download Android APK</a>
      </div>
    </div>

    <div id="driveBanner" class="drive-banner ready">
      <span id="driveBannerText">✅ External USB Mount Verified — Direct Stream Routing Active (465.2 GB free)</span>
      <div class="sim-btns">
        <button id="toggleRoBtn" onclick="toggleMountReadOnly()" style="background:#0F172A;border:1px solid #475569;font-size:12px;padding:6px 10px">🔒 Simulate Read-Only Mount</button>
        <button id="toggleUsbBtn" onclick="toggleExternalDrive()" style="background:#0F172A;border:1px solid #475569;font-size:12px;padding:6px 10px">🔌 Simulate Unplug USB HDD</button>
      </div>
    </div>

    <div id="statusText" class="status-box">Not recording</div>

    <div class="btn-row">
      <button id="startBtn" class="btn-start" onclick="startRecording()">● START RECORDING</button>
      <button id="stopBtn" class="btn-stop" onclick="stopRecording()">■ STOP</button>
      <button id="lockBtn" class="btn-lock" onclick="lockLatestClips()">🔒 LOCK LAST CLIPS</button>
    </div>

    <!-- CAMERA MONITOR DISPLAY: LIVE <-> PLAYBACK -->
    <div class="section-header">Camera Monitor Display (Live / Playback)</div>
    <div class="muted">Switch display between Live Car Camera View and Recorded Clip Playback from External USB Mount.</div>

    <div class="mode-switch-row">
      <button id="liveModeBtn" style="background:#2563EB" onclick="switchDisplayMode(false)">🔴 LIVE CAMERA VIEW</button>
      <button id="playbackModeBtn" style="background:#1E293B" onclick="switchDisplayMode(true)">▶ CLIP PLAYBACK</button>
    </div>

    <!-- LIVE VIEW PANEL -->
    <div id="livePanel" class="monitor-panel">
      <div id="liveCamPills" class="cam-pills"></div>
      <div id="previewLabel" class="monitor-status">🔴 LIVE: Cam 0 (Front Dashcam) · 1920x1080</div>
      <div class="viewport-box">
        <video id="webcamVideo" autoplay playsinline muted style="display:none"></video>
        <canvas id="liveCanvas" width="854" height="480"></canvas>
      </div>
    </div>

    <!-- PLAYBACK PANEL -->
    <div id="playbackPanel" class="monitor-panel" style="display:none">
      <div id="playbackStatusLabel" class="monitor-status">Select a recorded clip from the External USB Mount to play.</div>
      <div class="viewport-box">
        <canvas id="playbackCanvas" width="854" height="480"></canvas>
      </div>
      <div class="scrub-row">
        <span id="playbackTimeCur">00:00</span>
        <input type="range" id="playbackScrub" min="0" max="180" value="0" oninput="scrubPlayback(Number(this.value))">
        <span id="playbackTimeMax">03:00</span>
      </div>
      <div class="playback-controls">
        <button onclick="togglePlaybackPause()">⏯ Play / Pause</button>
        <button style="background:#2563EB" onclick="lockCurrentPlaybackClip()">🔒 Lock Clip</button>
        <button style="background:#7F1D1D" onclick="deleteCurrentPlaybackClip()">🗑 Delete Clip</button>
        <button onclick="renderPlaybackClips()">↻ Refresh Clips</button>
      </div>
      <div id="playbackClipsList" class="clip-list-box"></div>
    </div>

    <!-- DISK MANAGEMENT SERVICE -->
    <div class="section-header">Disk Management Service (USB Mount & Write Validation)</div>
    <div class="muted">Monitors connected USB drives, verifies block-level write permissions (fsync probe), and routes camera MediaRecorder file descriptors directly to the external USB mount point.</div>

    <div id="diskServiceInfoText" class="disk-telemetry-box"></div>

    <div id="driveSelectContainer" class="setting-row"></div>

    <div class="btn-row" style="margin-top:6px">
      <button style="background:#2563EB" onclick="runWritePermissionProbe()">✓ Validate Write Permissions & Probe USB</button>
      <button onclick="rescanUsbMounts()">↻ Rescan USB Mounts</button>
    </div>

    <!-- AUTO-DETECTED CAR CAMERAS -->
    <div class="section-header">Auto-Detected Car Cameras</div>
    <div class="muted">Car cameras (Front, Rear, Left/Right 360° AVM, and hidden OEM IDs) are detected automatically.</div>
    <div id="camBox" class="cam-list"></div>
    <button onclick="rescanCameras(true)">↻ Auto-Detect Car Cameras Now (Scan IDs 0–31)</button>

    <!-- RECORDING SETTINGS -->
    <div class="section-header">Stream & Loop Recording Settings</div>
    <div class="setting-row">
      <div class="setting-label">Video quality</div>
      <select id="maxHeightSelect" onchange="updatePref('maxHeight', Number(this.value))">
        <option value="480">480p</option>
        <option value="720" selected>720p</option>
        <option value="1080">1080p</option>
        <option value="0">Max</option>
      </select>
    </div>
    <div class="setting-row">
      <div class="setting-label">Clip length</div>
      <select id="segmentSelect" onchange="updatePref('segmentMinutes', Number(this.value))">
        <option value="1">1 min</option>
        <option value="2">2 min</option>
        <option value="3" selected>3 min</option>
        <option value="5">5 min</option>
        <option value="10">10 min</option>
      </select>
    </div>
    <div class="setting-row">
      <div class="setting-label">Loop limit (oldest deleted)</div>
      <select id="maxGbSelect" onchange="updatePref('maxStorageGb', Number(this.value))">
        <option value="4">4 GB</option>
        <option value="8">8 GB</option>
        <option value="16" selected>16 GB</option>
        <option value="32">32 GB</option>
        <option value="64">64 GB</option>
        <option value="128">128 GB</option>
        <option value="256">256 GB</option>
        <option value="512">512 GB</option>
      </select>
    </div>

    <label class="check-row">
      <input type="checkbox" id="recordAudioCheck" onchange="updatePref('recordAudio', this.checked)">
      <span>Record sound from microphone</span>
    </label>
    <label class="check-row">
      <input type="checkbox" id="autoStartCheck" onchange="updatePref('autoStartOnBoot', this.checked)">
      <span>Start recording automatically when the car turns on (requires writable USB mount)</span>
    </label>

    <!-- SAVE TO PHONE -->
    <div class="section-header">Save to phone (Wi-Fi Server)</div>
    <div class="muted">Connect the head unit to your phone's Wi-Fi hotspot, then open one of these addresses in the phone's browser to play or download clips from the external USB mount:</div>
    <label class="check-row">
      <input type="checkbox" id="phoneAccessCheck" checked onchange="togglePhoneAccess(this.checked)">
      <span>Allow phone access over Wi-Fi</span>
    </label>
    <div class="port-row">
      <span style="font-size:15px">Port </span>
      <input type="number" id="portInput" value="8080" min="1024" max="65535">
      <button onclick="applyPort()">Apply</button>
    </div>
    <div id="urlText" class="url-text">http://192.168.43.1:8080   (wlan0)</div>

    <div class="section-header">Log</div>
    <div id="logText" class="log-box"></div>
  </div>

  <div id="toast" class="toast"></div>

  <script>
    const externalMounts = [
      {
        label: 'USB Drive (usb0)',
        mountPoint: '/mnt/media_rw/usb0',
        streamRootDir: '/mnt/media_rw/usb0/ReKab',
        fsType: 'exfat',
        mountOptions: 'rw,dirsync,nosuid,nodev,noexec,relatime',
        free: '465.2 GB',
        total: '500.0 GB',
        probeLatencyMs: 2
      },
      {
        label: 'External USB Storage (sda1)',
        mountPoint: '/storage/8A3F-1B02',
        streamRootDir: '/storage/8A3F-1B02/Android/data/com.firdaus.rekab/files/ReKab',
        fsType: 'vfat',
        mountOptions: 'rw,nosuid,nodev,noexec,relatime,fmask=0007',
        free: '931.5 GB',
        total: '1000.0 GB',
        probeLatencyMs: 3
      }
    ];

    const state = {
      externalDriveConnected: true,
      mountWritableVerified: true,
      isPlaybackMode: false,
      prefs: {
        selectedCameras: new Set(),
        autoDetectedOnce: false,
        maxHeight: 720,
        segmentMinutes: 3,
        maxStorageGb: 16,
        storageIndex: 0,
        recordAudio: false,
        autoStartOnBoot: false,
        phoneAccess: true,
        port: 8080,
      },
      running: false,
      activeRecorders: [],
      activeLiveCam: null,
      cameras: [],
      clips: [
        { id: 'c1', cam: '0', role: 'Front Dashcam', name: 'ReKab_cam0_20261107_161500.mp4', time: '07 Nov 16:15:00', mb: 44.8, locked: false, duration: 180 },
        { id: 'c2', cam: '1', role: 'Rear / Cabin Cam', name: 'ReKab_cam1_20261107_161500.mp4', time: '07 Nov 16:15:00', mb: 39.2, locked: false, duration: 180 },
        { id: 'c3', cam: '2', role: 'Left 360° Cam', name: 'ReKab_cam2_20261107_161200.mp4', time: '07 Nov 16:12:00', mb: 36.5, locked: true, duration: 180 },
      ],
      currentPlayingClip: null,
      playbackPlaying: true,
      playbackPosSec: 0,
      logs: []
    };

    function fmtTime(d = new Date()) {
      return d.toTimeString().slice(0, 8);
    }

    function fmtMMSS(sec) {
      const m = Math.floor(sec / 60);
      const s = Math.floor(sec % 60);
      return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
    }

    function fmtStamp(d = new Date()) {
      const p = n => String(n).padStart(2, '0');
      return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
    }

    function logInfo(msg) {
      state.logs.push(fmtTime() + '  ' + msg);
      if (state.logs.length > 200) state.logs.shift();
      renderStatus();
    }

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.textContent = msg;
      t.classList.add('show');
      clearTimeout(showToast._timer);
      showToast._timer = setTimeout(() => t.classList.remove('show'), 2500);
    }

    function getActiveMount() {
      if (!state.externalDriveConnected) return null;
      return externalMounts[state.prefs.storageIndex] || externalMounts[0];
    }

    function isMountReady() {
      return state.externalDriveConnected && state.mountWritableVerified;
    }

    function toggleExternalDrive() {
      state.externalDriveConnected = !state.externalDriveConnected;
      if (!state.externalDriveConnected) {
        logInfo('DiskManagerService event: USB_DEVICE_DETACHED / MEDIA_UNMOUNTED');
        if (state.running) {
          logInfo('DiskManagerService: External USB drive lost — stopping active recording');
          state.running = false;
          state.activeRecorders = [];
          showToast('External USB drive unplugged — recording stopped');
        } else {
          showToast('External USB Hard Drive unplugged');
        }
      } else {
        state.mountWritableVerified = true;
        const m = getActiveMount();
        logInfo('DiskManagerService event: USB_DEVICE_ATTACHED (' + m.mountPoint + ')');
        logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE OK (' + m.probeLatencyMs + 'ms) · Write/fsync verified on ' + m.streamRootDir);
        showToast('External USB Hard Drive mounted & write-verified');
      }
      renderDriveUi();
      renderStatus();
      if (state.isPlaybackMode) renderPlaybackClips();
    }

    function toggleMountReadOnly() {
      if (!state.externalDriveConnected) {
        showToast('Plug in External USB Drive first');
        return;
      }
      state.mountWritableVerified = !state.mountWritableVerified;
      const m = getActiveMount();
      if (!state.mountWritableVerified) {
        logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE FAILED · Write denied (IOException: Read-only file system EROFS)');
        if (state.running) {
          logInfo('DiskManagerService: External USB drive became read-only — stopping active recording');
          state.running = false;
          state.activeRecorders = [];
        }
        showToast('Simulated Read-Only USB Mount (Write Denied)');
      } else {
        logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE OK (' + m.probeLatencyMs + 'ms) · Write/fsync verified on ' + m.streamRootDir);
        showToast('Write Permissions Restored & Verified');
      }
      renderDriveUi();
      renderStatus();
    }

    function runWritePermissionProbe() {
      const m = getActiveMount();
      if (!m) {
        logInfo('DiskManager probe: No connected external USB drive (0 mounts found)');
        showToast('No connected external USB drive');
        return;
      }
      if (!state.mountWritableVerified) {
        logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE FAILED · Directory marked read-only');
        showToast('Write permission check FAILED on ' + m.mountPoint);
        return;
      }
      m.probeLatencyMs = Math.floor(Math.random() * 3) + 1;
      logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE OK (' + m.probeLatencyMs + 'ms) · Atomic write/fsync/readback verified on ' + m.streamRootDir);
      renderDriveUi();
      showToast('Write probe OK (' + m.probeLatencyMs + ' ms) on ' + m.mountPoint);
    }

    function rescanUsbMounts() {
      logInfo('DiskManagerService: Scanning StorageManager volumes & /proc/mounts...');
      runWritePermissionProbe();
    }

    function renderDriveUi() {
      const banner = document.getElementById('driveBanner');
      const text = document.getElementById('driveBannerText');
      const usbBtn = document.getElementById('toggleUsbBtn');
      const roBtn = document.getElementById('toggleRoBtn');
      const infoBox = document.getElementById('diskServiceInfoText');
      const selContainer = document.getElementById('driveSelectContainer');
      const m = getActiveMount();

      if (!m) {
        banner.className = 'drive-banner missing';
        text.textContent = '⚠️ EXTERNAL USB DRIVE REQUIRED — DiskManagerService: No connected USB drive. Car HU internal storage is disabled.';
        usbBtn.textContent = '🔌 Simulate Plug In USB HDD';
        roBtn.style.display = 'none';
        infoBox.textContent = [
          'DiskManagerService : RUNNING (Monitoring USB & /proc/mounts)',
          'Connected USB Drive: NONE DETECTED',
          'Write Permission   : UNVERIFIED (Plug in external USB HDD/SSD)',
          'Stream Routing     : BLOCKED (Car Head Unit internal storage is strictly disabled)'
        ].join('\\n');
        selContainer.innerHTML = '<div style="color:#F87171;font-size:14px;font-weight:600">Target USB Mount: No External USB Hard Drive plugged in (Car HU internal storage disabled)</div>';
      } else if (!state.mountWritableVerified) {
        banner.className = 'drive-banner missing';
        text.textContent = '⚠️ USB DRIVE WRITE CHECK FAILED — Write denied (Read-only file system on ' + m.mountPoint + ')';
        usbBtn.textContent = '🔌 Simulate Unplug USB HDD';
        roBtn.style.display = 'inline-block';
        roBtn.textContent = '🔓 Restore Write Permission';
        infoBox.textContent = [
          'DiskManagerService : RUNNING',
          'External Mount Pt  : ' + m.mountPoint + ' (' + m.label + ' · ' + m.fsType + ')',
          'Write Permission   : ❌ FAILED (Directory marked read-only / EROFS on ' + m.streamRootDir + ')',
          'Capacity           : ' + m.free + ' free / ' + m.total + ' total',
          'Stream Routing     : BLOCKED until write permissions & free space are available'
        ].join('\\n');
        selContainer.innerHTML = buildMountSelectHtml();
      } else {
        banner.className = 'drive-banner ready';
        text.textContent = '✅ External USB Mount Verified — Direct Stream Routing Active (' + m.free + ' free)';
        usbBtn.textContent = '🔌 Simulate Unplug USB HDD';
        roBtn.style.display = 'inline-block';
        roBtn.textContent = '🔒 Simulate Read-Only Mount';
        infoBox.textContent = [
          'DiskManagerService : RUNNING (USB & Mount Health Verified)',
          'External Mount Pt  : ' + m.mountPoint + ' [' + m.fsType + ' · rw]',
          'Write Permission   : ✅ VERIFIED (atomic fsync probe ' + m.probeLatencyMs + ' ms)',
          'Direct Stream Path : ' + m.streamRootDir + '/cam_<ID>/ReKab_cam<ID>_<TS>.mp4',
          'Capacity           : ' + m.free + ' free of ' + m.total
        ].join('\\n');
        selContainer.innerHTML = buildMountSelectHtml();
      }
    }

    function buildMountSelectHtml() {
      const s0 = state.prefs.storageIndex === 0 ? 'selected' : '';
      const s1 = state.prefs.storageIndex === 1 ? 'selected' : '';
      const st = state.mountWritableVerified ? 'WRITE OK' : 'READ-ONLY';
      return (
        '<div class="setting-label">Active USB Mount Point</div>' +
        '<select id="storageSelect" onchange="updatePref(\\'storageIndex\\', Number(this.value))">' +
        '<option value="0" ' + s0 + '>USB Drive (usb0) [exfat] · 465.2 GB/500.0 GB free · ' + st + ' · /mnt/media_rw/usb0</option>' +
        '<option value="1" ' + s1 + '>External USB Storage (sda1) [vfat] · 931.5 GB/1000.0 GB free · ' + st + ' · /storage/8A3F-1B02</option>' +
        '</select>'
      );
    }

    function rescanCameras(probeHidden = true) {
      const detected = [
        { id: '0', hidden: false, facing: 'back', role: 'Front Dashcam', level: 'FULL', maxSz: '1920x1080', phys: [] },
        { id: '1', hidden: false, facing: 'front', role: 'Rear / Cabin Cam', level: 'LIMITED', maxSz: '1280x720', phys: [] },
      ];
      if (probeHidden) {
        detected.push(
          { id: '2', hidden: true, facing: 'external', role: 'Left 360° Cam', level: 'LIMITED', maxSz: '1280x720', phys: [] },
          { id: '3', hidden: true, facing: 'external', role: 'Right 360° Cam', level: 'LIMITED', maxSz: '1280x720', phys: [] }
        );
        logInfo('Auto-detected hidden car camera ID 2 (Left 360° Cam)');
        logInfo('Auto-detected hidden car camera ID 3 (Right 360° Cam)');
      }
      logInfo('Auto-detect found ' + detected.length + ' car camera(s): ' + detected.map(c => c.id + ':' + c.role).join(', '));

      if (!state.prefs.autoDetectedOnce && state.prefs.selectedCameras.size === 0) {
        detected.forEach(c => state.prefs.selectedCameras.add(c.id));
        state.prefs.autoDetectedOnce = true;
        logInfo('Auto-selected car cameras for recording: ' + Array.from(state.prefs.selectedCameras).join(', '));
      }

      state.cameras = detected;
      if (!state.activeLiveCam && detected.length > 0) {
        state.activeLiveCam = detected[0];
      }
      renderCameras();
      if (!state.isPlaybackMode && state.activeLiveCam) {
        startLivePreview(state.activeLiveCam);
      }
    }

    function camLabel(c) {
      const h = c.hidden ? ' [hidden]' : '';
      return 'Cam ' + c.id + ' (' + c.role + ') — ' + c.facing + ' — ' + c.maxSz + ' — ' + c.level + h;
    }

    function renderCameras() {
      const box = document.getElementById('camBox');
      const pills = document.getElementById('liveCamPills');
      box.innerHTML = '';
      pills.innerHTML = '';

      for (const c of state.cameras) {
        const pill = document.createElement('button');
        pill.className = 'cam-pill' + (state.activeLiveCam?.id === c.id ? ' active' : '');
        pill.textContent = 'Cam ' + c.id + ': ' + c.role;
        pill.onclick = () => {
          if (state.isPlaybackMode) switchDisplayMode(false);
          startLivePreview(c);
        };
        pills.appendChild(pill);

        const row = document.createElement('div');
        row.className = 'cam-row';

        const lbl = document.createElement('label');
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = state.prefs.selectedCameras.has(c.id);
        cb.onchange = () => {
          if (cb.checked) state.prefs.selectedCameras.add(c.id);
          else state.prefs.selectedCameras.delete(c.id);
        };
        const span = document.createElement('span');
        span.textContent = camLabel(c);
        lbl.appendChild(cb);
        lbl.appendChild(span);

        const btn = document.createElement('button');
        btn.style.background = '#2563EB';
        btn.textContent = 'Live View';
        btn.onclick = () => {
          if (state.isPlaybackMode) switchDisplayMode(false);
          startLivePreview(c);
        };

        row.appendChild(lbl);
        row.appendChild(btn);
        box.appendChild(row);
      }
    }

    function switchDisplayMode(playback) {
      state.isPlaybackMode = playback;
      const liveBtn = document.getElementById('liveModeBtn');
      const playBtn = document.getElementById('playbackModeBtn');
      const livePanel = document.getElementById('livePanel');
      const playPanel = document.getElementById('playbackPanel');

      if (playback) {
        stopLivePreview();
        liveBtn.style.background = '#1E293B';
        playBtn.style.background = '#2563EB';
        livePanel.style.display = 'none';
        playPanel.style.display = 'block';
        renderPlaybackClips();
        if (!state.currentPlayingClip && state.clips.length > 0 && isMountReady()) {
          playClipInMonitor(state.clips[0]);
        } else {
          startPlaybackLoop();
        }
      } else {
        stopPlaybackLoop();
        liveBtn.style.background = '#2563EB';
        playBtn.style.background = '#1E293B';
        playPanel.style.display = 'none';
        livePanel.style.display = 'block';
        if (state.activeLiveCam) startLivePreview(state.activeLiveCam);
      }
    }

    let liveAnim = null;
    async function startLivePreview(c) {
      state.activeLiveCam = c;
      renderCameras();
      const label = document.getElementById('previewLabel');
      const video = document.getElementById('webcamVideo');
      const canvas = document.getElementById('liveCanvas');
      const m = getActiveMount();

      if (state.running && m) {
        label.textContent = '🔴 DIRECT USB STREAM + LIVE: Cam ' + c.id + ' (' + c.role + ') → ' + m.mountPoint;
      } else {
        label.textContent = '🔴 LIVE: Cam ' + c.id + ' (' + c.role + ') · ' + c.maxSz;
      }

      if (!video.srcObject && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        try {
          const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
          video.srcObject = stream;
        } catch (_) {}
      }

      if (liveAnim) cancelAnimationFrame(liveAnim);
      const ctx = canvas.getContext('2d');
      let t = 0;
      function draw() {
        t += 0.03;
        drawCarScene(ctx, canvas.width, canvas.height, c.id, c.role, t, video, state.running ? 'LIVE · DIRECT USB STREAM' : 'LIVE VIEW');
        liveAnim = requestAnimationFrame(draw);
      }
      draw();
    }

    function stopLivePreview() {
      if (liveAnim) cancelAnimationFrame(liveAnim);
      liveAnim = null;
    }

    function drawCarScene(ctx, w, h, camId, roleName, t, videoEl, badgeText) {
      if (videoEl && videoEl.srcObject && videoEl.readyState >= 2 && camId === '0') {
        ctx.drawImage(videoEl, 0, 0, w, h);
      } else {
        const skyColors = {
          '0': ['#09152b', '#1e293b'],
          '1': ['#111827', '#1f2937'],
          '2': ['#0c192c', '#1e293b'],
          '3': ['#0c192c', '#1e293b']
        };
        const [skyTop, skyBot] = skyColors[camId] || skyColors['0'];
        const grad = ctx.createLinearGradient(0, 0, 0, h);
        grad.addColorStop(0, skyTop);
        grad.addColorStop(0.50, skyBot);
        grad.addColorStop(0.51, '#090d16');
        grad.addColorStop(1, '#1e293b');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, w, h);

        ctx.strokeStyle = '#475569';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(w * 0.40, h * 0.51);
        ctx.lineTo(w * 0.06, h);
        ctx.moveTo(w * 0.60, h * 0.51);
        ctx.lineTo(w * 0.94, h);
        ctx.stroke();

        ctx.strokeStyle = '#facc15';
        ctx.lineWidth = 4;
        ctx.setLineDash([28, 22]);
        ctx.lineDashOffset = (camId === '1' ? 1 : -1) * t * 140;
        ctx.beginPath();
        ctx.moveTo(w * 0.5, h * 0.52);
        ctx.lineTo(w * 0.5, h);
        ctx.stroke();
        ctx.setLineDash([]);

        if (camId !== '0') {
          ctx.strokeStyle = 'rgba(34, 197, 94, 0.6)';
          ctx.lineWidth = 2;
          ctx.strokeRect(w * 0.25, h * 0.62, w * 0.50, h * 0.12);
          ctx.strokeStyle = 'rgba(250, 204, 21, 0.7)';
          ctx.strokeRect(w * 0.20, h * 0.74, w * 0.60, h * 0.12);
          ctx.strokeStyle = 'rgba(239, 68, 68, 0.8)';
          ctx.strokeRect(w * 0.15, h * 0.86, w * 0.70, h * 0.10);
        }
      }

      const m = getActiveMount();
      ctx.fillStyle = 'rgba(15, 23, 42, 0.80)';
      ctx.fillRect(12, 12, w - 24, 38);
      ctx.fillStyle = badgeText.includes('PLAYBACK') ? '#38bdf8' : '#ef4444';
      ctx.beginPath();
      ctx.arc(28, 31, 6, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#f8fafc';
      ctx.font = 'bold 14px monospace';
      ctx.fillText(badgeText + ' · CAM ' + camId + ' (' + roleName.toUpperCase() + ')', 42, 36);

      ctx.fillStyle = '#94a3b8';
      ctx.textAlign = 'right';
      ctx.fillText((m ? m.mountPoint : 'NO-USB') + ' · ' + fmtTime(), w - 22, 36);
      ctx.textAlign = 'left';
    }

    let playbackAnim = null;
    let lastTickMs = 0;

    function playClipInMonitor(clip) {
      if (!isMountReady()) {
        showToast('Plug in a writable External USB Hard Drive first');
        return;
      }
      state.currentPlayingClip = clip;
      state.playbackPosSec = 0;
      state.playbackPlaying = true;
      document.getElementById('playbackScrub').max = clip.duration || 180;
      document.getElementById('playbackScrub').value = 0;
      document.getElementById('playbackTimeMax').textContent = fmtMMSS(clip.duration || 180);
      const lockTag = clip.locked ? ' [LOCKED]' : '';
      document.getElementById('playbackStatusLabel').textContent =
        '▶ Playing: ' + clip.name + ' (Cam ' + clip.cam + ': ' + clip.role + ')' + lockTag;
      logInfo('Playback started: ' + clip.name);
      renderPlaybackClips();
      startPlaybackLoop();
    }

    function startPlaybackLoop() {
      stopPlaybackLoop();
      const canvas = document.getElementById('playbackCanvas');
      const ctx = canvas.getContext('2d');
      lastTickMs = performance.now();
      let t = 0;
      function loop(now) {
        const dt = (now - lastTickMs) / 1000;
        lastTickMs = now;
        const clip = state.currentPlayingClip;
        if (!isMountReady() || !clip) {
          ctx.fillStyle = '#020617';
          ctx.fillRect(0, 0, canvas.width, canvas.height);
          ctx.fillStyle = '#94a3b8';
          ctx.font = '16px sans-serif';
          ctx.textAlign = 'center';
          ctx.fillText(
            !isMountReady()
              ? '⚠️ External USB Mount Unavailable or Unwritable'
              : 'Select a recorded clip below to start playback',
            canvas.width / 2,
            canvas.height / 2
          );
          ctx.textAlign = 'left';
          return;
        }
        if (state.playbackPlaying) {
          t += dt * 1.8;
          state.playbackPosSec += dt;
          if (state.playbackPosSec >= (clip.duration || 180)) {
            state.playbackPosSec = 0;
          }
          document.getElementById('playbackScrub').value = Math.floor(state.playbackPosSec);
          document.getElementById('playbackTimeCur').textContent = fmtMMSS(state.playbackPosSec);
        }
        drawCarScene(
          ctx,
          canvas.width,
          canvas.height,
          clip.cam,
          clip.role || ('Cam ' + clip.cam),
          t,
          null,
          (state.playbackPlaying ? '▶ PLAYBACK' : '⏸ PAUSED') + ' · ' + clip.name
        );
        playbackAnim = requestAnimationFrame(loop);
      }
      playbackAnim = requestAnimationFrame(loop);
    }

    function stopPlaybackLoop() {
      if (playbackAnim) cancelAnimationFrame(playbackAnim);
      playbackAnim = null;
    }

    function togglePlaybackPause() {
      if (!state.currentPlayingClip) {
        showToast('Select a clip below to play');
        return;
      }
      state.playbackPlaying = !state.playbackPlaying;
      const clip = state.currentPlayingClip;
      document.getElementById('playbackStatusLabel').textContent =
        (state.playbackPlaying ? '▶ Playing: ' : '⏸ Paused: ') + clip.name;
    }

    function scrubPlayback(sec) {
      state.playbackPosSec = sec;
      document.getElementById('playbackTimeCur').textContent = fmtMMSS(sec);
    }

    function lockCurrentPlaybackClip() {
      const clip = state.currentPlayingClip;
      if (!clip) return showToast('Select a clip first');
      if (clip.locked) return showToast('Clip is already locked');
      clip.locked = true;
      logInfo('Locked clip from playback: ' + clip.name);
      showToast('Clip moved to Locked folder on External USB');
      renderPlaybackClips();
    }

    function deleteCurrentPlaybackClip() {
      const clip = state.currentPlayingClip;
      if (!clip) return showToast('Select a clip first');
      state.clips = state.clips.filter(c => c.id !== clip.id);
      logInfo('Deleted clip from playback: ' + clip.name);
      showToast('Deleted ' + clip.name);
      state.currentPlayingClip = state.clips[0] || null;
      renderPlaybackClips();
      startPlaybackLoop();
    }

    function renderPlaybackClips() {
      const box = document.getElementById('playbackClipsList');
      box.innerHTML = '';
      if (!isMountReady()) {
        document.getElementById('playbackStatusLabel').textContent =
          '⚠️ Plug in a writable External USB Drive to browse and play recorded clips.';
        box.innerHTML = '<div style="color:#F87171;font-size:14px">No writable external USB mount detected. Recording and playback use external USB storage only.</div>';
        return;
      }
      if (state.clips.length === 0) {
        document.getElementById('playbackStatusLabel').textContent =
          'No recorded clips found on External USB Mount yet.';
        box.innerHTML = '<div class="muted">Start recording to stream clips directly onto the external USB mount.</div>';
        return;
      }
      for (const clip of state.clips) {
        const card = document.createElement('div');
        const isCur = state.currentPlayingClip?.id === clip.id;
        card.className = 'clip-card' + (isCur ? ' playing' : '');

        const info = document.createElement('div');
        info.innerHTML =
          '<div class="clip-name">' + (clip.locked ? '🔒 ' : '') + clip.name + '</div>' +
          '<div class="clip-meta">Cam ' + clip.cam + ' (' + clip.role + ') · ' + clip.time + ' · ' + clip.mb.toFixed(1) + ' MB</div>';

        const actions = document.createElement('div');
        actions.className = 'clip-actions';

        const playBtn = document.createElement('button');
        playBtn.style.background = '#2563EB';
        playBtn.textContent = isCur ? '▶ Playing' : '▶ Play';
        playBtn.onclick = () => playClipInMonitor(clip);
        actions.appendChild(playBtn);

        if (!clip.locked) {
          const lockBtn = document.createElement('button');
          lockBtn.textContent = '🔒 Lock';
          lockBtn.onclick = () => {
            clip.locked = true;
            logInfo('Locked clip: ' + clip.name);
            renderPlaybackClips();
          };
          actions.appendChild(lockBtn);
        }

        const delBtn = document.createElement('button');
        delBtn.style.background = '#7F1D1D';
        delBtn.textContent = 'Delete';
        delBtn.onclick = () => {
          state.clips = state.clips.filter(x => x.id !== clip.id);
          if (state.currentPlayingClip?.id === clip.id) {
            state.currentPlayingClip = state.clips[0] || null;
          }
          logInfo('Deleted clip: ' + clip.name);
          renderPlaybackClips();
        };
        actions.appendChild(delBtn);

        card.appendChild(info);
        card.appendChild(actions);
        box.appendChild(card);
      }
    }

    function pickHeightLabel() {
      const h = state.prefs.maxHeight;
      if (h === 480) return '854x480';
      if (h === 720) return '1280x720';
      return '1920x1080';
    }

    function startRecording() {
      const m = getActiveMount();
      if (!m) {
        showToast('Please plug in an External USB Hard Drive! Recording to Car HU is disabled.');
        logInfo('DiskManagerService blocked recording: No connected external USB drive');
        return;
      }
      if (!state.mountWritableVerified) {
        showToast('USB mount failed write permission check (' + m.mountPoint + ')');
        logInfo('DiskManagerService blocked recording: Write permission denied on ' + m.mountPoint);
        return;
      }
      if (state.prefs.selectedCameras.size === 0) {
        showToast('Tick at least one car camera first');
        return;
      }
      state.running = true;
      const stamp = fmtStamp();
      const res = pickHeightLabel();
      const sorted = Array.from(state.prefs.selectedCameras).sort();
      logInfo('DiskManager probe [' + m.label + ' @ ' + m.mountPoint + ']: WRITE OK (' + m.probeLatencyMs + 'ms) · Routing camera stream descriptors');
      state.activeRecorders = sorted.map(id => {
        const camObj = state.cameras.find(c => c.id === id);
        const role = camObj ? camObj.role : ('Car Cam ' + id);
        const name = 'ReKab_cam' + id + '_' + stamp + '.mp4';
        logInfo('Camera ' + id + ' (' + role + '): streaming directly to USB mount ' + m.mountPoint + ' (' + name + ')');
        state.clips.unshift({
          id: 'clip_' + Date.now() + '_' + id,
          cam: id,
          role,
          name,
          time: new Date().toUTCString().slice(5, 22),
          mb: 28.4,
          locked: false,
          duration: state.prefs.segmentMinutes * 60
        });
        return { id, status: 'REC ' + res + ' → ' + m.mountPoint + '/' + name };
      });
      logInfo('Direct USB stream started → Mount: ' + m.mountPoint + ' (' + m.streamRootDir + ')');
      showToast('Direct stream recording started → ' + m.mountPoint);
      renderStatus();
      if (state.isPlaybackMode) renderPlaybackClips();
      if (!state.isPlaybackMode && state.activeLiveCam) startLivePreview(state.activeLiveCam);
    }

    function stopRecording() {
      if (state.running) {
        logInfo('Recording stopped');
      }
      state.running = false;
      state.activeRecorders = [];
      showToast('Recording stopped');
      renderStatus();
      if (!state.isPlaybackMode && state.activeLiveCam) startLivePreview(state.activeLiveCam);
    }

    function lockLatestClips() {
      if (!isMountReady()) {
        showToast('Plug in writable external USB drive first');
        return;
      }
      let lockedCount = 0;
      const unlocked = state.clips.filter(c => !c.locked);
      for (const c of unlocked.slice(0, 2)) {
        c.locked = true;
        lockedCount++;
      }
      logInfo('Locked ' + lockedCount + ' latest clip(s) on External USB Mount');
      showToast(lockedCount + ' clip(s) moved to Locked on External USB');
      if (state.isPlaybackMode) renderPlaybackClips();
    }

    function updatePref(key, val) {
      state.prefs[key] = val;
      if (key === 'storageIndex') {
        const m = getActiveMount();
        if (m) {
          logInfo('Switched active USB mount point to ' + m.mountPoint + ' (' + m.streamRootDir + ')');
        }
      }
      renderDriveUi();
      renderStatus();
    }

    function togglePhoneAccess(on) {
      state.prefs.phoneAccess = on;
      if (on) {
        logInfo('Phone access on port ' + state.prefs.port + ': http://192.168.43.1:' + state.prefs.port + '   (wlan0)');
      } else {
        logInfo('Phone access server stopped');
      }
      renderStatus();
    }

    function applyPort() {
      const v = Number(document.getElementById('portInput').value);
      if (!Number.isInteger(v) || v < 1024 || v > 65535) {
        showToast('Port must be 1024–65535');
        return;
      }
      state.prefs.port = v;
      if (state.prefs.phoneAccess) {
        logInfo('Phone access on port ' + v + ': http://192.168.43.1:' + v + '   (wlan0)');
      }
      renderStatus();
      showToast('Port updated to ' + v);
    }

    function renderStatus() {
      const m = getActiveMount();
      const lines = [];
      lines.push(state.running ? 'Streaming ' + (state.activeRecorders?.length || 0) + ' camera(s) directly to External USB Mount' : 'Not recording');
      if (state.running && state.activeRecorders) {
        for (const r of state.activeRecorders) {
          lines.push('Camera ' + r.id + ': ' + r.status);
        }
      }
      if (!m) {
        lines.push('DiskManagerService: ⚠️ NO EXTERNAL USB DRIVE CONNECTED (Car HU storage disabled)');
      } else if (!state.mountWritableVerified) {
        lines.push('DiskManagerService: ❌ USB MOUNT WRITE DENIED (' + m.mountPoint + ')');
      } else {
        lines.push('Direct Stream Target: ' + m.streamRootDir + '  (Mount ' + m.mountPoint + ' · ' + m.free + ' free)');
      }
      document.getElementById('statusText').textContent = lines.join('\\n');
      const canStart = !state.running && isMountReady();
      document.getElementById('startBtn').style.opacity = canStart ? '1' : '0.5';

      const urlEl = document.getElementById('urlText');
      if (!state.prefs.phoneAccess) {
        urlEl.textContent = 'Phone access is off';
      } else {
        urlEl.textContent = 'http://192.168.43.1:' + state.prefs.port + '   (wlan0)';
      }

      document.getElementById('logText').textContent = state.logs.slice().reverse().join('\\n');
    }

    // Initialize
    logInfo('DiskManagerService started: monitoring external USB mount points');
    logInfo('DiskManager probe [USB Drive (usb0) @ /mnt/media_rw/usb0]: WRITE OK (2ms) · Write/fsync verified on /mnt/media_rw/usb0/ReKab');
    logInfo('Phone access on port 8080: http://192.168.43.1:8080   (wlan0)');
    renderDriveUi();
    rescanCameras(true);
    renderStatus();
  </script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (url === '/download-apk') {
    const candidates = [
      path.resolve('/app/applet/.build-outputs/app-debug.apk'),
      path.resolve('/app/applet/app/build/outputs/apk/debug/app-debug.apk'),
      path.resolve('/app/applet/release/ReKab-by-firdaus.apk'),
    ];
    const found = candidates.find((p) => fs.existsSync(p));
    if (found) {
      const stat = fs.statSync(found);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="ReKab-debug.apk"',
      });
      fs.createReadStream(found).pipe(res);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('APK not found');
    return;
  }

  if (url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(HTML);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('ReKab preview server listening on http://0.0.0.0:' + PORT);
});
