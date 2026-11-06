package com.outsiderdev.outsidercast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Status bersama antara layanan rekam layar, server HTTP, dan tampilan. */
object Cast {
    enum class Quality(val label: String, val longSide: Int, val jpeg: Int, val fps: Int) {
        HIGH("Tinggi (teks tajam)", 1280, 80, 15),
        MEDIUM("Sedang", 960, 65, 10),
        LOW("Hemat (kelas besar)", 720, 50, 6)
    }

    @Volatile var live = false
    @Volatile var paused = false
    @Volatile var pin = ""
    @Volatile var quality = Quality.MEDIUM
    @Volatile var port = 0

    // Frame JPEG terbaru
    private val lock = Object()
    @Volatile var frame: ByteArray? = null; private set
    @Volatile var seq = 0L; private set

    fun publish(jpeg: ByteArray) {
        synchronized(lock) { frame = jpeg; seq++; lock.notifyAll() }
    }
    fun waitFrame(after: Long, timeoutMs: Long): Long {
        synchronized(lock) { if (seq == after) lock.wait(timeoutMs); return seq }
    }
    fun wakeAll() { synchronized(lock) { lock.notifyAll() } }

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
