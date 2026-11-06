package com.outsiderdev.outsidercast

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Server HTTP mini: halaman penonton + aliran MJPEG. Tanpa library tambahan. */
class HttpServer(private val ctx: Context) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Int {
        for (p in listOf(8080, 8081, 8090, 0)) {
            try { server = ServerSocket(p); break } catch (_: Exception) {}
        }
        val s = server ?: return 0
        running = true
        thread(name = "oc-http", isDaemon = true) {
            while (running) {
                try { val c = s.accept(); thread(isDaemon = true) { handle(c) } } catch (_: Exception) {}
            }
        }
        return s.localPort
    }

    fun stop() { running = false; try { server?.close() } catch (_: Exception) {}; Cast.wakeAll() }

    private fun handle(sock: Socket) {
        sock.use { c ->
            try {
                c.soTimeout = 15000
                val rd = BufferedReader(InputStreamReader(c.getInputStream()))
                val line = rd.readLine() ?: return
                while (true) { val h = rd.readLine() ?: break; if (h.isEmpty()) break }
                val target = line.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val q = parseQuery(target.substringAfter('?', ""))
                val out = c.getOutputStream()
                when (path) {
                    "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8",
                        ctx.assets.open("viewer.html").readBytes())
                    "/status" -> send(out, 200, "application/json",
                        """{"live":${Cast.live},"paused":${Cast.paused},"pin":${Cast.pin.isNotEmpty()}}""".toByteArray())
                    "/auth" -> if (pinOk(q)) send(out, 200, "text/plain", "ok".toByteArray())
                               else send(out, 403, "text/plain", "PIN salah".toByteArray())
                    "/stream" -> if (pinOk(q)) stream(c, out, (q["name"] ?: "Penonton").take(40))
                                 else send(out, 403, "text/plain", "PIN salah".toByteArray())
                    else -> send(out, 404, "text/plain", "Tidak ditemukan".toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    private fun pinOk(q: Map<String, String>) = Cast.pin.isEmpty() || q["pin"] == Cast.pin

    private fun stream(c: Socket, out: OutputStream, name: String) {
        c.soTimeout = 0
        c.tcpNoDelay = true
        val id = Cast.addViewer(name)
        try {
            out.write(("HTTP/1.1 200 OK\r\nCache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=ocframe\r\n\r\n").toByteArray())
            var last = -1L
            while (running) {
                last = Cast.waitFrame(last, 2000)
                val f = Cast.frame
                if (f == null || Cast.paused || !Cast.live) {
                    // jaga koneksi tetap hidup agar penonton tidak terputus
                    if (f != null && !Cast.live) break
                    continue
                }
                out.write("--ocframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally { Cast.viewers.remove(id) }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val msg = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun parseQuery(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}
