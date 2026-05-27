package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AirPlayEventChannel — Listens on the AirPlay 2 event port.
 *
 * WHY: AirPlay 2 senders (iOS, macOS) include an `eventPort` in the master SETUP
 * response and open a long-lived TCP connection to it. The channel carries
 * encrypted out-of-band control plists — volume changes, metadata updates,
 * track-change events, AND (the part we care about for YouTube) the actual
 * handoff request that tells the receiver to start playing a specific URL.
 *
 * Until we exposed this channel we advertised `eventPort: 0` ("no event
 * channel"), which iOS interprets as a degraded receiver that doesn't merit
 * a video URL handoff. The receiver would get stuck after RECORD waiting
 * indefinitely while the iPhone shows "Connected to AirPlay, loading…".
 *
 * HOW: Bind a TCP server, accept the iOS connection, and parse the
 * length-prefixed ChaCha20-Poly1305 frames defined in the AirPlay 2 spec
 * (openairplay §control-channel-encryption). When [setSecret] has been called
 * with the pair-verify ECDH shared secret, we attempt to decrypt each frame
 * with the HKDF-SHA-512 derived "Events-Read-Encryption-Key"; the decrypted
 * bytes are logged so we can identify the plist payloads YouTube uses to
 * coordinate mirror codec readiness (issue #17). When no secret is available
 * we fall back to dumping raw bytes — same diagnostic value, less plaintext.
 */
class AirPlayEventChannel {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptedSocket: Socket? = null
    private var acceptJob: Job? = null

    @Volatile private var readKey: ByteArray? = null
    @Volatile private var writeKey: ByteArray? = null
    @Volatile private var readNonceCounter: Long = 0L

    /**
     * Starts the event channel server. Picks an ephemeral port so we don't
     * collide with other AirPlay sockets bound on the device.
     *
     * Returns the bound port; pass it back to the iOS sender via the
     * master SETUP response so it knows where to connect.
     */
    fun start(scope: CoroutineScope): Int {
        if (serverSocket != null) return serverSocket!!.localPort
        val socket = ServerSocket(0) // ephemeral
        serverSocket = socket
        Logger.i("AirPlay event channel listening on port ${socket.localPort}")
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !socket.isClosed) {
                    val client = socket.accept()
                    Logger.i("Event channel: client connected from ${client.inetAddress}")
                    acceptedSocket = client
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isActive) Logger.e("Event channel server error", e)
            }
        }
        return socket.localPort
    }

    private fun handleClient(socket: Socket) {
        val input = socket.getInputStream()
        readNonceCounter = 0L
        try {
            if (readKey != null) {
                readFramedAndDecrypt(input)
            } else {
                dumpRaw(input)
            }
        } catch (e: Exception) {
            Logger.e("Event channel: client read error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
            if (acceptedSocket === socket) acceptedSocket = null
        }
    }

    /**
     * Parses AirPlay 2 event-channel frames as `[2B BE length][ciphertext][16B tag]`
     * and ChaCha20-Poly1305-decrypts each one with the length prefix as AAD.
     *
     * If decryption fails (wrong derivation, frame misaligned, etc.) we log
     * the failure and the raw bytes so a future iteration can re-derive
     * without losing the trace. We do NOT desynchronize on the first failure
     * — the nonce counter is incremented per attempted frame so the rest of
     * the session stays aligned.
     */
    private fun readFramedAndDecrypt(input: InputStream) {
        val key = readKey ?: return dumpRaw(input)
        val header = ByteArray(2)
        while (!Thread.currentThread().isInterrupted) {
            if (!readFully(input, header, 2)) {
                Logger.i("Event channel: EOF before next length prefix")
                break
            }
            val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            if (length <= 0 || length > MAX_FRAME) {
                Logger.w(
                    "Event channel: frame length $length out of range — bailing to raw mode"
                )
                break
            }
            val body = ByteArray(length + 16)
            if (!readFully(input, body, body.size)) {
                Logger.w("Event channel: EOF mid-frame (expected ${body.size}B body)")
                break
            }
            val nonce = chacha20Nonce(readNonceCounter)
            val cipher = ChaCha20Poly1305()
            cipher.init(false, ParametersWithIV(KeyParameter(key), nonce))
            cipher.processAADBytes(header, 0, header.size)
            val plain = ByteArray(length)
            try {
                val written = cipher.processBytes(body, 0, body.size, plain, 0)
                cipher.doFinal(plain, written)
                val previewLen = minOf(plain.size, PLAIN_PREVIEW_BYTES)
                val preview = plain.copyOf(previewLen).joinToString(" ") { "%02x".format(it) }
                val ascii = plain.copyOf(previewLen).joinToString("") {
                    val c = it.toInt() and 0xFF
                    if (c in 0x20..0x7E) c.toChar().toString() else "."
                }
                Logger.i(
                    "Event channel: nonce=$readNonceCounter " +
                        "decrypted ${plain.size}B  hex=$preview  ascii=$ascii"
                )
            } catch (e: Exception) {
                val previewLen = minOf(body.size, 64)
                val preview = body.copyOf(previewLen).joinToString(" ") { "%02x".format(it) }
                Logger.w(
                    "Event channel: ChaCha20 decrypt failed at nonce=$readNonceCounter " +
                        "(${e.message}). Raw ${body.size}B first${previewLen}B=$preview"
                )
            }
            readNonceCounter++
        }
    }

    /**
     * Fallback when no read key has been derived: hex-dump the first
     * [RAW_PREVIEW_BYTES] of every read so we still get diagnostic visibility
     * into framing without claiming a decryption we can't perform.
     */
    private fun dumpRaw(input: InputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) {
                Logger.i("Event channel: client EOF (n=$n)")
                break
            }
            val previewLen = minOf(n, RAW_PREVIEW_BYTES)
            val preview = buf.copyOf(previewLen).joinToString(" ") { "%02x".format(it) }
            Logger.i("Event channel: rx ${n}B  first${previewLen}B=$preview (no key yet — raw)")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /**
     * Builds a 12-byte ChaCha20-Poly1305 nonce from a 64-bit message counter.
     *
     * AirPlay 2 follows the convention used by shairport-sync / UxPlay: the
     * first 4 bytes are zero, the last 8 bytes are the counter in little-
     * endian. The counter starts at 0 for each direction and increments
     * once per frame.
     */
    private fun chacha20Nonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        return nonce
    }

    /**
     * Derives the per-direction ChaCha20-Poly1305 keys from the pair-verify
     * ECDH shared secret. Called from [com.ambient.tvclock.receiver.airplay.AirPlayReceiver]
     * once SETUP has completed so we already have the secret pair-verify
     * produced. Subsequent connections reuse the derived keys.
     *
     * The derivation matches shairport-sync's
     * `pair_verify_get_signed_data` follow-on: HKDF-SHA-512 with no salt
     * bytes — the label that distinguishes read/write is passed via `info`.
     */
    fun setSecret(ecdhSecret: ByteArray) {
        readKey = hkdf(ecdhSecret, EVENTS_SALT, EVENTS_READ_INFO, 32)
        writeKey = hkdf(ecdhSecret, EVENTS_SALT, EVENTS_WRITE_INFO, 32)
        readNonceCounter = 0L
        Logger.i("Event channel: ChaCha20 keys derived (read+write, 32B each)")
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        gen.generateBytes(out, 0, length)
        return out
    }

    fun stop() {
        try {
            acceptedSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Event channel stop error (non-fatal)", e)
        }
        acceptedSocket = null
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    fun port(): Int = serverSocket?.localPort ?: 0

    companion object {
        // openairplay §control-channel: 16-bit BE length prefix per frame.
        // Cap at 64 KiB to bound buffer allocation; AirPlay event payloads
        // are well under 4 KiB in every trace we've seen.
        private const val MAX_FRAME = 64 * 1024
        private const val PLAIN_PREVIEW_BYTES = 128
        private const val RAW_PREVIEW_BYTES = 96
        private val EVENTS_SALT = "Events-Salt".toByteArray(Charsets.UTF_8)
        private val EVENTS_READ_INFO = "Events-Read-Encryption-Key".toByteArray(Charsets.UTF_8)
        private val EVENTS_WRITE_INFO = "Events-Write-Encryption-Key".toByteArray(Charsets.UTF_8)
    }
}
