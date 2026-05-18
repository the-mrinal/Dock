package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtpFuaReassemblyTest — Unit tests for H.264 FU-A NAL unit reassembly (S7-1).
 *
 * WHY: AirPlay screen mirroring uses H.264 FU-A (Fragmentation Unit type A) to split
 * large NAL units (e.g., IDR frames) across multiple RTP packets. Before S7-1,
 * [RtpInterleaved] passed the raw RTP payload directly as a NAL unit; for FU-A
 * packets this produced a malformed NAL unit that MediaCodec rejected, causing
 * video glitches or decoder stalls on large I-frames.
 *
 * HOW: We feed synthetic FU-A byte streams via [ByteArrayInputStream] into
 * [RtpInterleaved.readLoop] and verify that the reassembled NAL unit has the
 * correct reconstructed header and payload bytes.
 *
 * FU-A packet layout (RFC 6184 §5.8):
 *   Byte 0: FU indicator  — F(1) NRI(2) type=28(5)
 *   Byte 1: FU header     — S(1) E(1) R(1) orig-NAL-type(5)
 *   Bytes 2+: fragment data
 *
 * Reconstructed NAL unit header: (NRI from FU indicator) | (orig-NAL-type from FU header)
 */
class RtpFuaReassemblyTest {

    // ─── Two-fragment FU-A ────────────────────────────────────────────────────

    @Test
    fun `two-fragment FU-A reassembles into single NAL unit with correct header`() {
        val nalType = 0x05   // IDR slice
        val nri     = 0x60   // high importance
        // Fragment 1: S=1, E=0, data=[01 02 03 04]
        val frag1 = fuaPayload(nri, isStart = true,  isEnd = false, nalType, 0x01, 0x02, 0x03, 0x04)
        // Fragment 2: S=0, E=1, data=[05 06 07 08]
        val frag2 = fuaPayload(nri, isStart = false, isEnd = true,  nalType, 0x05, 0x06, 0x07, 0x08)

        val data = interleavedStream(frag1, ts = 90000L) + interleavedStream(frag2, ts = 90000L)

        var received: ByteArray? = null
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { nal, _ -> received = nal }, {})

        assertNotNull("FU-A reassembly must produce a NAL unit", received)
        // Reconstructed NAL header = (nri | nalType) = (0x60 | 0x05) = 0x65
        assertEquals("NAL header should be reconstructed from NRI + orig type",
                     0x65.toByte(), received!![0])
        assertArrayEquals(
            byteArrayOf(0x65.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            received
        )
    }

    @Test
    fun `three-fragment FU-A reassembles all data in order`() {
        val nalType = 0x01  // non-IDR
        val nri     = 0x40
        val frag1 = fuaPayload(nri, isStart = true,  isEnd = false, nalType, 0xAA.toByte())
        val frag2 = fuaPayload(nri, isStart = false, isEnd = false, nalType, 0xBB.toByte())
        val frag3 = fuaPayload(nri, isStart = false, isEnd = true,  nalType, 0xCC.toByte())

        val data = interleavedStream(frag1, 0L) +
                   interleavedStream(frag2, 0L) +
                   interleavedStream(frag3, 0L)

        var received: ByteArray? = null
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { nal, _ -> received = nal }, {})

