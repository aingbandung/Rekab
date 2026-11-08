package com.firdaus.rekab

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tiny web server so a phone on the same Wi-Fi (e.g. the phone's hotspot that the
 * head unit is connected to) can browse, play and download the clips from the external hard drive.
 */
object FileServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private lateinit var app: Context
    @Volatile var runningPort = 0
        private set

    @Synchronized
    fun start(ctx: Context, port: Int) {
        app = ctx.applicationContext
        if (server != null && runningPort == port) return
        stop()
        try {
            val ss = ServerSocket(port)
            server = ss
            runningPort = port
            pool.execute {
                while (!ss.isClosed) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(s) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        AppLog.e("Server accept", e)
                    }
                }
            }
            AppLog.i("Phone access on port $port: ${urls().joinToString()}")
        } catch (e: Exception) {
            AppLog.e("Cannot start phone access server on port $port", e)
            runningPort = 0
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        runningPort = 0
    }

    fun urls(): List<String> {
        val port = if (runningPort != 0) runningPort else return emptyList()
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
                .map { (name, a) -> "http://${a.hostAddress}:$port   ($name)" }
        } catch (_: Exception) { emptyList() }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val full = parts[1]
                val path = URLDecoder.decode(full.substringBefore('?'), "UTF-8")
                val query = full.substringAfter('?', "")
                val out = s.getOutputStream()
                when {
                    path == "/" -> html(out, indexPage())
                    path.startsWith("/f/") -> serveFile(out, path.removePrefix("/f/"), headers["range"],
                        query.contains("dl=1"), method == "HEAD")
                    path.startsWith("/del/") -> { delete(path.removePrefix("/del/")); redirect(out) }
                    path.startsWith("/lock/") -> { lock(path.removePrefix("/lock/")); redirect(out) }
                    else -> text(out, 404, "Not found")
                }
                out.flush()
            } catch (_: SocketException) {
            } catch (e: Exception) {
                AppLog.e("Server request", e)
            }
        }
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    /** Resolves a relative path inside the external drive clips folder; refuses anything outside it. */
    private fun resolve(rel: String): File? {
        val b = Storage.baseOrNull(app)?.canonicalFile ?: return null
        val f = File(b, rel).canonicalFile
        return if (f.path.startsWith(b.path + File.separator) && f.isFile) f else null
    }

    private fun serveFile(out: OutputStream, rel: String, range: String?, download: Boolean, head: Boolean) {
        val f = resolve(rel) ?: return text(out, 404, "File not found")
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val r = range.removePrefix("bytes=").substringBefore(',')
            val a = r.substringBefore('-')
            val b = r.substringAfter('-')
            if (a.isNotEmpty()) {
                start = a.toLongOrNull() ?: 0
                if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(len - 1)
            } else if (b.isNotEmpty()) {
                start = (len - (b.toLongOrNull() ?: len)).coerceAtLeast(0)
            }
            partial = true
        }
        if (start > end || start >= len) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val count = end - start + 1
        val type = if (f.name.endsWith(".mp4")) "video/mp4" else "application/octet-stream"
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $count\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$len\r\n")
        if (download) sb.append("Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (head) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun delete(rel: String) {
        resolve(rel)?.let { if (it.delete()) AppLog.i("Deleted from phone: ${it.name}") }
    }

    private fun lock(rel: String) {
        val lockedFolder = Storage.lockedDir(app) ?: return
        resolve(rel)?.let {
            val dest = File(lockedFolder, it.name)
            if (it.renameTo(dest)) AppLog.i("Locked from phone: ${it.name}")
        }
    }

    private fun indexPage(): String {
        val base = Storage.baseOrNull(app)
        val df = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ReKab by firdaus</title><style>
body{font-family:system-ui,sans-serif;margin:0;background:#0f172a;color:#e2e8f0}
header{padding:16px;background:#1e293b;position:sticky;top:0}
h1{margin:0;font-size:20px} .sub{color:#94a3b8;font-size:13px;margin-top:4px}
h2{font-size:16px;margin:20px 16px 8px;color:#f8fafc}
.c{margin:6px 12px;padding:10px 12px;background:#1e293b;border-radius:10px}
.n{font-size:14px;word-break:break-all} .m{color:#94a3b8;font-size:12px;margin:4px 0 8px}
a.b{display:inline-block;margin-right:8px;padding:7px 12px;border-radius:8px;background:#334155;color:#fff;text-decoration:none;font-size:13px}
a.p{background:#2563eb} a.d{background:#7f1d1d}
</style></head><body><header><h1>ReKab by firdaus</h1>""")
        if (base == null) {
            sb.append("<div class=sub>${RecorderService.summary()} · No external hard drive connected</div></header>")
            sb.append("<p style='padding:16px;color:#f87171'>⚠️ Please plug in an external USB hard drive to the Car Head Unit.</p></body></html>")
            return sb.toString()
        }
        val dirs = (base.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .sortedWith(compareBy({ it.name != Storage.LOCKED }, { it.name }))
        sb.append("<div class=sub>${RecorderService.summary()} · External HDD free ${Storage.gb(Storage.freeBytes(base))}</div></header>")
        if (dirs.isEmpty()) sb.append("<p style='padding:16px'>No clips yet on external hard drive.</p>")
        for (d in dirs) {
            val files = (d.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".mp4") }
                .sortedByDescending { it.lastModified() }
            val title = if (d.name == Storage.LOCKED) "🔒 Locked clips" else "Camera " + d.name.removePrefix("cam_")
            sb.append("<h2>$title (${files.size})</h2>")
            for (f in files) {
                val rel = enc(d.name) + "/" + enc(f.name)
                val mb = String.format(Locale.US, "%.1f MB", f.length() / 1048576.0)
                sb.append("<div class=c><div class=n>${f.name}</div>")
                sb.append("<div class=m>${df.format(Date(f.lastModified()))} · $mb</div>")
                sb.append("<a class='b p' href='/f/$rel?dl=1'>Download</a>")
                sb.append("<a class=b href='/f/$rel'>Play</a>")
                if (d.name != Storage.LOCKED) sb.append("<a class=b href='/lock/$rel'>Lock</a>")
                sb.append("<a class='b d' href='/del/$rel' onclick=\"return confirm('Delete ${f.name}?')\">Delete</a></div>")
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun html(out: OutputStream, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
    }

    private fun text(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray()
        out.write("HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
    }

    private fun redirect(out: OutputStream) {
        out.write("HTTP/1.1 303 See Other\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }
}
