package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Tests for the AirPlay SET_PARAMETER metadata / progress parsers.
 *
 * The DAAP wire format and the `progress:` text-parameter line are the
 * only two payload shapes the receiver translates into UI state; getting
 * either wrong silently corrupts what the Now Playing screen shows.
 */
class AirPlayNowPlayingTest {

    // ─── DAAP parser ────────────────────────────────────────────────────────

    @Test
    fun `parses flat minm asar asal records`() {
        // What iOS actually sends most of the time: three string records
        // back-to-back, no `mlit` wrapper.
        val body = concat(
            daapString("minm", "Bohemian Rhapsody"),
            daapString("asar", "Queen"),
            daapString("asal", "A Night at the Opera")
        )

        val meta = AirPlayDaapParser.parse(body)

        assertEquals("Bohemian Rhapsody", meta.title)
        assertEquals("Queen", meta.artist)
        assertEquals("A Night at the Opera", meta.album)
    }

    @Test
    fun `parses tags wrapped in mlit container`() {
        // Some senders wrap per-track fields in `mlit` (list item) so the
        // parser must descend into containers — otherwise wrapped streams
        // would render with all-null metadata.
        val inner = concat(
            daapString("minm", "Hey Jude"),
            daapString("asar", "The Beatles")
        )
        val body = daapContainer("mlit", inner)

        val meta = AirPlayDaapParser.parse(body)

        assertEquals("Hey Jude", meta.title)
        assertEquals("The Beatles", meta.artist)
        assertNull(meta.album)
    }

    @Test
    fun `unknown tags are skipped without breaking framing`() {
        // Senders include lots of tags we don't surface (`mper` persistent
        // id, `asbt` beats, etc.). Skipping by length must not lose framing
        // for the tags we do care about.
        val body = concat(
            daapBytes("mper", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)),  // 8 bytes
            daapString("minm", "Song"),
            daapBytes("asbt", byteArrayOf(0, 120)),                   // 2 bytes
            daapString("asar", "Artist")
        )

        val meta = AirPlayDaapParser.parse(body)

        assertEquals("Song", meta.title)
        assertEquals("Artist", meta.artist)
    }

    @Test
    fun `truncated length stops parse without crashing`() {
        // A malformed body — `minm` claims 100 bytes but the buffer only
        // has 4. Must bail safely; previous successfully-parsed fields stay.
        val good = daapString("asar", "Real Artist")
        val truncated = ByteBuffer.allocate(8 + 4).apply {
            put("minm".toByteArray(Charsets.US_ASCII))
            putInt(100)
            put("aaaa".toByteArray(Charsets.US_ASCII))
        }.array()

        val meta = AirPlayDaapParser.parse(concat(good, truncated))

        assertEquals("Real Artist", meta.artist)
        assertNull(meta.title)
    }

    @Test
    fun `empty body parses cleanly to all-null metadata`() {
        val meta = AirPlayDaapParser.parse(ByteArray(0))
        assertNull(meta.title)
        assertNull(meta.artist)
        assertNull(meta.album)
    }

    @Test
    fun `utf8 string with non-ascii characters round-trips`() {
        val body = daapString("minm", "Café del Mar — Vol. 12")
        val meta = AirPlayDaapParser.parse(body)
        assertEquals("Café del Mar — Vol. 12", meta.title)
    }

    // ─── progress: parser ───────────────────────────────────────────────────

    @Test
    fun `progress line decodes three rtp timestamps`() {
        // Real-world line iOS sends every ~1s during playback.
        val body = "progress: 1234/56789/123456\r\n"

        val p = parseAirPlayProgress(body, sampleRate = 44100, nowMs = 1_000_000L)

        assertNotNull(p)
        assertEquals(1234L, p!!.rtpStart)
        assertEquals(56789L, p.rtpCurrent)
        assertEquals(123456L, p.rtpEnd)
        assertEquals(44100, p.sampleRate)
        assertEquals(1_000_000L, p.updatedAtMs)
    }

    @Test
    fun `progress is ignored when body has only volume`() {
        val body = "volume: -10.000000\r\n"
        assertNull(parseAirPlayProgress(body, sampleRate = 44100, nowMs = 0L))
    }

    @Test
    fun `progress with two slash-separated values is rejected`() {
        // Malformed body shouldn't propagate as a half-filled progress.
        val body = "progress: 1234/56789\r\n"
        assertNull(parseAirPlayProgress(body, sampleRate = 44100, nowMs = 0L))
    }

    @Test
    fun `elapsedSeconds extrapolates between updates and clamps to total`() {
        val sampleRate = 44100
        val total = sampleRate * 200L                 // 200-second track
        val current = sampleRate * 30L                // 30 s elapsed at update
        val p = AirPlayProgress(
            rtpStart = 0L,
            rtpCurrent = current,
            rtpEnd = total,
            sampleRate = sampleRate,
            updatedAtMs = 10_000L
        )

        assertEquals(30.0, p.elapsedSeconds(10_000L), 0.01)
        assertEquals(35.0, p.elapsedSeconds(15_000L), 0.01)
        // Clamp: 5 minutes after the update we're still bounded by total.
        assertEquals(200.0, p.elapsedSeconds(310_000L), 0.01)
    }

    @Test
    fun `totalSeconds is zero when sampleRate is missing`() {
        val p = AirPlayProgress(0L, 0L, 100L, sampleRate = 0, updatedAtMs = 0L)
        assertEquals(0.0, p.totalSeconds(), 0.0)
        assertEquals(0.0, p.elapsedSeconds(0L), 0.0)
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun daapString(tag: String, value: String): ByteArray =
        daapBytes(tag, value.toByteArray(Charsets.UTF_8))

    private fun daapContainer(tag: String, body: ByteArray): ByteArray =
        daapBytes(tag, body)

    private fun daapBytes(tag: String, payload: ByteArray): ByteArray {
        require(tag.length == 4)
        val out = ByteBuffer.allocate(8 + payload.size)
        out.put(tag.toByteArray(Charsets.US_ASCII))
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var i = 0
        for (p in parts) { System.arraycopy(p, 0, out, i, p.size); i += p.size }
        return out
    }
}
