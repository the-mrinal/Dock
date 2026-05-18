package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * WHY: AirPlay uses RTSP (Real Time Streaming Protocol) to negotiate and control
 * a streaming session. Before any video or audio arrives, macOS and PhairPlay
 * must exchange a series of RTSP messages to agree on codecs, ports, and encryption keys.
 *
 * HOW: Listens on TCP port 7000. When a macOS device connects, it handles the
 * RTSP message exchange, extracts stream parameters from the SDP body, and then
 * creates [VideoDecoder] and [AudioPlayer] instances to handle the media.
 *
 * The RTSP state machine:
 *   IDLE → (client connects) → CONNECTED → (OPTIONS/SETUP/ANNOUNCE) → NEGOTIATING
 *   NEGOTIATING → (RECORD received) → STREAMING → (TEARDOWN or disconnect) → IDLE
 *
 * Example:
 *   val handler = RtspHandler(
 *       videoSurface = surface,
 *       onStreamingStarted = { showVideo() },
 *       onStreamingStopped = { showWaiting() }
 *   )
 *   handler.start(coroutineScope)
 *   handler.stop()
 */
open class RtspHandler(
    // Called lazily when RECORD is received — Surface is ready by then
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val controlHandler: AirPlayControlHandler,
    private val onMirrorRecord: () -> Unit = {}
) {

    // The server socket that accepts incoming AirPlay connections on port 7000
    private var serverSocket: ServerSocket? = null

    // The currently active client connection (only one at a time, per REQUIREMENTS.md FR-10)
    @Volatile
    private var activeClient: Socket? = null

    // Flag to signal that we should stop accepting new connections
    @Volatile
    private var running = false

    // RTSP CSeq counter — each RTSP response must echo back the request's CSeq number
    private var currentCSeq: Int = 0

    // Parsed SDP from the most recent ANNOUNCE — stored for use in SETUP and RECORD
    @Volatile
    private var currentSession: SessionDescription? = null

    // Counter for SETUP calls: first is typically video, second is audio
    private var setupCount = 0

    /** True after macOS plist SETUP (ekey) — RECORD/TEARDOWN use rtsp:// URIs, not /paths. */
    @Volatile
    private var macOsMirrorHandshake = false

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /**
     * Starts the RTSP server.
     *
     * Opens TCP port 7000 and begins accepting connections in a background coroutine.
     * This method returns immediately; the actual work runs asynchronously.
     *
     * @param scope The [CoroutineScope] to launch the server coroutine in.
     *              Should be the AirPlayReceiver's SupervisorJob scope so that
     *              a crash here doesn't kill the mDNS service.
     */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /**
     * Stops the RTSP server.
     *
     * Closes the server socket (which causes the accept() call to throw, ending the loop),
     * disconnects any active client, and releases media resources.
     *
     * Safe to call from any thread.
     */
    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    /**
     * The main server loop. Listens on port 7000 and handles one client at a time.
     *
     * SECURITY: Only one client is accepted at a time (FR-07). A second connection
     * attempt while a client is active receives a 503 response and is disconnected.
     *
     * @param scope Used to check if the coroutine is still active (for graceful shutdown).
     */
    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(RTSP_PORT)
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                // accept() blocks until a client connects — this is intentional
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${AirPlayNetwork.formatAddress(clientSocket.inetAddress)}")

                // SECURITY: Only one active client at a time (see FR-07)
                if (activeClient != null && !activeClient!!.isClosed) {
                    Logger.w("Rejecting second client — already streaming")
                    sendServiceUnavailable(clientSocket)
                    clientSocket.close()
                    continue
                }

                activeClient = clientSocket
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            // SocketException is thrown when serverSocket.close() is called from stop()
            // This is expected behavior during shutdown, not a real error
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    /**
     * Handles all RTSP communication with a single connected client.
     *
     * Phase 1 (RTSP): reads RTSP text requests until RECORD is received.
     * Phase 2 (RTP):  hands the raw [InputStream] to [RtpInterleaved.readLoop]
     *                 for binary `$`-framed RTP reading.
     *
     * WHY raw InputStream (not BufferedReader): after RECORD the connection switches
     * from text to binary. A BufferedReader would consume and discard binary data in
     * its internal read-ahead buffer, causing the first RTP frames to be lost.
     *
     * @param socket The connected client socket.
     */
    private fun peerAddress(socket: Socket): InetAddress =
        AirPlayNetwork.normalizePeerAddress(socket.inetAddress, socket)

    internal fun handleClient(socket: Socket) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()
        var mirrorMode = false
        // Only fire onStreamingStopped() in `finally` if this connection actually
        // entered streaming mode (RECORD with 200). Without this gate, every
        // pair-verify-only probe triggers a full teardown — including mDNS
        // restart — which makes the device flicker in the sender's picker list
        // and provokes a re-pair storm after the iPhone's normal TEARDOWN.
        var streamingStarted = false

        try {
            // ── Phase 1: AirPlay control + RTSP handshake ───────────────────
            while (running && !socket.isClosed) {
                val request = readAirPlayRequest(inputStream) ?: break
                val response = if (isAirPlayControlRequest(request)) {
                    val r = controlHandler.handle(request, peerAddress(socket), socket)
                    if (request.method == "SETUP" && r.statusCode == 200 && isBinaryPlist(request.bodyBytes)) {
                        macOsMirrorHandshake = true
                    }
                    if (request.method == "RECORD" && r.statusCode == 200) {
                        mirrorMode = true
                        streamingStarted = true
                        onMirrorRecord()
                    }
                    r
                } else {
                    val r = routeRequest(request, outputStream)
                    if (request.method == "RECORD" && r.statusCode == 200) {
                        streamingStarted = true
                    }
                    r
                }
                sendResponse(outputStream, request.protocol, response)

                // Classic RTSP interleaved path after RECORD
                if (!mirrorMode && request.method == "RECORD" && response.statusCode == 200) {
                    Logger.d("RTSP handshake complete — switching to RTP interleaved mode")
                    break
                }
            }

            if (mirrorMode) {
                // Mirror video uses a separate TCP port; keep connection open for TEARDOWN/feedback
                while (running && !socket.isClosed) {
                    val request = readAirPlayRequest(inputStream) ?: break
                    if (!isAirPlayControlRequest(request)) continue
                    val response = controlHandler.handle(request, peerAddress(socket), socket)
                    sendResponse(outputStream, request.protocol, response)
                    if (request.method == "TEARDOWN") break
                }
                return
            }

            // ── Phase 2: RTP binary read loop ────────────────────────────────
            // Only start if the session was established (ANNOUNCE was parsed)
            val session = currentSession
            if (session != null && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    }
                )
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("Client disconnected (streaming=$streamingStarted)")
            socket.close()
            activeClient = null
            currentSession = null
            setupCount = 0
            macOsMirrorHandshake = false
            if (streamingStarted) onStreamingStopped()
        }
    }

    /**
     * Reads a complete RTSP request from the raw [inputStream].
     *
     * Uses byte-by-byte reading to detect CRLF line endings without consuming
     * binary data into a buffered reader's internal buffer — critical for the
     * switch to RTP interleaved mode after RECORD.
     *
     * SECURITY: Total message size capped at [MAX_MESSAGE_BYTES].
     *
     * @return Parsed [RtspRequest], or null on clean EOF / oversized message.
     */
    private fun isAirPlayControlRequest(request: RtspRequest): Boolean {
        if (request.uri.startsWith("/")) return true
        if (request.method == "SETUP" && isBinaryPlist(request.bodyBytes)) return true
        if (macOsMirrorHandshake && request.method in MACOS_MIRROR_CONTROL_METHODS) return true
        return false
    }

    private fun isBinaryPlist(body: ByteArray): Boolean =
        body.size >= 6 && body[0] == 'b'.code.toByte() &&
            String(body, 0, 6, Charsets.US_ASCII) == "bplist"

    private fun readAirPlayRequest(inputStream: InputStream): RtspRequest? {
        val requestLine = readLine(inputStream) ?: return null
        if (requestLine.isBlank()) return readAirPlayRequest(inputStream)

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed request line: '$requestLine'")
            return null
        }
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]

        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLine.length

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) break  // blank line = end of headers

            totalBytes += line.length
            if (totalBytes > MAX_MESSAGE_BYTES) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] =
                    line.substring(colonIndex + 1).trim()
            }
        }

        currentCSeq = headers["CSeq"]?.toIntOrNull() ?: 0

        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (contentLength in 1..MAX_MESSAGE_BYTES) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inputStream.read(buf, read, contentLength - read)
                if (n == -1) return null
                read += n
            }
            buf
        } else if (contentLength > MAX_MESSAGE_BYTES) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        } else {
            byteArrayOf()
        }
        val body = if (bodyBytes.isEmpty()) "" else String(bodyBytes, Charsets.UTF_8)

        return RtspRequest(
            method = method,
            uri = uri,
            protocol = protocol,
            headers = headers,
            body = body,
            bodyBytes = bodyBytes
        )
    }

    /**
     * Reads a single CRLF-terminated line from [inputStream], byte by byte.
     *
     * Returns null on EOF. Returns an empty string for a blank line (only `\r\n`).
     * The trailing `\r\n` is stripped from the result.
     */
    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue  // skip CR
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > MAX_MESSAGE_BYTES) return null  // safety valve
        }
    }

    /**
     * Routes an [RtspRequest] to the appropriate handler method.
     *
     * Each RTSP method has a specific role in the AirPlay protocol:
     * - OPTIONS:  macOS asks "what can you do?" — we reply with our supported methods
     * - ANNOUNCE: macOS sends the SDP describing codecs and encryption keys
     * - SETUP:    macOS requests that we set up a media channel (video or audio)
     * - RECORD:   macOS says "start sending media now"
     * - TEARDOWN: macOS says "stop and clean up"
     * - GET/SET_PARAMETER: used for keep-alive and metadata updates
     *
     * @return An [RtspResponse] to send back to the client.
     */
    private fun routeRequest(request: RtspRequest, outputStream: OutputStream): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            "SETUP"         -> handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /**
     * Handles OPTIONS — macOS asks "what RTSP methods do you support?"
     *
     * We respond with the list of methods PhairPlay supports. This is the
     * first message in every AirPlay session.
     *
     * Exposed as `internal open` so unit tests can call it via [TestableRtspHandler]
     * without requiring a real network socket.
     */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /**
     * Handles ANNOUNCE — macOS/iOS sends the SDP body describing codecs, ports, and encryption.
     *
     * We parse the SDP with [SdpParser] and store the result in [currentSession].
     * The session is used later in SETUP and RECORD to configure the media pipeline.
     *
     * Security: if SDP parsing fails completely, we return 400 Bad Request.
     */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Extracts a readable sender name from the RTSP `User-Agent` header (S6-1).
     * "AirPlay/376.1.1" → "AirPlay", "iTunes/12.12" → "iTunes", absent → fallback.
     */
    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    /**
     * Handles SETUP — allocates a media channel.
     * Responds with TCP interleaved transport for video, UDP for audio.
     */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        // Determine if this SETUP is for video or audio based on order and session info
        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            // Video: interleaved over the existing RTSP TCP connection
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            // Audio: UDP to the fixed port; timing-port tells the sender where to send NTP probes
            "RTP/AVP/UDP;unicast;" +
            "client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "timing-port=${TimingHandler.TIMING_PORT}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /**
     * Handles RECORD — macOS/iOS says "start sending media now".
     *
     * Invokes [onStreamingStarted] with the parsed [SessionDescription] so the caller
     * can wire up [VideoDecoder] and/or [AudioPlayer] as appropriate.
     * For audio-only streams, only [AudioPlayer] is started.
     */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        val session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN — macOS says "stop and clean up".
     *
     * The streaming session is over. We clean up resources and return to WAITING state.
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        Logger.i("TEARDOWN received — streaming stopping")
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles GET_PARAMETER — used by macOS as a keep-alive ping.
     * We respond with 200 OK and an empty body.
     */
    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles SET_PARAMETER — macOS may send metadata (volume, track info, etc.).
     * We acknowledge receipt but currently ignore the content.
     */
    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        Logger.d("SET_PARAMETER: ${request.body}")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles any unrecognized RTSP method.
     * Returns 501 Not Implemented, which is the correct RTSP response for unknown methods.
     */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown RTSP method: ${request.method}")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Sends an RTSP response to the client.
     *
     * RTSP response format:
     *   RTSP/1.0 <statusCode> <statusMessage>\r\n
     *   CSeq: <n>\r\n
     *   <header>: <value>\r\n
     *   \r\n
     *   [optional body]
     *
     * @param writer The output writer to the client socket.
     * @param response The [RtspResponse] to serialize and send.
     */
    private fun sendResponse(outputStream: OutputStream, protocol: String, response: RtspResponse) {
        val bodyBytes = response.bodyBytes
        val sb = StringBuilder()
        sb.append("$protocol ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }
        if (bodyBytes.isNotEmpty()) {
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("\r\n")
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) {
            outputStream.write(bodyBytes)
        }
        outputStream.flush()
    }

    /**
     * Sends a 503 Service Unavailable response to a client that connected while
     * another session is already active (enforcing FR-07: one sender at a time).
     *
     * @param socket The socket of the rejected client.
     */
    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        private val MACOS_MIRROR_CONTROL_METHODS = setOf(
            "RECORD", "TEARDOWN", "FLUSH", "GET_PARAMETER", "SET_PARAMETER", "OPTIONS"
        )

        /** Standard AirPlay RTSP port. */
        private const val RTSP_PORT = 7000

        /**
         * Maximum allowed RTSP message size (security: prevents DoS via huge messages).
         * 64 KB is well above any legitimate RTSP/SDP message size.
         */
        private const val MAX_MESSAGE_BYTES = 65536

        /** Fixed session ID — one session at a time. */
        private const val SESSION_ID = "PhairPlaySession"

        /**
         * UDP port for receiving audio RTP packets.
         * Must match [AirPlayReceiver.AUDIO_RTP_PORT] — both values must be kept in sync.
         * We keep a separate const here to avoid a circular compile-time dependency.
         */
        private const val AUDIO_RTP_PORT = 6001

        /** Fallback sender name when User-Agent header is absent or unparseable. */
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"
    }
}

// RtspRequest and RtspResponse are defined in RtspMessages.kt
