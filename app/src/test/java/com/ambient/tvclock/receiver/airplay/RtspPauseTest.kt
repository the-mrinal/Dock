package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RtspPauseTest — Unit tests for RTSP PAUSE handling (S7-2).
 *
 * WHY: macOS sends PAUSE when the user pauses screen mirroring. Before S7-2
 * the handler returned 501 Not Implemented, causing macOS to abort the session.
 * Now it returns 200 OK so the session is preserved and can resume via RECORD.
 */
class RtspPauseTest {

    private fun makeHandler() = TestableRtspHandler(
        onStreamingStarted = {},
        onStreamingStopped = {}
    )

    private fun pauseRequest() = RtspRequest(
        method = "PAUSE",
        uri = "rtsp://192.168.1.1/phairplay",
        headers = mapOf("CSeq" to "5"),
        body = ""
    )

    @Test
    fun `PAUSE returns 200 OK`() {
        val response = makeHandler().handlePausePublic(pauseRequest())
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.statusMessage)
    }

    @Test
    fun `PAUSE does not require a prior ANNOUNCE`() {
        // PAUSE should be handled gracefully regardless of session state
        val response = makeHandler().handlePausePublic(pauseRequest())
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `OPTIONS advertises PAUSE as a supported method`() {
        val response = makeHandler().handleOptionsPublic(
            RtspRequest(method = "OPTIONS", uri = "", headers = emptyMap(), body = "")
        )
        val publicMethods = response.headers["Public"] ?: ""
        assert("PAUSE" in publicMethods) { "PAUSE must be listed in OPTIONS Public header" }
    }
}
