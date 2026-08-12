package com.ambient.tvclock.receiver.airplay

import android.content.Context
import android.view.Surface
import com.ambient.tvclock.receiver.ProtocolState
import com.ambient.tvclock.util.Logger
import com.ambient.tvclock.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [ReceiverService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [ReceiverService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([ReceiverService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Called when the active [VideoDecoder] learns the source video dimensions
     * (and again if they change mid-session, e.g. a phone rotates). The
     * receiver forwards this verbatim so the UI layer can letterbox correctly.
     * `null` is emitted from [stop] / `onStreamingStopped` to reset overlay state.
     */
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> }
) {

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    // Tracks the surface VideoDecoder was last configured/rebound with. Used to
    // suppress redundant setOutputSurface() calls when the StateFlow replays.
    @Volatile private var configuredSurface: Surface? = null
    private var lastMirrorSpsPps: ByteArray? = null
    private var audioPlayer: AudioPlayer? = null
    private var mirrorReceiver: MirrorTcpReceiver? = null
    private var surfaceObserverJob: kotlinx.coroutines.Job? = null
    private val airPlayPairing = AirPlayPairing(context)
    // Event channel: iOS-side AirPlay 2 senders open a long-lived TCP socket
    // to whichever port we advertise as `eventPort` in the master SETUP
    // response. Spec says video URL handoffs (e.g. YouTube → "play this video
    // ID") arrive over this channel. We advertise 0 by default; the receiver
    // gets stuck after RECORD waiting for the channel that never opens.
    private val eventChannel = AirPlayEventChannel()
    // AirPlay video URL playback (POST /play). Allocated up-front so the
    // RTSP handler thread can hand requests straight to it without a
    // round-trip through a coroutine; ExoPlayer itself is not created until
    // the first /play arrives, so the idle cost is one Handler.
    private val videoPlayer = AirPlayVideoPlayer(
        context = context,
        surfaceProvider = videoSurfaceProvider,
        onConnected = { onVideoUrlConnected() },
        onDisconnected = { onVideoUrlDisconnected() },
        onVideoSize = onVideoSize
    )
    @Volatile private var videoUrlSenderName: String = "AirPlay"

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null
    // UDP socket on the advertised audio control port (RTCP feedback). iOS
    // YouTube tears down sessions at ~3 s when this port is not bound
    // (issue #17): RTCP heartbeats hit a closed UDP port and Linux returns
    // ICMP port-unreachable, which iOS interprets as "receiver dead." We
    // bind a drain-only listener so the OS no longer rejects packets and
    // we get visibility into what iOS is sending. Apple Music tolerates the
    // closed port, but YouTube doesn't — same code path either way.
    @Volatile private var audioControlSocket: DatagramSocket? = null

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        startSurfaceObserver()
        scope.launch {
            try {
                startTimingHandler()
                val eventPort = eventChannel.start(scope)
                AirPlaySetupParser.eventPort = eventPort
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Collects surface-lifecycle events from [com.ambient.tvclock.receiver.ReceiverStateBus]
     * and rebinds the active [VideoDecoder] each time the SurfaceView's underlying
     * Surface is destroyed and recreated (which happens whenever the
     * `AspectRatioSurfaceView` re-measures to the source video aspect ratio).
     *
     * The first emission is the StateFlow's current value at subscribe time —
     * for a fresh receiver session this is `null` (no overlay yet) or the
     * pre-resize Surface that [VideoDecoder.initialize] will pick up via the
     * existing surface provider. We skip surfaces identical to the one the
     * decoder was last configured with, so the initial replay doesn't trigger
     * a redundant rebind.
     */
    private fun startSurfaceObserver() {
        surfaceObserverJob?.cancel()
        surfaceObserverJob = scope.launch {
            com.ambient.tvclock.receiver.ReceiverStateBus.videoSurface.collect { surface ->
                if (surface == null || surface === configuredSurface) return@collect
                // Whichever of the two pipelines is currently live needs its
                // output surface rebound; both calls are no-ops when the
                // respective pipeline is idle, so we just send to both.
                videoDecoder?.let { decoder ->
                    Logger.i("AirPlayReceiver: surface changed mid-stream — rebinding mirror decoder")
                    decoder.setOutputSurface(surface)
                }
                if (videoPlayer.isActive()) {
                    Logger.i("AirPlayReceiver: surface changed mid-stream — rebinding ExoPlayer")
                    videoPlayer.setOutputSurface(surface)
                }
                configuredSurface = surface
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [ReceiverService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            eventChannel.stop()
            releaseMediaComponents()
            videoPlayer.stop()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        val playbackHandler = AirPlayPlaybackHandler(
            videoPlayer = videoPlayer,
            onPlayRequested = { senderName, peer, headers ->
                videoUrlSenderName = senderName
                // iOS expects the mirror pipeline to be free before the
                // video URL bind takes the Surface — release synchronously
                // on the request thread so the main-thread ExoPlayer.play
                // dispatch never races a live MediaCodec on the same output.
                releaseMediaComponents()
                if (peer != null) {
                    SenderProbe(context, peer, headers, "POST", "/play").start()
                }
            }
        )
        val controlHandler = AirPlayControlHandler(
            context = context,
            pairing = airPlayPairing,
            deviceName = { displayName.ifBlank { NetworkUtils.getDeviceName(context) } },
            onSetupComplete = { setup -> onMirrorSetup(setup) },
            onTimingPeer = { addr, port, socket -> timingHandler?.beginPeerSync(addr, port, socket) },
            playbackHandler = playbackHandler,
            // Issue #17: feed the pair-verify ECDH secret into the event
            // channel so it can derive ChaCha20-Poly1305 keys before iOS
            // opens the encrypted control socket. Without this we can only
            // see encrypted bytes and never the plist payloads that YouTube
            // may be using to coordinate mirror codec readiness.
            onEcdhSecretReady = { secret -> eventChannel.setSecret(secret) }
        )
        rtspHandler = RtspHandler(
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            controlHandler = controlHandler,
            onMirrorRecord = { senderLabel, peer, headers ->
                onMirrorStreamingStarted(senderLabel)
                if (peer != null) {
                    SenderProbe(context, peer, headers, "RECORD", "<mirror>").start()
                }
            }
        ).also { it.start(scope) }
        Logger.d("RTSP handler started on port 7000")
    }

    private fun onMirrorSetup(setup: AirPlayControlHandler.SetupResult) {
        val aesKey = setup.aesKey ?: run {
            Logger.w("Mirror SETUP without AES key")
            return
        }
        val aesIv = setup.aesIv ?: run {
            Logger.w("Mirror SETUP without AES IV")
            return
        }
        // Audio-only sessions (YouTube on iOS, Spotify, etc.) advertise only a
        // type 96 audio stream — no type 110 mirror, so no streamConnectionID.
        // Start the audio receiver whenever the SETUP negotiated an audio port,
        // independent of whether mirror video was also requested. Pre-fix this
        // method returned early on missing streamConnectionID, leaving port
        // 6000 unbound: iOS sent audio packets into the void and gave up
        // after a few seconds.
        val hasAudio = setup.audioDataPort != null
        val streamId = setup.streamConnectionId

        if (streamId != null) {
            mirrorReceiver?.stop()
            mirrorReceiver = MirrorTcpReceiver(
                onSpsPps = { data ->
                    val surface = videoSurfaceProvider() ?: return@MirrorTcpReceiver
                    initVideoDecoderFromSpsPps(data, surface)
                },
                onNalUnit = { nal, ptsUs ->
                    videoDecoder?.decodeNalUnit(stripStartCode(nal), ptsUs)
                }
            ).also {
                it.start(scope, aesKey, streamId)
            }
            Logger.i("Mirror TCP receiver started (streamId=$streamId)")
        }

        if (hasAudio) {
            // Codec comes from SETUP plist `ct`. Older senders / audio-only
            // mirror sessions without an explicit ct get AAC_ELD by default
            // (matches the old hardcoded path so we don't regress mirror audio).
            val codec = setup.audioCodec ?: AudioCodec.AAC_ELD
            startMirrorAudioReceiver(aesKey, aesIv, codec)
            Logger.i("Mirror audio receiver started (audio-only=${streamId == null}, codec=$codec)")
        }

        if (streamId == null && !hasAudio) {
            Logger.d("Mirror SETUP master phase — keys cached, awaiting stream SETUP")
        }
    }

    /** Mirror audio RTP (type 96) — same AES key/IV as SETUP ekey; UxPlay data port 6000. */
    private fun startMirrorAudioReceiver(
        aesKey: ByteArray,
        aesIv: ByteArray,
        audioCodec: AudioCodec
    ) {
        try {
            audioSocket?.close()
        } catch (_: Exception) {
        }
        try {
            audioControlSocket?.close()
        } catch (_: Exception) {
        }
        audioPlayer?.release()
        audioPlayer = AudioPlayer().also {
            it.initialize(aesKey, aesIv, MIRROR_AUDIO_SAMPLE_RATE, MIRROR_AUDIO_CHANNELS, audioCodec)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(MirrorAudioPorts.DATA_PORT)
                audioSocket = socket
                Logger.i("Mirror audio UDP listening on port ${MirrorAudioPorts.DATA_PORT}")
                val buf = ByteArray(MAX_AUDIO_PACKET_BYTES)
                while (isActive && audioSocket === socket) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                if (isActive) Logger.e("Mirror audio UDP receiver error", e)
            }
        }
        startMirrorAudioControlReceiver()
    }

    /**
     * Drain-only listener on the advertised audio control port
     * ([MirrorAudioPorts.CONTROL_PORT]). The first ~10 packets are hex-dumped
     * so we can identify the protocol iOS uses there (almost certainly
     * RTCP receiver / sender reports); subsequent packets are silently
     * drained to keep the OS from sending ICMP port-unreachable replies,
     * which iOS appears to read as "receiver dead" for the YouTube path.
     *
     * No reply is generated for now — confirming the bind is enough to
     * fix YouTube would tell us the next step is responding with proper
     * RTCP RRs / receiver feedback.
     */
    private fun startMirrorAudioControlReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(MirrorAudioPorts.CONTROL_PORT)
                audioControlSocket = socket
                Logger.i("Mirror audio CONTROL UDP listening on port ${MirrorAudioPorts.CONTROL_PORT}")
                val buf = ByteArray(MAX_AUDIO_PACKET_BYTES)
                var logged = 0
                while (isActive && audioControlSocket === socket) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (logged < 10) {
                        val previewLen = minOf(packet.length, 32)
                        val hex = packet.data.copyOf(previewLen).joinToString(" ") { "%02x".format(it) }
                        Logger.i(
                            "Mirror audio CONTROL rx ${packet.length}B from " +
                                "${packet.address?.hostAddress}:${packet.port} first${previewLen}B=$hex"
                        )
                        logged++
                    }
                }
            } catch (e: Exception) {
                if (isActive) Logger.e("Mirror audio CONTROL UDP receiver error", e)
            }
        }
    }

    private fun onMirrorStreamingStarted(senderLabel: String) {
        scope.launch {
            onSenderNameChanged(senderLabel)
            emitState(ProtocolState.CONNECTED)
        }
    }

    /**
     * Fired by [AirPlayVideoPlayer] from the main thread when an ExoPlayer
     * playback session has been kicked off in response to `POST /play`.
     *
     * We pipe the sender name (captured on the IO thread when /play arrived
     * via [AirPlayPlaybackHandler.onPlayRequested]) into the existing
     * [onSenderNameChanged] callback so [ReceiverService] decorates the
     * active-connection notification consistently with mirror/audio sessions.
     */
    private fun onVideoUrlConnected() {
        scope.launch {
            onSenderNameChanged(videoUrlSenderName)
            emitState(ProtocolState.CONNECTED)
        }
    }

    /**
     * Fired when the video URL player releases (POST /stop, end of stream,
     * or AirPlayReceiver.stop). Restarts mDNS so the receiver reappears in
     * sender pickers without having to wait for the next periodic refresh,
     * matching the mirror/audio teardown path.
     */
    private fun onVideoUrlDisconnected() {
        Logger.i("Video URL playback ended — re-advertising")
        emitState(ProtocolState.ADVERTISING)
        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after video URL playback", e)
            }
        }
    }

    private fun initVideoDecoderFromSpsPps(data: ByteArray, surface: Surface) {
        // Skip if this SPS/PPS payload is byte-identical to the one we last configured.
        // iOS/iPadOS sends a 0x01 codec-data packet before every IDR keyframe even when
        // the resolution didn't change; recreating the decoder each time would tear down
        // and re-allocate the hardware codec several times per second.
        // When the bytes DO change (e.g. YouTube going fullscreen → portrait/landscape
        // resolution swap), we MUST release the old decoder and reconfigure — otherwise
        // MediaCodec's dequeueInputBuffer throws IllegalStateException on the first NAL
        // that doesn't match the configured format, the screen goes blank, and the sender
        // gives up.
        val previous = lastMirrorSpsPps
        if (previous != null && previous.contentEquals(data)) return

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                var next = i + 4
                while (next < data.size - 4) {
                    if (data[next] == 0.toByte() && data[next + 1] == 0.toByte() &&
                        data[next + 2] == 0.toByte() && data[next + 3] == 1.toByte()
                    ) break
                    next++
                }
                if (next >= data.size - 4) next = data.size
                val nal = data.copyOfRange(i + 4, next)
                val type = nal[0].toInt() and 0x1F
                when (type) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
                i = next
            } else i++
        }
        if (sps != null && pps != null) {
            if (videoDecoder != null) {
                Logger.i("Mirror SPS/PPS changed (${previous?.size ?: 0}B → ${data.size}B) — recreating decoder")
                videoDecoder?.release()
                videoDecoder = null
            }
            videoDecoder = VideoDecoder(surface, onVideoSize).also {
                it.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            }
            configuredSurface = surface
            lastMirrorSpsPps = data.copyOf()
            Logger.i("VideoDecoder initialized from mirror SPS/PPS")
        }
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        var offset = 0
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte()) {
            offset = if (nal[3] == 1.toByte()) 4 else if (nal[2] == 1.toByte()) 3 else 0
        }
        return if (offset > 0) nal.copyOfRange(offset, nal.size) else nal
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Notify ReceiverService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        Logger.i("Streaming stopped — releasing media components")
        timingHandler?.clearPeer()
        releaseMediaComponents()
        // iOS often closes the AirPlay video URL TCP connection without sending
        // POST /stop, so the disconnect path is our only chance to release the
        // ExoPlayer. Without this the last frame stays pinned to the Surface
        // and the user sees "stuck on the last AirPlayed video".
        if (videoPlayer.isActive()) {
            videoPlayer.stop()
        }
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface, onVideoSize).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        configuredSurface = surface
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                // SDP-path codec — `AppleLossless` → ALAC, `mpeg4-generic` → AAC_ELD.
                // UNKNOWN falls through to AAC-ELD as the historical default.
                audioCodec = if (session.audioCodec == AudioCodec.UNKNOWN) AudioCodec.AAC_ELD
                             else session.audioCodec
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (audioSocket != null) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    /** Clears the video NAL callback, closes the audio socket, and releases media components. */
    private fun releaseMediaComponents() {
        rtspHandler?.onVideoNalUnit = null
        mirrorReceiver?.stop()
        mirrorReceiver = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        try { audioControlSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioControlSocket = null
        videoDecoder?.release()
        videoDecoder = null
        configuredSurface = null
        lastMirrorSpsPps = null
        audioPlayer?.release()
        audioPlayer = null
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        private const val MIRROR_AUDIO_SAMPLE_RATE = 44100
        private const val MIRROR_AUDIO_CHANNELS = 2

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
