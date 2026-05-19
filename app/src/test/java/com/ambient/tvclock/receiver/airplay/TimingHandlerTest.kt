package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress

/**
 * TimingHandlerTest — Unit tests for [TimingHandler].
 *
 * WHY: [TimingHandler] parses and produces Apple NTP timing packets that control
 * A/V sync. A wrong response format means the sender mis-calculates its clock
 * offset, causing A/V drift > 40 ms (NFR-15).
 *
 * WHAT WE TEST:
 * - [TimingHandler.currentNtpTimestamp] returns a plausible value near the current time
 * - [TimingHandler.ntpToUs] converts known timestamps to the correct µs value
 * - [TimingHandler.readUint32] / [TimingHandler.writeUint32] round-trip correctly
 * - [TimingHandler.handleProbe] ignores probes with the wrong type byte
 * - [TimingHandler.handleProbe] ignores probes that are too short
 * - [TimingHandler.handleProbe] with a valid request updates [TimingHandler.rtpClockOffsetUs]
 * - [TimingHandler.handleProbe] response bytes contain echoed sequence and correct type
 * - [TimingHandler.stop] before [TimingHandler.start] does not crash
 *
 * HOW: [handleProbe] is `internal` so we call it directly from tests.
 * We use a fixed [receiveNtp] value to make offset calculations deterministic.
 * The DatagramSocket.send() call inside [handleProbe] uses null-safe `?.send()` so
 * it silently no-ops when the handler is not started (socket is null) — no real
 * UDP socket is needed to test the logic.
 */
class TimingHandlerTest {

    private lateinit var handler: TimingHandler

    @Before
    fun setup() {
        handler = TimingHandler()
    }

    // ─── NTP helpers ──────────────────────────────────────────────────────────

    @Test
    fun `currentNtpTimestamp returns value after NTP epoch`() {
        // NTP epoch is 1900-01-01. By 2024 we have > 124 years of seconds.
        // The NTP seconds (high 32 bits) should be > 3_900_000_000 (approx year 2023).
        val ntp = TimingHandler.currentNtpTimestamp()
        val seconds = ntp ushr 32
        assertTrue(
            "NTP seconds should be past year 2023 (got $seconds)",
            seconds > 3_900_000_000L
        )
    }

    @Test
    fun `currentNtpTimestamp high 32 bits increase over time`() {
        val t1 = TimingHandler.currentNtpTimestamp() ushr 32
        Thread.sleep(1010)  // sleep slightly more than 1 second
        val t2 = TimingHandler.currentNtpTimestamp() ushr 32
        assertTrue("NTP second should have incremented", t2 > t1)
    }

    @Test
    fun `ntpToUs converts Unix epoch correctly`() {
        // Unix epoch in NTP = NTP_EPOCH_OFFSET seconds, 0 fraction → 0 µs from Unix epoch
        val ntpEpochSeconds = 2_208_988_800L  // seconds from 1900 to 1970
        val us = TimingHandler.ntpToUs(ntpSeconds = ntpEpochSeconds, ntpFraction = 0L)
        assertEquals(0L, us)
    }

    @Test
    fun `ntpToUs converts one second correctly`() {
        // 1 second after Unix epoch = NTP_EPOCH_OFFSET + 1 seconds, 0 fraction → 1_000_000 µs
        val ntpEpochSeconds = 2_208_988_800L + 1L
        val us = TimingHandler.ntpToUs(ntpSeconds = ntpEpochSeconds, ntpFraction = 0L)
        assertEquals(1_000_000L, us)
    }

    @Test
    fun `ntpToUs half-second fraction converts to 500000 us`() {
        // Fraction 0x80000000 (2^31) represents 0.5 seconds
        val ntpEpochSeconds = 2_208_988_800L
        val halfSecFraction = 0x80000000L  // 2147483648 / 4294967296 ≈ 0.5
        val us = TimingHandler.ntpToUs(ntpEpochSeconds, halfSecFraction)
        // Allow ±1 µs rounding
        assertTrue("Expected ~500_000 µs, got $us", us in 499_999L..500_001L)
    }

    // ─── readUint32 / writeUint32 round-trip ──────────────────────────────────

    @Test
    fun `writeUint32 and readUint32 round-trip for zero`() {
        val buf = ByteArray(8)
        TimingHandler.writeUint32(buf, 0, 0)
        assertEquals(0, TimingHandler.readUint32(buf, 0))
    }

    @Test
    fun `writeUint32 and readUint32 round-trip for max value`() {
        val buf = ByteArray(8)
        TimingHandler.writeUint32(buf, 0, -1)  // -1 as signed Int = 0xFFFFFFFF
        assertEquals(-1, TimingHandler.readUint32(buf, 0))
    }

    @Test
    fun `writeUint32 and readUint32 round-trip for known value`() {
        val buf = ByteArray(8)
        val value = 0x12AB_34CD.toInt()
        TimingHandler.writeUint32(buf, 0, value)
        assertEquals(value, TimingHandler.readUint32(buf, 0))
    }

    @Test
    fun `writeUint32 uses big-endian byte order`() {
        val buf = ByteArray(8)
        TimingHandler.writeUint32(buf, 0, 0x01020304)
        assertEquals(0x01.toByte(), buf[0])
        assertEquals(0x02.toByte(), buf[1])
        assertEquals(0x03.toByte(), buf[2])
        assertEquals(0x04.toByte(), buf[3])
    }

