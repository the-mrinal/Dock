package com.ambient.tvclock.receiver.airplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AirPlayPlaybackHandlerTest — Unit tests for [AirPlayPlaybackHandler].
 *
 * The handler is the parsing seam between iOS-shaped HTTP requests and
 * [AirPlayVideoPlayer] — broken parsing here turns into "the AirPlay button
 * does nothing" on a real iPhone, which is hard to diagnose from logs alone.
 * Every supported endpoint has at least one happy-path and one failure-path
 * case so regressions surface in CI rather than on the user's TV.
 */
class AirPlayPlaybackHandlerTest {

    private lateinit var player: AirPlayVideoPlayer
    private lateinit var handler: AirPlayPlaybackHandler
    private val playRequestedNames = mutableListOf<String>()

    @Before
    fun setUp() {
        player = mockk(relaxed = true)
        playRequestedNames.clear()
        handler = AirPlayPlaybackHandler(
            videoPlayer = player,
            onPlayRequested = { name -> playRequestedNames += name }
        )
    }

    // ─── claims() ────────────────────────────────────────────────────────────

    @Test fun `claims the five video URL routes`() {
        for (path in listOf("/play", "/stop", "/rate", "/scrub", "/playback-info")) {
            val req = request("GET", path)
            assertTrue("claims $path", handler.claims(req))
        }
    }

    @Test fun `claims accounts for query strings`() {
        assertTrue(handler.claims(request("POST", "/rate?value=1.0")))
        assertTrue(handler.claims(request("POST", "/scrub?position=12.345")))
    }

    @Test fun `does not claim foreign routes`() {
        assertTrue(!handler.claims(request("GET", "/info")))
        assertTrue(!handler.claims(request("POST", "/pair-setup")))
        assertTrue(!handler.claims(request("POST", "/fp-setup")))
    }

    // ─── /play — text/parameters body (the iOS default) ──────────────────────

    @Test fun `play text body extracts URL and start position`() {
        val body = "Content-Location: http://stream.example.com/movie.m3u8\r\n" +
                   "Start-Position: 0.5\r\n"
        val req = request("POST", "/play", body = body)

        val response = handler.handle(req)
        assertEquals(200, response.statusCode)
        verify { player.play("http://stream.example.com/movie.m3u8", 0.5) }
    }

    @Test fun `play text body preserves colons inside URL value`() {
        // The URL value itself contains a colon (http:) — the parser must split
        // on the FIRST colon, not on every one.
        val body = "Content-Location: https://video.example.com:8443/master.m3u8\r\n"
        val req = request("POST", "/play", body = body)

        handler.handle(req)
        verify { player.play("https://video.example.com:8443/master.m3u8", 0.0) }
    }

    @Test fun `play with empty body returns 400`() {
        val response = handler.handle(request("POST", "/play"))
        assertEquals(400, response.statusCode)
        verify(exactly = 0) { player.play(any(), any()) }
    }

    @Test fun `play without Content-Location returns 400`() {
        val body = "Start-Position: 0.5\r\n"
        val response = handler.handle(request("POST", "/play", body = body))
        assertEquals(400, response.statusCode)
        verify(exactly = 0) { player.play(any(), any()) }
    }

    // ─── /play — binary plist body (newer iOS) ───────────────────────────────

    @Test fun `play binary plist body extracts URL and start position`() {
        val dict = NSDictionary().apply {
            put("Content-Location", NSString("http://plist.example.com/movie.m3u8"))
            put("Start-Position", NSNumber(2.5))
        }
        val plistBytes = BinaryPropertyListWriter.writeToArray(dict)
        val req = request("POST", "/play", bodyBytes = plistBytes)

        val response = handler.handle(req)
        assertEquals(200, response.statusCode)
        verify { player.play("http://plist.example.com/movie.m3u8", 2.5) }
    }

    // ─── /play — onPlayRequested hook ────────────────────────────────────────

    @Test fun `play invokes onPlayRequested with extracted sender name`() {
        val body = "Content-Location: http://x.example/m.m3u8\r\n"
        val req = request("POST", "/play", body = body, userAgent = "MediaControl/1.0")

        handler.handle(req)

        assertEquals(listOf("MediaControl"), playRequestedNames)
    }

    @Test fun `play uses default sender name when User-Agent absent`() {
        val body = "Content-Location: http://x.example/m.m3u8\r\n"
        handler.handle(request("POST", "/play", body = body, userAgent = null))

        assertEquals(listOf("AirPlay"), playRequestedNames)
    }

    // ─── /stop ───────────────────────────────────────────────────────────────

    @Test fun `stop returns 200 and calls player`() {
        val response = handler.handle(request("POST", "/stop"))
        assertEquals(200, response.statusCode)
        verify { player.stop() }
    }

    // ─── /rate ───────────────────────────────────────────────────────────────

    @Test fun `rate parses value query parameter`() {
        every { player.setRate(any()) } just Runs

        val response = handler.handle(request("POST", "/rate?value=1.000000"))

        assertEquals(200, response.statusCode)
        verify { player.setRate(1.0f) }
    }

