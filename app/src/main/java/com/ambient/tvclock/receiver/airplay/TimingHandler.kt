package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Apple AirPlay NTP timing — passive replies (0xD2→0xD3) and active sync to the sender
 * (UxPlay [raop_ntp.c]: send 0xD2 to SETUP timingPort, read response).
 */
class TimingHandler {

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var peer: InetSocketAddress? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var rtpClockOffsetUs: Long = 0L
        private set

    @Volatile
    private var loggedFirstPassive = false
    @Volatile
    private var loggedFirstActive = false

    private var lastClientRefNtp: Long = 0L
    private var lastRecvNtp: Long = 0L
    private var clientTimeReceived = false

    fun start(scope: CoroutineScope, port: Int = TIMING_PORT) {
        if (running) return
        running = true
        this.scope = scope
        scope.launch(Dispatchers.IO) {
            runLoop(scope, port)
        }
    }

    /** Start sending NTP requests to the Mac (SETUP timingPort). */
    fun beginPeerSync(peerAddress: InetAddress, peerPort: Int, fromSocket: Socket? = null) {
        val normalized = AirPlayNetwork.normalizePeerAddress(peerAddress, fromSocket)
        peer = InetSocketAddress(normalized, peerPort)
        loggedFirstActive = false
        Logger.i("NTP active peer: ${AirPlayNetwork.formatAddress(normalized)}:$peerPort")
        scope?.launch(Dispatchers.IO) {
            val sock = socket ?: return@launch
            repeat(INITIAL_BURST) {
                flushSocket(sock)
                sendActiveRequest(sock)
                drainResponses(sock, maxPackets = 8)
                delay(150)
            }
        }
    }

    fun clearPeer() {
        peer = null
        clientTimeReceived = false
    }

    fun stop() {
        running = false
        clearPeer()
        try {
            socket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing timing socket (non-fatal)", e)
        }
        socket = null
    }

    private fun runLoop(scope: CoroutineScope, port: Int) {
        try {
            val sock = AirPlayNetwork.openTimingSocket(port)
            socket = sock
            sock.soTimeout = RECV_TIMEOUT_MS
            Logger.i("Timing/NTP listening on UDP port $port")

            while (scope.isActive && running) {
                if (peer != null) {
                    flushSocket(sock)
                    sendActiveRequest(sock)
                    drainResponses(sock, maxPackets = 8)
                } else {
                    try {
                        val buf = ByteArray(PACKET_SIZE)
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        handleIncoming(sock, packet, currentNtpTimestamp())
                    } catch (_: SocketTimeoutException) {
                    }
                }
                Thread.sleep(ACTIVE_INTERVAL_MS)
            }
        } catch (e: Exception) {
            if (running) Logger.e("Timing handler error (unexpected)", e)
            else Logger.d("Timing socket closed (expected during shutdown)")
        }
    }

    private fun sendActiveRequest(sock: DatagramSocket) {
        val target = peer ?: return
        val request = ByteArray(PACKET_SIZE)
        request[0] = 0x80.toByte()
        request[1] = REQUEST_TYPE.toByte()
        request[2] = 0
        request[3] = 0x07
        if (clientTimeReceived) {
            writeNtp64(request, 8, lastClientRefNtp)
            writeNtp64(request, 16, lastRecvNtp)
        }
        writeNtp64(request, 24, currentNtpTimestamp())
        try {
            sock.send(DatagramPacket(request, request.size, target))
            Logger.d("NTP request → ${AirPlayNetwork.formatAddress(target.address)}:${target.port}")
        } catch (e: Exception) {
            Logger.w("NTP request send failed: ${e.message}")
        }
    }

    private fun drainResponses(sock: DatagramSocket, maxPackets: Int) {
        repeat(maxPackets) {
            try {
                val buf = ByteArray(RESPONSE_SIZE)
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                handleIncoming(sock, packet, currentNtpTimestamp())
            } catch (_: SocketTimeoutException) {
                return
            }
        }
    }

    private fun handleIncoming(sock: DatagramSocket, packet: DatagramPacket, receiveNtp: Long) {
        if (packet.length < PACKET_SIZE) return
        when (packet.data[1].toInt() and 0xFF) {
            REQUEST_TYPE -> handlePassiveProbe(sock, packet, receiveNtp)
            RESPONSE_TYPE -> handleActiveResponse(packet, receiveNtp)
            else -> Logger.d(
                "NTP packet type 0x${(packet.data[1].toInt() and 0xFF).toString(16)} len=${packet.length}"
            )
        }
    }