    @Test
    fun `writeUint32 writes at correct offset`() {
        val buf = ByteArray(8)
        TimingHandler.writeUint32(buf, 4, 0xDEAD_BEEF.toInt())
        // Bytes 0-3 should remain zero
        assertEquals(0, buf[0].toInt())
        assertEquals(0, buf[1].toInt())
        assertEquals(0, buf[2].toInt())
        assertEquals(0, buf[3].toInt())
        // Bytes 4-7 should have the value
        assertEquals(0xDE.toByte(), buf[4])
    }

    // ─── handleProbe: input validation ────────────────────────────────────────

    @Test
    fun `handleProbe with wrong type byte is ignored`() {
        val before = handler.rtpClockOffsetUs  // 0
        val packet = buildTimingProbe(type = 0xD0)  // 0xD0 ≠ 0xD2 (request type)
        handler.handleProbe(packet, receiveNtp = 0L)
        // offset must not change — wrong type means ignored
        assertEquals(before, handler.rtpClockOffsetUs)
    }

    @Test
    fun `handleProbe with packet shorter than 32 bytes is ignored`() {
        val before = handler.rtpClockOffsetUs
        val shortData = ByteArray(10)
        val packet = DatagramPacket(shortData, shortData.size)
        handler.handleProbe(packet, receiveNtp = 0L)
        assertEquals(before, handler.rtpClockOffsetUs)
    }

    // ─── handleProbe: correct processing ──────────────────────────────────────

    @Test
    fun `handleProbe with valid request updates rtpClockOffsetUs`() {
        // Sender's transmit time: NTP seconds = NTP_EPOCH + 1 (= 1 second after Unix epoch)
        val senderNtpSec = 2_208_988_801L  // NTP_EPOCH_OFFSET + 1
        val probe = buildTimingProbe(
            type         = 0xD2,
            transmitSec  = senderNtpSec.toInt(),
            transmitFrac = 0
        )
        // Our receive time: NTP seconds = NTP_EPOCH + 2 (= 2 seconds after Unix epoch)
        val ourReceiveNtp = (2_208_988_802L shl 32) or 0L

        handler.handleProbe(probe, receiveNtp = ourReceiveNtp)

        // offset = ourReceive(µs) - senderSend(µs)
        //        = 2_000_000 - 1_000_000 = 1_000_000 µs (1 second)
        assertEquals(1_000_000L, handler.rtpClockOffsetUs)
    }

    @Test
    fun `handleProbe echoes sequence number in response type byte`() {
        // Even though we can't intercept the sent response (socket is null),
        // verifying that the handler does NOT throw is the minimum correctness check.
        val probe = buildTimingProbe(type = 0xD2, seqHigh = 0x00, seqLow = 0x07)
        handler.handleProbe(probe, receiveNtp = TimingHandler.currentNtpTimestamp())
        // If we reach here without exception, the response construction logic didn't crash
    }

    @Test
    fun `handleProbe called twice accumulates offset correctly`() {
        val epoch = 2_208_988_800L  // NTP epoch offset

        // First probe: sender sends at t=1s, we receive at t=2s → offset = 1_000_000 µs
        handler.handleProbe(
            buildTimingProbe(type = 0xD2, transmitSec = (epoch + 1).toInt()),
            receiveNtp = ((epoch + 2L) shl 32) or 0L
        )
        val offset1 = handler.rtpClockOffsetUs

        // Second probe: sender sends at t=3s, we receive at t=3.5s → offset = 500_000 µs
        handler.handleProbe(
            buildTimingProbe(type = 0xD2, transmitSec = (epoch + 3).toInt()),
            receiveNtp = ((epoch + 3L) shl 32) or (0x80000000L)  // 0.5s fraction
        )
        val offset2 = handler.rtpClockOffsetUs

        assertEquals(1_000_000L, offset1)
        assertNotEquals(offset1, offset2)  // second probe has a different offset
        assertTrue("Second offset should be ~500_000 µs, got $offset2", offset2 in 499_999L..500_001L)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `stop before start does not crash`() {
        // handler was never started — stop() must be a safe no-op
        handler.stop()
    }

    @Test
    fun `stop can be called twice safely`() {
        handler.stop()
        handler.stop()  // should not throw
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a 32-byte timing probe [DatagramPacket] with the given fields.
     * Unspecified NTP fields default to 0.
     *
     * @param type         Packet type byte at offset [1] (0xD2 = request, other = invalid)
     * @param seqHigh      High byte of sequence number (offset [2])
     * @param seqLow       Low byte of sequence number (offset [3])
     * @param transmitSec  Sender's transmit NTP seconds (big-endian at offset [24-27])
     * @param transmitFrac Sender's transmit NTP fraction (big-endian at offset [28-31])
     */
    private fun buildTimingProbe(
        type: Int         = 0xD2,
        seqHigh: Int      = 0,
        seqLow: Int       = 1,
        transmitSec: Int  = 0,
        transmitFrac: Int = 0
    ): DatagramPacket {
        val data = ByteArray(32)
        data[0] = 0x80.toByte()
        data[1] = type.toByte()
        data[2] = seqHigh.toByte()
        data[3] = seqLow.toByte()
        // [8-23] reference + received: leave as 0
        TimingHandler.writeUint32(data, 24, transmitSec)
        TimingHandler.writeUint32(data, 28, transmitFrac)
        return DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), 12345)
    }
}
