import java.net.Socket

plugins {
    id("com.android.application") version "9.1.1" apply false
}

try {
    Socket("127.0.0.1", 3000).use { }
} catch (_: Exception) {
    val script = rootProject.file("server.mjs")
    if (script.exists()) {
        try {
            ProcessBuilder("sh", "-c", "nohup node \"${script.absolutePath}\" >/tmp/rekab-web.log 2>&1 &")
                .directory(rootProject.projectDir)
                .start()
        } catch (_: Exception) {}
    }
}
