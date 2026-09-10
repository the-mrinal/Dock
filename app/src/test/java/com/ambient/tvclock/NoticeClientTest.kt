package com.ambient.tvclock

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WHY: the notice feed is served by whatever the user has running at home, so
 * the client meets reboots, typos in the URL, and half-written JSON. None of
 * those may throw — each must come back as a failure the board can ride out.
 *
 * WHAT WE TEST: the happy path with its ETag, conditional requests, and every
 * failure classification against a real socket.
 */
class NoticeClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = NoticeClient(server.url("/api/notices").toString())

    private val feedJson = """
        {"version":1,"notices":[
          {"id":"bins","title":"Bins out","html":"<p>Before 7</p>",
           "starts_at":"2026-09-10T18:00:00+05:30","ends_at":"2026-09-11T08:00:00+05:30"}
        ]}
    """.trimIndent()

    @Test
    fun `a good feed parses and carries the response etag`() {
        server.enqueue(MockResponse().setBody(feedJson).setHeader("ETag", "\"v1\""))

        val result = client().fetch(null)

        val feed = (result as NoticeClient.Result.Ok).value
        assertEquals("bins", feed.notices.single().id)
        assertEquals("\"v1\"", feed.etag)
        val request = server.takeRequest()
        assertEquals("application/json", request.getHeader("Accept"))
        assertNull("no etag to send on a first fetch", request.getHeader("If-None-Match"))
    }

    @Test
    fun `a known etag is sent and an unchanged board comes back as NotModified`() {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = client().fetch("\"v1\"")

        assertTrue(result is NoticeClient.Result.NotModified)
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `http failures map to their own kind`() {
        val cases = mapOf(
            401 to NoticeClient.Failure.Unauthorized,
            403 to NoticeClient.Failure.Unauthorized,
            404 to NoticeClient.Failure.NotFound,
            500 to NoticeClient.Failure.Server(500),
        )
        for ((code, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(code))
            val result = client().fetch(null)
            assertEquals("HTTP $code", expected, (result as NoticeClient.Result.Err).failure)
        }
    }

    @Test
    fun `a body that is not a feed is malformed, not a crash`() {
        server.enqueue(MockResponse().setBody("{ not json"))

        val result = client().fetch(null)

        assertTrue((result as NoticeClient.Result.Err).failure is NoticeClient.Failure.Malformed)
    }

    @Test
    fun `a server that is not there is unreachable`() {
        val client = client()
        server.shutdown()

        val result = client.fetch(null)

        assertEquals(NoticeClient.Failure.Unreachable, (result as NoticeClient.Result.Err).failure)
    }

    @Test
    fun `an unconfigured board makes no request at all`() {
        val result = NoticeClient("").fetch(null)

        assertEquals(NoticeClient.Failure.NotConfigured, (result as NoticeClient.Result.Err).failure)
        assertEquals(0, server.requestCount)
    }
}
