package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtpInterleavedTest — Unit tests for [RtpInterleaved].
 *
 * WHY: [RtpInterleaved] reads binary data from the network. It is critical that:
 * - Valid `$`-framed RTP frames are parsed correctly
 * - Non-`$` bytes are skipped without crashing (keep-alive RTSP text)
 * - Oversized frames are rejected (DoS prevention)
 * - EOF is handled cleanly
 * - RTP header is parsed correctly and presentation timestamp is computed
 *
 * HOW: We use [ByteArrayInputStream] to simulate the TCP InputStream,
 * avoiding real network sockets.
 */
class RtpInterleavedTest {

    // ─── Frame parsing ────────────────────────────────────────────────────────

    @Test
    fun `valid video RTP frame triggers onVideoNalUnit callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var nalUnitReceived: ByteArray? = null
        var ptsReceived: Long = -1

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { nal, pts ->
                nalUnitReceived = nal
                ptsReceived = pts
            },
            onStreamEnded = {}
        )

        assertNotNull("NAL unit should be received", nalUnitReceived)
        // 90000 RTP ticks @ 90kHz = 1 second = 1_000_000 µs
        assertEquals(1_000_000L, ptsReceived)
    }

    @Test
    fun `non-video channel (channel 1 = RTCP) does not trigger callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 1, payload = rtp)  // RTCP

        var callbackInvoked = false
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> callbackInvoked = true },
            onStreamEnded = {}
        )

        assertTrue("RTCP channel should not trigger video callback", !callbackInvoked)
    }

    @Test
    fun `empty stream calls onStreamEnded without crash`() {
        val stream = ByteArrayInputStream(byteArrayOf())
        var streamEnded = false

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> },
            onStreamEnded = { streamEnded = true }
        )

        assertTrue("onStreamEnded should be called on empty stream", streamEnded)
    }

    @Test
    fun `non-dollar bytes are skipped`() {
        // Prepend some non-'$' garbage bytes before a valid frame
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val frame = buildInterleavedFrame(channel = 0, payload = rtp)
        val garbage = byteArrayOf(0x41, 0x42, 0x43)  // "ABC" — not '$'
        val data = garbage + frame

        var nalReceived = false
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(data),
            onVideoNalUnit = { _, _ -> nalReceived = true },
            onStreamEnded = {}
        )

        assertTrue("NAL unit should be received after garbage bytes", nalReceived)
    }

    @Test
    fun `RTP timestamp zero produces zero presentation time`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var ptsReceived = -1L
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, pts -> ptsReceived = pts },
            onStreamEnded = {}
        )

        assertEquals(0L, ptsReceived)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid RTP frame (12-byte header + 4-byte dummy NAL payload).
     *
     * @param timestampRtp90k RTP timestamp in 90 kHz clock ticks.
     */
    private fun buildMinimalVideoRtpFrame(timestampRtp90k: Long): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()  // V=2, P=0, X=0, CC=0
        header[1] = 0x60.toByte()  // M=0, PT=96
        header[2] = 0x00           // Seq high
        header[3] = 0x01           // Seq low = 1
        // Timestamp (big-endian 32-bit)
        header[4] = ((timestampRtp90k shr 24) and 0xFF).toByte()
        header[5] = ((timestampRtp90k shr 16) and 0xFF).toByte()
        header[6] = ((timestampRtp90k shr  8) and 0xFF).toByte()
        header[7] = ( timestampRtp90k         and 0xFF).toByte()
        // SSRC = 0 (bytes 8–11 already zero)
        val payload = byteArrayOf(0x65, 0x00, 0x00, 0x00)  // fake IDR NAL unit
        return header + payload
    }

    /** Wraps [payload] in an interleaved `$` frame with the given [channel]. */
    private fun buildInterleavedFrame(channel: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(
            0x24,                    // '$'
            channel.toByte(),
            (len shr 8).toByte(),    // length high byte
            (len and 0xFF).toByte()  // length low byte
        ) + payload
    }

    /** Creates a [ByteArrayInputStream] containing a single interleaved frame. */
    private fun buildInterleavedStream(channel: Int, payload: ByteArray): ByteArrayInputStream =
        ByteArrayInputStream(buildInterleavedFrame(channel, payload))
}
