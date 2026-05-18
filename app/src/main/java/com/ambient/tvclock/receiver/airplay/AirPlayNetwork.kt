package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * IPv6 link-local helpers for AirPlay (fe80::…%wlan0 scope, interface bind).
 */
object AirPlayNetwork {

    private val PREFERRED_IFACES = listOf("wlan0", "eth0", "ap0")

    /**
     * Ensures link-local IPv6 peers have a valid scope id for UDP/TCP (Android strips scope in hostAddress).
     */
    fun normalizePeerAddress(remote: InetAddress, fromSocket: Socket? = null): InetAddress {
        if (remote !is Inet6Address) return remote

        val host = remote.hostAddress
        if (host != null && '%' in host) {
            val bare = host.substringBefore('%')
            val zone = host.substringAfter('%')
            val scopeId = scopeIdFromZone(zone, fromSocket)
            if (scopeId > 0) {
                return scopedIpv6(bare, scopeId)
            }
        }

        if (remote.scopeId != 0) return remote

        if (remote.isLinkLocalAddress) {
            val scopeId = scopeIdFromSocket(fromSocket)
                ?: preferredNetworkInterface()?.index
                ?: 0
            if (scopeId > 0) {
                return Inet6Address.getByAddress(null, remote.address, scopeId)
            }
        }
        return remote
    }

    fun formatAddress(addr: InetAddress): String {
        if (addr is Inet6Address && addr.scopeId != 0) {
            val base = addr.hostAddress?.substringBefore('%') ?: return addr.toString()
            return "$base%${addr.scopeId}"
        }
        return addr.hostAddress ?: addr.toString()
    }

    /** Link-local IPv6 on wlan0/eth0 — use for UDP/TCP bind when control is on fe80:: */
    fun preferredIpv6BindAddress(): InetAddress? {
        val nif = preferredNetworkInterface() ?: return null
        return nif.inetAddresses.toList().firstOrNull { addr ->
            addr is Inet6Address && addr.isLinkLocalAddress && !addr.isLoopbackAddress
        }
    }

    fun preferredNetworkInterface(): NetworkInterface? {
        for (name in PREFERRED_IFACES) {
            try {
                val nif = NetworkInterface.getByName(name)
                if (nif != null && nif.isUp) return nif
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun openTimingSocket(port: Int): DatagramSocket {
        val sock = DatagramSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(port))
        Logger.i("Timing socket bound on UDP port $port (all interfaces)")
        return sock
    }

    fun bindMirrorServer(port: Int): java.net.ServerSocket {
        val server = java.net.ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        Logger.i("Mirror TCP bound on 0.0.0.0:$port (all interfaces)")
        return server
    }

    private fun scopeIdFromZone(zone: String, fromSocket: Socket?): Int {
        if (zone.isNotEmpty() && zone.all { it.isDigit() }) {
            return zone.toIntOrNull() ?: -1
        }
        try {
            NetworkInterface.getByName(zone)?.index?.let { if (it > 0) return it }
        } catch (_: Exception) {
        }
        return scopeIdFromSocket(fromSocket) ?: -1
    }

    private fun scopeIdFromSocket(socket: Socket?): Int {
        if (socket == null) return -1
        val local = socket.localAddress
        if (local is Inet6Address && local.scopeId != 0) return local.scopeId
        return -1
    }

    private fun scopedIpv6(hostWithoutZone: String, scopeId: Int): Inet6Address {
        val bytes = Inet6Address.getByName(hostWithoutZone).address
        return Inet6Address.getByAddress(null, bytes, scopeId)
    }
}
