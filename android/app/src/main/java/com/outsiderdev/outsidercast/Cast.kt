package com.outsiderdev.outsidercast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Status bersama antara layanan, server HTTP, dan tampilan. */
object Cast {
    enum class Quality(val label: String, val longSide: Int, val fps: Int, val bitrate: Int) {
        HIGH("Tinggi (1080p, 30 fps)", 1920, 30, 4_000_000),
        MEDIUM("Sedang (720p, 30 fps)", 1280, 30, 2_000_000),
        LOW("Hemat (kelas besar)", 960, 15, 800_000)
    }

    @Volatile var live = false
    @Volatile var paused = false
    @Volatile var pin = ""
    @Volatile var quality = Quality.MEDIUM
    @Volatile var port = 0

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
