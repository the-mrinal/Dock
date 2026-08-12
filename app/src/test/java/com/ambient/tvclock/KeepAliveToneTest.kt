package com.ambient.tvclock

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeepAliveToneTest — Unit tests for the keep-alive tone math.
 *
 * WHY: The loop buffer is written once into a MODE_STATIC AudioTrack and
 * repeated ~1000 times per burst. If the period length is wrong or the buffer
 * doesn't start/end at a zero crossing, every loop boundary produces an
 * audible click — the exact opposite of an inaudible tone. And if the
 * amplitude math is off, the tone is either audible or too weak to keep the
 * soundbar awake.
 */
class KeepAliveToneTest {

    @Test
    fun `one period of 40Hz at 48kHz is exactly 1200 frames`() {
        assertEquals(1200, KeepAliveTone.periodFrames(48_000, 40))
    }

    @Test
    fun `loop buffer starts and ends at a zero crossing`() {
        val buffer = KeepAliveTone.generateLoopBuffer(48_000, 40, -50.0)
        assertEquals(1200, buffer.size)
        assertEquals(0, buffer.first().toInt())
        // Last sample is one frame before the wrap-around zero crossing.
        assertTrue("last sample ${buffer.last()} should be ~0", abs(buffer.last().toInt()) <= 1)
    }

    @Test
    fun `peak amplitude matches -50 dBFS`() {
        val buffer = KeepAliveTone.generateLoopBuffer(48_000, 40, -50.0)
        val peak = buffer.maxOf { abs(it.toInt()) }
        // 32767 * 10^(-50/20) ≈ 103.6
        assertTrue("peak $peak should be ~104", abs(peak - 104) <= 1)
    }

    @Test
    fun `no sample exceeds the requested peak`() {
        val buffer = KeepAliveTone.generateLoopBuffer(48_000, 40, -50.0)
        assertTrue(buffer.all { abs(it.toInt()) <= 105 })
    }

    @Test
    fun `loop count fills 25 seconds with 40Hz periods`() {
        // 1000 total periods = first play + 999 repeats.
        assertEquals(999, KeepAliveTone.loopCount(25_000L, 40))
    }
}
