package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

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
 * HOW (this iteration): Bind a TCP server, accept the iOS connection, and
 * log every byte that arrives so we can see what YouTube is actually
 * sending. We don't decrypt yet — the goal is visibility first; if the
 * traffic looks like an encrypted plist stream we layer on AES decryption
 * (same key as the SETUP ekey) in the next pass.
 */
class AirPlayEventChannel {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptedSocket: Socket? = null
    private var acceptJob: Job? = null

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
        try {
            val input = socket.getInputStream()
            val buf = ByteArray(8192)
            while (!socket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) {
                    Logger.i("Event channel: client EOF (n=$n)")
                    break
                }
                // Hex-dump the first 96 bytes so we can recognise plist or
                // encrypted-blob shapes without flooding the log on every
                // heartbeat. Decryption comes next iteration.
                val preview = buf.copyOf(minOf(n, 96)).joinToString(" ") { "%02x".format(it) }
                Logger.i("Event channel: rx ${n}B  first96=$preview")
            }
        } catch (e: Exception) {
            Logger.e("Event channel: client read error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
            if (acceptedSocket === socket) acceptedSocket = null
        }
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
}
