package com.ambient.tvclock.vpn

import java.net.Inet4Address
import java.net.NetworkInterface

/** Picks the first non-loopback IPv4 address on this device, preferring wlan/eth interfaces. */
object LanIp {
    fun firstIPv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            return null
        }
        // Prefer wlan/eth/en interfaces to avoid grabbing a tun0/utun address.
        val sorted = interfaces.sortedBy { iface ->
            val name = iface.name.lowercase()
            when {
                name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> 0
                name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp") -> 9
                else -> 5
            }
        }
        for (iface in sorted) {
            if (!iface.isUp || iface.isLoopback) continue
            val addrs = iface.inetAddresses?.toList().orEmpty()
            val v4 = addrs.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            if (v4 != null) return v4.hostAddress
        }
        return null
    }
}
