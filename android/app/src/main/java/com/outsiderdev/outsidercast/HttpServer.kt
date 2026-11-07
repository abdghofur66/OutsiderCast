package com.outsiderdev.outsidercast

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Server HTTP mini: halaman penonton + signaling WebRTC (POST /offer). Tanpa library tambahan. */
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

    fun stop() { running = false; try { server?.close() } catch (_: Exception) {} }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read(); if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c); if (b.size() > 8192) return null
        }
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            try {
                c.soTimeout = 15000
                val inp = BufferedInputStream(c.getInputStream())
                val line = readLine(inp) ?: return
                var len = 0
                while (true) {
                    val h = readLine(inp) ?: break; if (h.isEmpty()) break
                    if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val body = if (len in 1..200_000) ByteArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } } else ByteArray(0)
                val parts = line.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val q = parseQuery(target.substringAfter('?', ""))
                val out = c.getOutputStream()
                when {
                    path == "/" || path == "/index.html" -> send(out, 200, "text/html; charset=utf-8", ctx.assets.open("viewer.html").readBytes())
                    path == "/icon.png" || path == "/favicon.ico" -> try {
                        send(out, 200, "image/png", ctx.assets.open("icon.png").readBytes())
                    } catch (_: Exception) { send(out, 404, "text/plain", "Tidak ditemukan".toByteArray()) }
                    path == "/status" -> send(out, 200, "application/json",
                        """{"live":${Cast.live},"paused":${Cast.paused},"pin":${Cast.pin.isNotEmpty()}}""".toByteArray())
                    path == "/auth" -> if (pinOk(q["pin"])) send(out, 200, "text/plain", "ok".toByteArray())
                                       else send(out, 403, "text/plain", "PIN salah".toByteArray())
                    path == "/stream" -> if (pinOk(q["pin"])) stream(c, out, (q["name"] ?: "Penonton").take(40))
                                         else send(out, 403, "text/plain", "PIN salah".toByteArray())
                    path == "/frame.jpg" -> {
                        if (!pinOk(q["pin"])) send(out, 403, "text/plain", "PIN salah".toByteArray())
                        else {
                            Cast.mjpegTouch()
                            val f = Cast.frame
                            if (f == null || Cast.paused) send(out, 204, "image/jpeg", ByteArray(0)) else send(out, 200, "image/jpeg", f)
                        }
                    }
                    path == "/debug" -> send(out, 200, "text/plain",
                        ("host: " + Cast.lanAddresses().joinToString { "${it.first}=${it.second}" } + " | kamu: " + c.inetAddress.hostAddress).toByteArray())
                    path == "/offer" && method == "POST" -> offer(out, String(body, Charsets.UTF_8), c.inetAddress.hostAddress ?: "")
                    else -> send(out, 404, "text/plain", "Tidak ditemukan".toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    private fun offer(out: OutputStream, body: String, clientIp: String) {
        val j = try { JSONObject(body) } catch (_: Exception) { return send(out, 400, "text/plain", "Bad".toByteArray()) }
        if (!pinOk(j.optString("pin"))) return send(out, 403, "text/plain", "PIN salah".toByteArray())
        val eng = CastService.instance?.engine
        if (!Cast.live || eng == null) return send(out, 503, "text/plain", "Belum berbagi".toByteArray())
        // Browser menyamarkan IP lokalnya sebagai "xxxx.local" (mDNS) yang tidak bisa dibaca
        // WebRTC Android. Ganti dengan IP asli penonton yang terlihat dari koneksi HTTP ini.
        val ip = clientIp.removePrefix("::ffff:")
        val sdp = if (ip.isEmpty()) j.getString("sdp")
                  else j.getString("sdp").replace(Regex("[0-9a-fA-F-]{36}\\.local"), ip)
        // Mulai langsung di bitrate tinggi (bawaan WebRTC mulai pelan lalu naik bertahap)
        val kbps = Cast.quality.bitrate / 1000
        val tuned = sdp.replace(Regex("(a=fmtp:\\d+ [^\\r\\n]+)")) {
            if (it.value.contains("minptime") || it.value.contains("useinbandfec")) it.value + ";stereo=0;maxaveragebitrate=96000"
            else it.value + ";x-google-start-bitrate=${kbps * 2 / 3};x-google-min-bitrate=${kbps / 4}"
        }
        val ans = eng.answer(tuned, j.optString("name", "Penonton").ifBlank { "Penonton" }.take(40))
            ?: return send(out, 500, "text/plain", "Gagal".toByteArray())
        send(out, 200, "application/json", JSONObject().put("type", "answer").put("sdp", ans).toString().toByteArray())
    }

    private fun stream(c: Socket, out: OutputStream, name: String) {
        c.soTimeout = 0; c.tcpNoDelay = true
        val id = Cast.addViewer("$name (cadangan)")
        Cast.mjpegViewers.incrementAndGet()
        try {
            out.write(("HTTP/1.1 200 OK\r\nCache-Control: no-cache, no-store\r\nConnection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=ocframe\r\n\r\n").toByteArray())
            var last = -1L
            while (running && Cast.live) {
                last = Cast.waitFrame(last, 2000)
                val f = Cast.frame ?: continue
                if (Cast.paused) continue
                out.write("--ocframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f); out.write("\r\n".toByteArray()); out.flush()
            }
        } catch (_: Exception) {
        } finally { Cast.viewers.remove(id); Cast.mjpegViewers.decrementAndGet() }
    }

    private fun pinOk(p: String?) = Cast.pin.isEmpty() || p == Cast.pin

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val msg = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 503 -> "Unavailable"; else -> "Error" }
        out.write("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun parseQuery(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}
