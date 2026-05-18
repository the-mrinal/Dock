package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives AirPlay mirror video over TCP (port [MIRROR_PORT]) — ported from UxPlay raop_rtp_mirror.c.
 */
class MirrorTcpReceiver(
    private val onSpsPps: (ByteArray) -> Unit,
    private val onNalUnit: (ByteArray, Long) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var mirrorBuffer: MirrorBuffer? = null
    private var pendingSpsPps: ByteArray? = null

    fun start(scope: CoroutineScope, aesKey: ByteArray, streamConnectionId: String) {
        stop()
        mirrorBuffer = MirrorBuffer(aesKey).apply { initAes(streamConnectionId) }
        running = true
        // Bind synchronously so SETUP response is not sent before port 7100 is listening (UxPlay).
        val server = AirPlayNetwork.bindMirrorServer(MIRROR_PORT)
        serverSocket = server
        scope.launch(Dispatchers.IO) {
            try {
                while (running && scope.isActive) {
                    val client = server.accept()
                    configureClientSocket(client)
                    Logger.i(
                        "Mirror client connected: ${AirPlayNetwork.formatAddress(client.inetAddress)}:${client.port}"
                    )
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) Logger.e("Mirror TCP server error", e)
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        mirrorBuffer = null
    }

    /** Updates AES-CTR keys when macOS sends streamConnectionID in a follow-up SETUP. */
    fun setStreamConnectionId(streamConnectionId: String) {
        mirrorBuffer?.initAes(streamConnectionId)
        Logger.i("Mirror AES reinitialized for streamId=$streamConnectionId")
    }

    private fun configureClientSocket(socket: Socket) {
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    private fun handleClient(socket: Socket) {
        val input: InputStream = socket.getInputStream()
        val header = ByteArray(128)
        var pendingSps: ByteArray? = null
        var packetIndex = 0L
        var totalBytes = 0L
        Logger.i("Mirror: entering read loop, soTimeout=${socket.soTimeout}ms")

        try {
            while (running) {
                if (!readFully(input, header, 128, label = "header#$packetIndex")) break
                val payloadSize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val packetType = header[4]
                val ptsRaw = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
                val ptsUs = ptsRaw / 1000
                val typeUnsigned = packetType.toInt() and 0xFF
                totalBytes += 128
                Logger.i(
                    "Mirror: hdr#$packetIndex type=0x${typeUnsigned.toString(16)} " +
                        "payloadSize=$payloadSize pts=$ptsUs totalBytes=$totalBytes"
                )

                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: rejecting hdr#$packetIndex payloadSize=$payloadSize (out of range)")
                    packetIndex++
                    continue
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize, label = "payload#$packetIndex")) break
                totalBytes += payloadSize

                when (typeUnsigned) {
                    0x00 -> {
                        val decrypted = ByteArray(payloadSize)
                        mirrorBuffer?.decrypt(payload, decrypted, payloadSize)
                        val prepend = pendingSps
                        pendingSps = null
                        emitNalUnits(decrypted, ptsUs, prepended = prepend)
                    }
                    0x01 -> {
                        val withStart = parseH264CodecData(payload)
                        if (withStart != null) {
                            pendingSps = withStart
                            onSpsPps(withStart)
                        }
                    }
                    else -> Logger.d("Mirror: unknown packet type 0x${typeUnsigned.toString(16)} size=$payloadSize")
                }
                packetIndex++
            }
            Logger.i("Mirror: read loop exited cleanly, packets=$packetIndex bytes=$totalBytes")
        } catch (e: Exception) {
            Logger.e("Mirror client error at packet#$packetIndex totalBytes=$totalBytes", e)
        } finally {
            socket.close()
        }
    }

    /**
     * Parse H.264 SPS+PPS from a mirror type 0x01 codec data payload (UxPlay raop_rtp_mirror.c).
     *
     * Layout (no start codes inside — fixed offsets + big-endian length prefixes):
     *   [0..5]                 6-byte AVCC preamble
     *   [6..7]                 SPS size (u16 BE)
     *   [8..8+sps_size-1]      SPS NAL bytes (first byte 0x67)
     *   [sps_size+9..sps+10]   PPS size (u16 BE)
     *   [sps_size+11..]        PPS NAL bytes (first byte 0x68)
     *
     * Returns SPS/PPS wrapped with 0x00 0x00 0x00 0x01 start codes so the downstream
     * decoder-init can parse them with the same start-code scanner used for video NALs.
     */
    private fun parseH264CodecData(payload: ByteArray): ByteArray? {
        if (payload.size < 11) {
            Logger.w("Mirror: 0x01 codec data too small (${payload.size}B)")
            return null
        }
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        if (8 + spsSize + 3 > payload.size) {
            Logger.w("Mirror: SPS size $spsSize doesn't fit in ${payload.size}B payload")
            return null
        }
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsSize = ((payload[spsSize + 9].toInt() and 0xFF) shl 8) or
            (payload[spsSize + 10].toInt() and 0xFF)
        if (spsSize + 11 + ppsSize > payload.size) {
            Logger.w("Mirror: PPS size $ppsSize doesn't fit after SPS (payload=${payload.size}B)")
            return null
        }
        val pps = payload.copyOfRange(spsSize + 11, spsSize + 11 + ppsSize)
        val spsNalType = sps.firstOrNull()?.toInt()?.and(0x1F)
        val ppsNalType = pps.firstOrNull()?.toInt()?.and(0x1F)
        Logger.i(
            "Mirror: parsed SPS=${sps.size}B (nal_type=$spsNalType) " +
                "PPS=${pps.size}B (nal_type=$ppsNalType)"
        )
        return byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
    }

    /**
     * Emit NAL units from a decrypted mirror video payload.
     *
     * The decrypted 0x00 payload from macOS/iOS is **AVCC**-framed: each NAL unit is
     * preceded by a 4-byte big-endian length, with no start codes. UxPlay (`raop_rtp_mirror.c`)
     * walks the buffer, overwrites each length prefix in place with the Annex-B start code
     * `00 00 00 01`, and hands the whole buffer to the decoder. We do the same and emit each
     * NAL individually so [VideoDecoder.decodeNalUnit] can feed one MediaCodec input buffer
     * per NAL.
     *
     * If [prepended] is non-null (the parsed SPS+PPS waiting from a recent 0x01 packet),
     * it is emitted first so the keyframe that follows has its parameter sets in front.
     */
    private fun emitNalUnits(data: ByteArray, ptsUs: Long, prepended: ByteArray? = null) {
        if (prepended != null) emitAnnexB(prepended, ptsUs)
        var off = 0
        var count = 0
        while (off + 4 <= data.size) {
            val length = ((data[off].toInt() and 0xFF) shl 24) or
                ((data[off + 1].toInt() and 0xFF) shl 16) or
                ((data[off + 2].toInt() and 0xFF) shl 8) or
                (data[off + 3].toInt() and 0xFF)
            if (length <= 0 || off + 4 + length > data.size) {
                Logger.w(
                    "Mirror: AVCC NAL length $length at off=$off exceeds buffer ${data.size}B — stopping"
                )
                break
            }
            val nal = ByteArray(4 + length).apply {
                this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1
                System.arraycopy(data, off + 4, this, 4, length)
            }
            onNalUnit(nal, ptsUs)
            off += 4 + length
            count++
        }
        if (count == 0 && data.size > 0) {
            Logger.w("Mirror: no NAL units extracted from ${data.size}B payload")
        }
    }

    /** Emit a buffer that already contains one or more Annex-B framed NAL units. */
    private fun emitAnnexB(data: ByteArray, ptsUs: Long) {
        var i = 0
        while (i + 4 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                var next = i + 4
                while (next + 4 <= data.size) {
                    if (data[next] == 0.toByte() && data[next + 1] == 0.toByte() &&
                        data[next + 2] == 0.toByte() && data[next + 3] == 1.toByte()
                    ) break
                    next++
                }
                if (next + 4 > data.size) next = data.size
                onNalUnit(data.copyOfRange(i, next), ptsUs)
                i = next
            } else {
                i++
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int, label: String = ""): Boolean {
        var read = 0
        var firstChunkLogged = false
        while (read < len) {
            val n = try {
                input.read(buf, read, len - read)
            } catch (e: java.net.SocketTimeoutException) {
                Logger.w("Mirror: read timeout on $label after ${read}B of ${len}B")
                throw e
            }
            if (n <= 0) {
                Logger.w("Mirror: EOF on $label after ${read}B of ${len}B (read returned $n)")
                return false
            }
            if (!firstChunkLogged) {
                Logger.i("Mirror: first chunk of $label = ${n}B (need ${len}B)")
                firstChunkLogged = true
            }
            read += n
        }
        return true
    }

    companion object {
        const val MIRROR_PORT = 7100
        private const val MAX_PAYLOAD = 8 * 1024 * 1024
    }
}