    private fun handlePassiveProbe(sock: DatagramSocket?, packet: DatagramPacket, receiveNtp: Long) {
        if (packet.length < PACKET_SIZE) return
        val data = packet.data
        if ((data[1].toInt() and 0xFF) != REQUEST_TYPE) return
        if (!loggedFirstPassive) {
            loggedFirstPassive = true
            Logger.i(
                "Timing probe from ${AirPlayNetwork.formatAddress(packet.address)}:${packet.port} " +
                    "(seq=${data[2].toInt() and 0xFF},${data[3].toInt() and 0xFF})"
            )
        }
        val refSec = readUint32(data, 24)
        val refFrac = readUint32(data, 28)
        val response = ByteArray(PACKET_SIZE)
        response[0] = 0x80.toByte()
        response[1] = RESPONSE_TYPE.toByte()
        response[2] = data[2]
        response[3] = data[3]
        writeUint32(response, 8, refSec)
        writeUint32(response, 12, refFrac)
        writeUint32(response, 16, (receiveNtp ushr 32).toInt())
        writeUint32(response, 20, (receiveNtp and 0xFFFFFFFFL).toInt())
        val transmitNtp = currentNtpTimestamp()
        writeUint32(response, 24, (transmitNtp ushr 32).toInt())
        writeUint32(response, 28, (transmitNtp and 0xFFFFFFFFL).toInt())
        if (sock != null) {
            try {
                sock.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (e: Exception) {
                Logger.w("Failed to send timing response: ${e.message}")
            }
        }
        val senderSendUs = ntpToUs(refSec.toLong() and 0xFFFFFFFFL, refFrac.toLong() and 0xFFFFFFFFL)
        val ourReceiveUs = ntpToUs(receiveNtp ushr 32, receiveNtp and 0xFFFFFFFFL)
        rtpClockOffsetUs = ourReceiveUs - senderSendUs
    }

    /** UxPlay raop_ntp_thread: process response to our 0xD2 request. */
    private fun handleActiveResponse(packet: DatagramPacket, recvNtp: Long) {
        if (packet.length < PACKET_SIZE) return
        val data = packet.data
        if (!loggedFirstActive) {
            loggedFirstActive = true
            Logger.i(
                "NTP sync response from ${AirPlayNetwork.formatAddress(packet.address)}:" +
                    "${packet.port} (${packet.length} bytes)"
            )
        }
        clientTimeReceived = true
        lastClientRefNtp = readNtp64(data, 24)
        lastRecvNtp = recvNtp

        val t0 = readNtp64(data, 8)
        val t1 = readNtp64(data, 16)
        val t2 = readNtp64(data, 24)
        val t3 = recvNtp
        val offsetNs = ((t1 - t0) + (t2 - t3)) / 2
        rtpClockOffsetUs = offsetNs / 1000
        Logger.d("NTP sync offset≈${rtpClockOffsetUs}µs")
    }

    private fun flushSocket(sock: DatagramSocket) {
        val buf = ByteArray(1)
        val packet = DatagramPacket(buf, 1)
        sock.soTimeout = 1
        try {
            while (true) {
                sock.receive(packet)
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        }
        sock.soTimeout = RECV_TIMEOUT_MS
    }

    internal fun handleProbe(packet: DatagramPacket, receiveNtp: Long) {
        val sock = socket
        if (sock != null) {
            handlePassiveProbe(sock, packet, receiveNtp)
        } else {
            handlePassiveProbe(null, packet, receiveNtp)
        }
    }

    companion object {
        const val TIMING_PORT = 6002

        private const val PACKET_SIZE = 32
        private const val RESPONSE_SIZE = 128
        private const val REQUEST_TYPE = 0xD2
        private const val RESPONSE_TYPE = 0xD3
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L
        private const val RECV_TIMEOUT_MS = 300
        private const val ACTIVE_INTERVAL_MS = 3_000L
        private const val INITIAL_BURST = 8

        fun currentNtpTimestamp(): Long {
            val millis = System.currentTimeMillis()
            val seconds = millis / 1000L + NTP_EPOCH_OFFSET
            val fraction = (millis % 1000L) * 0x1_0000_0000L / 1000L
            return (seconds shl 32) or fraction
        }

        fun ntpToUs(ntpSeconds: Long, ntpFraction: Long): Long {
            val unixSeconds = ntpSeconds - NTP_EPOCH_OFFSET
            val subUs = ntpFraction * 1_000_000L / 0x1_0000_0000L
            return unixSeconds * 1_000_000L + subUs
        }

        fun readUint32(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        fun writeUint32(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value ushr 24).toByte()
            data[offset + 1] = (value ushr 16).toByte()
            data[offset + 2] = (value ushr 8).toByte()
            data[offset + 3] = value.toByte()
        }

        fun readNtp64(data: ByteArray, offset: Int): Long {
            val hi = readUint32(data, offset).toLong() and 0xFFFFFFFFL
            val lo = readUint32(data, offset + 4).toLong() and 0xFFFFFFFFL
            return (hi shl 32) or lo
        }

        fun writeNtp64(data: ByteArray, offset: Int, ntp: Long) {
            writeUint32(data, offset, (ntp ushr 32).toInt())
            writeUint32(data, offset + 4, (ntp and 0xFFFFFFFFL).toInt())
        }
    }
}
