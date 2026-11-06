package com.outsiderdev.outsidercast

import android.content.Context
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkChangeDetectorFactory
import org.webrtc.NetworkMonitorAutoDetect
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * WebRTC Android tidak memasukkan antarmuka hotspot/tethering (ap0, swlan0, dll.)
 * ke daftar jaringannya, sehingga penonton yang tersambung ke hotspot HP ini gagal
 * terhubung. Detektor ini menambahkan antarmuka tersebut secara manual.
 */
class TetherDetectorFactory : NetworkChangeDetectorFactory {
    override fun create(observer: NetworkChangeDetector.Observer, context: Context): NetworkChangeDetector {
        val base = NetworkMonitorAutoDetect(observer, context)
        return object : NetworkChangeDetector by base {
            override fun getActiveNetworkList(): List<NetworkChangeDetector.NetworkInformation>? {
                val list = (base.activeNetworkList ?: emptyList()).toMutableList()
                val known = list.map { it.name }.toSet()
                try {
                    for (ni in NetworkInterface.getNetworkInterfaces()) {
                        if (!ni.isUp || ni.isLoopback || ni.name in known) continue
                        if (!Regex("^(ap|swlan|softap|wlan|rndis|p2p|eth|wigig|bt-pan)").containsMatchIn(ni.name)) continue
                        val ips = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                            .map { NetworkChangeDetector.IPAddress(it.address) }
                        if (ips.isEmpty()) continue
                        list += NetworkChangeDetector.NetworkInformation(
                            ni.name, NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                            NetworkChangeDetector.ConnectionType.CONNECTION_NONE, 0L, ips.toTypedArray())
                    }
                } catch (_: Exception) {}
                return list
            }
        }
    }
}