        assertNotNull(received)
        // Header(1) + 0xAA + 0xBB + 0xCC = 4 bytes
        assertEquals(4, received!!.size)
        assertEquals(0xAA.toByte(), received!![1])
        assertEquals(0xBB.toByte(), received!![2])
        assertEquals(0xCC.toByte(), received!![3])
    }

    @Test
    fun `single-packet FU-A (S=1 and E=1) is delivered as one NAL unit`() {
        val nalType = 0x02
        val nri     = 0x40
        val frag = fuaPayload(nri, isStart = true, isEnd = true, nalType, 0x11, 0x22)

        val data = interleavedStream(frag, 0L)
        var received: ByteArray? = null
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { nal, _ -> received = nal }, {})

        assertNotNull(received)
        // Header + 0x11 + 0x22 = 3 bytes
        assertEquals(3, received!!.size)
    }

    // ─── Error/robustness paths ───────────────────────────────────────────────

    @Test
    fun `FU-A end fragment without prior start is discarded without crash`() {
        val nalType = 0x05
        val nri     = 0x60
        // E=1, S=0: end fragment arrived without a start — should be silently dropped
        val frag = fuaPayload(nri, isStart = false, isEnd = true, nalType, 0xDE.toByte())

        val data = interleavedStream(frag, 0L)
        var nalReceived = false
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { _, _ -> nalReceived = true }, {})

        assertTrue("Orphaned FU-A end packet must not deliver a NAL unit", !nalReceived)
    }

    @Test
    fun `FU-A start without end (EOF) is discarded gracefully`() {
        val frag = fuaPayload(0x60, isStart = true, isEnd = false, 0x05, 0x01, 0x02)
        val data = interleavedStream(frag, 0L)

        var nalReceived = false
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { _, _ -> nalReceived = true }, {})

        assertTrue("Incomplete FU-A must not trigger callback", !nalReceived)
    }

    @Test
    fun `new FU-A start discards prior incomplete assembly`() {
        val nalType = 0x05
        val nri     = 0x60
        // First sequence: start only (no end)
        val frag1 = fuaPayload(nri, isStart = true,  isEnd = false, nalType, 0x01)
        // Second sequence: immediate start+end (new NAL unit)
        val frag2 = fuaPayload(nri, isStart = true,  isEnd = true,  nalType, 0x02)

        val data = interleavedStream(frag1, 0L) + interleavedStream(frag2, 0L)

        val received = mutableListOf<ByteArray>()
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { nal, _ -> received.add(nal) }, {})

        // Only the second (complete) FU-A should be delivered
        assertEquals("Only the second (complete) FU-A should be delivered", 1, received.size)
    }

    // ─── FU-A mixed with single-NAL packets ──────────────────────────────────

    @Test
    fun `single NAL packet after complete FU-A is delivered separately`() {
        val frag1 = fuaPayload(0x60, isStart = true,  isEnd = false, 0x05, 0x01)
        val frag2 = fuaPayload(0x60, isStart = false, isEnd = true,  0x05, 0x02)
        val single = byteArrayOf(0x65.toByte(), 0xAA.toByte())  // single IDR NAL

        val data = interleavedStream(frag1, 9000L) +
                   interleavedStream(frag2, 9000L) +
                   interleavedStream(single, 18000L)

        val received = mutableListOf<ByteArray>()
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { nal, _ -> received.add(nal) }, {})

        assertEquals("Expected 2 NAL units: 1 FU-A + 1 single-NAL", 2, received.size)
        assertEquals(3, received[0].size)   // header + 0x01 + 0x02
        assertEquals(2, received[1].size)   // single NAL as-is
    }

    @Test
    fun `FU-A presentation timestamp is taken from the end fragment`() {
        val frag1 = fuaPayload(0x60, isStart = true,  isEnd = false, 0x05, 0x01)
        val frag2 = fuaPayload(0x60, isStart = false, isEnd = true,  0x05, 0x02)

        val data = interleavedStream(frag1, ts = 45000L) +
                   interleavedStream(frag2, ts = 45000L)

        var ptsReceived = -1L
        RtpInterleaved.readLoop(ByteArrayInputStream(data), { _, pts -> ptsReceived = pts }, {})

        // 45_000 RTP ticks / 90_000 Hz * 1_000_000 µs = 500_000 µs
        assertEquals(500_000L, ptsReceived)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a FU-A RTP payload: FU indicator + FU header + [data].
     * @param nri       NRI bits (e.g. 0x60 = high importance, 0x40 = medium)
     * @param isStart   Set the S bit in the FU header
     * @param isEnd     Set the E bit in the FU header
     * @param nalType   Original NAL unit type (0-31)
     * @param data      Fragment data bytes
     */
    private fun fuaPayload(
        nri: Int, isStart: Boolean, isEnd: Boolean, nalType: Int, vararg data: Byte
    ): ByteArray {
        val fuIndicator = (nri or 28).toByte()
        val seBits      = (if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0)
        val fuHeader    = (seBits or (nalType and 0x1F)).toByte()
        return byteArrayOf(fuIndicator, fuHeader) + data
    }

    /** Wraps [payload] in a minimal RTP packet then in an interleaved `$` frame (channel 0). */
    private fun interleavedStream(payload: ByteArray, ts: Long): ByteArray {
        val rtp = ByteArray(12 + payload.size)
        rtp[0] = 0x80.toByte()
        rtp[1] = 0x60.toByte()  // PT=96
        rtp[2] = 0x00; rtp[3] = 0x01
        rtp[4] = ((ts shr 24) and 0xFF).toByte()
        rtp[5] = ((ts shr 16) and 0xFF).toByte()
        rtp[6] = ((ts shr  8) and 0xFF).toByte()
        rtp[7] = ( ts         and 0xFF).toByte()
        payload.copyInto(rtp, destinationOffset = 12)
        val len = rtp.size
        return byteArrayOf(0x24, 0x00, (len shr 8).toByte(), (len and 0xFF).toByte()) + rtp
    }
}
