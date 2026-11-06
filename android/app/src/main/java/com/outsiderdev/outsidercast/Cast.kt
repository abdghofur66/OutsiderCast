package com.outsiderdev.outsidercast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Status bersama antara layanan, server HTTP, dan tampilan. */
object Cast {
    enum class Quality(val label: String, val longSide: Int, val fps: Int, val bitrate: Int, val smooth: Boolean = false) {
        FAST("Responsif (gerak cepat, 60 fps)", 1280, 60, 5_000_000, true),
        HIGH("Tinggi (1080p, 30 fps)", 1920, 30, 4_000_000),
        MEDIUM("Sedang (720p, 30 fps)", 1280, 30, 2_000_000),
        LOW("Hemat (kelas besar)", 960, 15, 800_000)
    }

    @Volatile var live = false
    @Volatile var paused = false
    @Volatile var audioOn = true
    @Volatile var pin = ""
    @Volatile var quality = Quality.MEDIUM
    @Volatile var port = 0

    // Cadangan MJPEG bila WebRTC gagal tersambung
    val mjpegViewers = AtomicInteger(0)
    @Volatile var lastPoll = 0L
    fun mjpegTouch() { lastPoll = System.currentTimeMillis() }
    fun wantJpeg() = mjpegViewers.get() > 0 || System.currentTimeMillis() - lastPoll < 3000
    private val lock = Object()
    @Volatile var frame: ByteArray? = null; private set
    @Volatile var seq = 0L; private set
    fun publish(jpeg: ByteArray) { synchronized(lock) { frame = jpeg; seq++; lock.notifyAll() } }
    fun waitFrame(after: Long, ms: Long): Long { synchronized(lock) { if (seq == after) lock.wait(ms); return seq } }

    val viewers = ConcurrentHashMap<Int, String>()
    private val ids = AtomicInteger(1)
    fun addViewer(name: String): Int { val id = ids.getAndIncrement(); viewers[id] = name; return id }

    fun lanAddresses(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) out += ni.name to a.hostAddress!!
                }
            }
        } catch (_: Exception) {}
        // Wi-Fi & hotspot didahulukan, data seluler (rmnet) paling akhir
        return out.sortedBy { (n, _) -> when { n.startsWith("wlan") -> 0; n.contains("ap") -> 1; n.startsWith("rmnet") -> 9; else -> 5 } }
    }
}
