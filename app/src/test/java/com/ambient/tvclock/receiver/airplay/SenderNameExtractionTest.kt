package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SenderNameExtractionTest — Tests that sender identity flows end-to-end from
 * RTSP ANNOUNCE headers into [SessionDescription.senderName].
 *
 * The actual label-derivation rules live in [SenderIdentity] and are unit-
 * tested there; this suite covers the *plumbing*: that
 * [RtspHandler.handleAnnounceInternal] feeds the headers through
 * SenderIdentity.label and stashes the result on the session, and that the
 * session survives the ANNOUNCE → RECORD hand-off.
 */
class SenderNameExtractionTest {

    // ─── SessionDescription defaults ─────────────────────────────────────────

    @Test
    fun `SessionDescription senderName defaults to empty string`() {
        val session = SessionDescription(hasVideo = false, hasAudio = true)
        assertEquals("", session.senderName)
    }

    @Test
    fun `SessionDescription copy preserves senderName`() {
        val original = SessionDescription(hasVideo = false, hasAudio = true, senderName = "iPhone")
        val copy = original.copy(audioCodec = AudioCodec.ALAC)
        assertEquals("iPhone", copy.senderName)
    }

    // ─── senderName flows via ANNOUNCE → RECORD ──────────────────────────────

    @Test
    fun `iPhone in User-Agent surfaces as iPhone`() {
        val session = announceAndRecord(userAgent = "iPhone OS/17.0 (AirPlay/720)")
        assertEquals("iPhone", session?.senderName)
    }

    @Test
    fun `Mac in User-Agent surfaces as Mac`() {
        val session = announceAndRecord(userAgent = "Macintosh OS/14.0 (AirPlay/950)")
        assertEquals("Mac", session?.senderName)
    }

    @Test
    fun `friendly-name header wins over User-Agent heuristic`() {
        val session = announceAndRecord(
            userAgent = "AirPlay/950.7.1",
            extraHeaders = mapOf("X-Apple-Client-Name" to "Mrinal's MacBook Pro")
        )
        assertEquals("Mrinal's MacBook Pro", session?.senderName)
    }

    @Test
    fun `generic User-Agent falls back to Apple device default`() {
        val session = announceAndRecord(userAgent = "AirPlay/950.7.1")
        assertEquals("Apple device", session?.senderName)
    }

    @Test
    fun `missing User-Agent header results in non-empty fallback sender name`() {
        val session = announceAndRecord(userAgent = null)
        assertNotNull(session)
        assertTrue("Fallback must be non-empty", session!!.senderName.isNotEmpty())
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    /**
     * Drives a minimal ANNOUNCE → RECORD cycle and returns the [SessionDescription]
     * delivered to [onStreamingStarted], or null if RECORD did not fire the callback.
     */
    private fun announceAndRecord(
        userAgent: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): SessionDescription? {
        var captured: SessionDescription? = null
        val handler = TestableRtspHandler(
            onStreamingStarted = { session -> captured = session },
            onStreamingStopped = {}
        )

        val announceHeaders = buildMap {
            put("CSeq", "3")
            if (userAgent != null) put("User-Agent", userAgent)
            putAll(extraHeaders)
        }

        handler.handleAnnouncePublic(
            RtspRequest(
                method  = "ANNOUNCE",
                uri     = "rtsp://192.168.1.1/phairplay",
                headers = announceHeaders,
                body    = MINIMAL_AUDIO_SDP
            )
        )
        handler.handleRecordPublic(
            RtspRequest(
                method  = "RECORD",
                uri     = "rtsp://192.168.1.1/phairplay",
                headers = mapOf("CSeq" to "4"),
                body    = ""
            )
        )
        return captured
    }

    companion object {
        /** Audio-only SDP that [SdpParser] accepts — no H.264 SPS required. */
        private val MINIMAL_AUDIO_SDP = """
            v=0
            o=AirTunes AA:BB:CC:DD:EE:FF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
        """.trimIndent()
    }
}