    @Test fun `rate without value returns 400`() {
        val response = handler.handle(request("POST", "/rate"))
        assertEquals(400, response.statusCode)
        verify(exactly = 0) { player.setRate(any()) }
    }

    @Test fun `rate with non-numeric value returns 400`() {
        val response = handler.handle(request("POST", "/rate?value=abc"))
        assertEquals(400, response.statusCode)
    }

    // ─── /scrub — POST seek ──────────────────────────────────────────────────

    @Test fun `scrub POST parses position query parameter`() {
        every { player.scrub(any()) } just Runs

        val response = handler.handle(request("POST", "/scrub?position=42.5"))

        assertEquals(200, response.statusCode)
        verify { player.scrub(42.5) }
    }

    @Test fun `scrub POST without position returns 400`() {
        val response = handler.handle(request("POST", "/scrub"))
        assertEquals(400, response.statusCode)
    }

    // ─── /scrub — GET (poll target) ──────────────────────────────────────────

    @Test fun `scrub GET returns duration and position as text parameters`() {
        every { player.durationMs } returns 123_456L
        every { player.positionMs } returns 7_890L

        val response = handler.handle(request("GET", "/scrub"))

        assertEquals(200, response.statusCode)
        assertEquals("text/parameters", response.headers["Content-Type"])
        val body = String(response.bodyBytes, Charsets.UTF_8)
        assertTrue("contains duration: $body", body.contains("duration: 123.456000"))
        assertTrue("contains position: $body", body.contains("position: 7.890000"))
    }

    // ─── /playback-info ──────────────────────────────────────────────────────

    @Test fun `playback-info returns XML plist with required keys`() {
        every { player.durationMs } returns 60_000L
        every { player.positionMs } returns 10_500L
        every { player.bufferedPositionMs } returns 20_000L
        every { player.ratePlaying } returns true
        every { player.readyToPlay } returns true
        every { player.buffering } returns false
        every { player.isActive() } returns true

        val response = handler.handle(request("GET", "/playback-info"))

        assertEquals(200, response.statusCode)
        assertEquals("text/x-apple-plist+xml", response.headers["Content-Type"])

        val dict = PropertyListParser.parse(response.bodyBytes) as NSDictionary
        for (key in listOf(
            "duration", "position", "rate", "readyToPlay",
            "playbackBufferEmpty", "playbackBufferFull", "playbackLikelyToKeepUp",
            "loadedTimeRanges", "seekableTimeRanges"
        )) {
            assertTrue("plist must contain '$key'", dict.containsKey(key))
        }

        assertEquals(60.0, (dict["duration"] as NSNumber).doubleValue(), 1e-6)
        assertEquals(10.5, (dict["position"] as NSNumber).doubleValue(), 1e-6)
        assertEquals(1.0, (dict["rate"] as NSNumber).doubleValue(), 1e-6)
        assertEquals(true, (dict["readyToPlay"] as NSNumber).boolValue())
    }

    @Test fun `playback-info reports rate=0 when paused`() {
        every { player.durationMs } returns 60_000L
        every { player.positionMs } returns 10_000L
        every { player.bufferedPositionMs } returns 15_000L
        every { player.ratePlaying } returns false
        every { player.readyToPlay } returns true
        every { player.buffering } returns false
        every { player.isActive() } returns true

        val response = handler.handle(request("GET", "/playback-info"))

        val dict = PropertyListParser.parse(response.bodyBytes) as NSDictionary
        assertEquals(0.0, (dict["rate"] as NSNumber).doubleValue(), 1e-6)
    }

    @Test fun `playback-info reports buffering state`() {
        every { player.durationMs } returns 60_000L
        every { player.positionMs } returns 5_000L
        every { player.bufferedPositionMs } returns 5_500L
        every { player.ratePlaying } returns false
        every { player.readyToPlay } returns true
        every { player.buffering } returns true
        every { player.isActive() } returns true

        val response = handler.handle(request("GET", "/playback-info"))

        val dict = PropertyListParser.parse(response.bodyBytes) as NSDictionary
        assertEquals(true, (dict["playbackBufferEmpty"] as NSNumber).boolValue())
        assertEquals(false, (dict["playbackLikelyToKeepUp"] as NSNumber).boolValue())
    }

    // ─── unknown method on claimed route ─────────────────────────────────────

    @Test fun `unsupported method on claimed path returns 501`() {
        val response = handler.handle(request("DELETE", "/play"))
        assertEquals(501, response.statusCode)
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun request(
        method: String,
        uri: String,
        body: String = "",
        bodyBytes: ByteArray? = null,
        userAgent: String? = "AirPlay/376.1.1"
    ): RtspRequest {
        val headers = mutableMapOf("CSeq" to "1")
        if (userAgent != null) headers["User-Agent"] = userAgent
        return RtspRequest(
            method = method,
            uri = uri,
            protocol = "HTTP/1.1",
            headers = headers,
            body = if (bodyBytes != null) "" else body,
            bodyBytes = bodyBytes ?: body.toByteArray(Charsets.UTF_8)
        )
    }
}
