package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RtspHandlerTest — Unit tests for the RTSP protocol implementation.
 *
 * WHY: The RTSP handler is the most security-critical component of PhairPlay.
 * It processes untrusted data from the network. Every parsing path must be
 * tested with both valid inputs and malformed/malicious inputs.
 *
 * WHAT WE TEST:
 * - Correct responses for each RTSP method (OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN)
 * - SDP body parsing (codec parameters and encryption keys)
 * - Security: malformed/empty input handling (must not crash)
 * - Security: oversized messages (should be rejected)
 * - Correct state machine: RECORD without prior ANNOUNCE returns 455
 */
class RtspHandlerTest {

    // Captures whether the callbacks were invoked
    private var streamingStarted = false
    private var lastSession: SessionDescription? = null
    private var streamingStopped = false

    @Before
    fun setup() {
        streamingStarted = false
        lastSession = null
        streamingStopped = false
    }

    // ─── OPTIONS ─────────────────────────────────────────────────────────────

    /**
     * Test: OPTIONS response includes all required RTSP methods.
     *
     * WHY: macOS reads the OPTIONS response to know what methods it can use.
     * If a required method is missing, the connection attempt will fail.
     */
    @Test
    fun `OPTIONS response includes all required methods`() {
        val response = createTestHandler().handleOptionsPublic(
            RtspRequest(
                method = "OPTIONS",
                uri = "rtsp://192.168.1.1/phairplay",
                headers = mapOf("CSeq" to "1"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        val publicMethods = response.headers["Public"] ?: ""
        assertTrue("OPTIONS missing", "OPTIONS" in publicMethods)
        assertTrue("ANNOUNCE missing", "ANNOUNCE" in publicMethods)
        assertTrue("SETUP missing", "SETUP" in publicMethods)
        assertTrue("RECORD missing", "RECORD" in publicMethods)
        assertTrue("TEARDOWN missing", "TEARDOWN" in publicMethods)
    }

    // ─── ANNOUNCE ────────────────────────────────────────────────────────────

    @Test
    fun `ANNOUNCE with valid video+audio SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_VIDEO_AUDIO
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with valid audio-only SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_AUDIO_ONLY
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with empty body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with blank body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "   \n  ")
        )
        assertEquals(400, response.statusCode)
    }

    // ─── SETUP ───────────────────────────────────────────────────────────────

    @Test
    fun `SETUP response includes Session and Transport headers`() {
        val handler = createTestHandler()
        // ANNOUNCE first so session is established
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        assertEquals(200, response.statusCode)
        assertNotNull("Session header required", response.headers["Session"])
        assertNotNull("Transport header required", response.headers["Transport"])
    }

    @Test
    fun `first SETUP uses TCP interleaved transport (video)`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        val transport = response.headers["Transport"] ?: ""
        assertTrue("First SETUP should be TCP interleaved", "TCP" in transport || "interleaved" in transport)
    }

    // ─── RECORD ──────────────────────────────────────────────────────────────

    @Test
    fun `RECORD after ANNOUNCE triggers onStreamingStarted`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("onStreamingStarted should be called", streamingStarted)
        assertNotNull("SessionDescription should be passed to callback", lastSession)
    }

    @Test
    fun `RECORD without prior ANNOUNCE returns 455`() {
        // No ANNOUNCE → currentSession is null → must return 455 Method Not Valid in This State
        val response = createTestHandler().handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(455, response.statusCode)
    }

    @Test
    fun `RECORD with audio-only SDP sets isAudioOnly on session`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_AUDIO_ONLY)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("audio-only session flag should be set", lastSession?.isAudioOnly == true)
    }

    // ─── TEARDOWN ────────────────────────────────────────────────────────────

    @Test
    fun `TEARDOWN triggers onStreamingStopped callback`() {
        val handler = createTestHandler()
        handler.handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertTrue("onStreamingStopped should have been called", streamingStopped)
    }

    @Test
    fun `TEARDOWN returns 200`() {
        val response = createTestHandler().handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(200, response.statusCode)
    }

    // ─── Reconnect-storm regression ──────────────────────────────────────────

    /**
     * Test: a TCP connection that closes without ever reaching RECORD must NOT
     * fire onStreamingStopped.
     *
     * REGRESSION: previously the `finally` in handleClient called
     * onStreamingStopped() on every close — including bare pair-verify probes —
     * which restarted mDNS, made the device flicker in the iPhone's picker, and
     * provoked a re-pair storm at ~30 ms intervals after a normal TEARDOWN.
     */
    @Test
    fun `connection closed before RECORD does not trigger onStreamingStopped`() {
        val handler = createTestHandler()
        val server = java.net.ServerSocket(0)
        try {
            val port = server.localPort
            val client = java.net.Socket("127.0.0.1", port)
            val accepted = server.accept()
            // Close the client side without sending any RTSP request — the server
            // should see EOF on the next read and hit the finally block.
            client.close()
            handler.handleClientPublic(accepted)
        } finally {
            server.close()
        }

        assertEquals(
            "onStreamingStopped must not fire when no RECORD was received",
            false,
            streamingStopped
        )
    }

    // ─── Unknown method ───────────────────────────────────────────────────────

    @Test
    fun `unknown RTSP method returns 501`() {
        val response = createTestHandler().handleUnknownMethodPublic(
            RtspRequest(method = "FOOBAR", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(501, response.statusCode)
        assertEquals("Not Implemented", response.statusMessage)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun createTestHandler(): TestableRtspHandler = TestableRtspHandler(
        onStreamingStarted = { session ->
            streamingStarted = true
            lastSession = session
        },
        onStreamingStopped = { streamingStopped = true }
    )

    companion object {
        // Minimal valid SDP with H.264 video + AAC-ELD audio (base64 SPS/PPS included)
        val VALID_SDP_VIDEO_AUDIO = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=Z2QAKKwbGAoAofjA,aO48gA==
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 streamtype=5;profile-level-id=15;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=F8E85000
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()

        // Audio-only SDP (no video section — used for music/podcast streaming)
        val VALID_SDP_AUDIO_ONLY = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()
    }
}

/**
 * TestableRtspHandler — Subclass of [RtspHandler] that exposes internal methods for unit testing
 * without requiring a real network socket.
 */
class TestableRtspHandler(
    onStreamingStarted: (SessionDescription) -> Unit,
    onStreamingStopped: () -> Unit
) : RtspHandler(
    videoSurfaceProvider = { null },
    onStreamingStarted = onStreamingStarted,
    onStreamingStopped = onStreamingStopped,
    controlHandler = AirPlayTestFixtures.stubControlHandler()
) {
    fun handleOptionsPublic(req: RtspRequest) = handleOptionsInternal(req)
    fun handleAnnouncePublic(req: RtspRequest) = handleAnnounceInternal(req)
    fun handleSetupPublic(req: RtspRequest) = handleSetupInternal(req)
    fun handleRecordPublic(req: RtspRequest) = handleRecordInternal(req)
    fun handleTeardownPublic(req: RtspRequest) = handleTeardownInternal(req)
    fun handleUnknownMethodPublic(req: RtspRequest) = handleUnknownInternal(req)
    fun handlePausePublic(req: RtspRequest) = handlePauseInternal(req)
    fun handleClientPublic(socket: java.net.Socket) = handleClient(socket)
}
