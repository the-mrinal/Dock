package com.ambient.tvclock.grainstorm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The sync client against a real socket. The dock lives on a shelf: every
 * failure mode here is one it will actually meet, and none of them may throw.
 */
class GrainstormClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    private val token = "device-token"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tempDir = Files.createTempDirectory("grainstorm-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun client() = GrainstormClient(server.url("/").toString().trimEnd('/'), token)

    private val currentJson = """
        {"version":1,"deviceKey":"firetv-dock","assetId":"a1b2c3d4e5f60718","seed":4805,
         "updatedAt":"2026-08-17T09:00:14Z",
         "image":{"url":"/v1/assets/a1b2c3d4e5f60718/r/full","w":3840,"h":2160,"sha256":"%s"}}
    """.trimIndent()

    @Test
    fun `current carries the bearer token and returns the parsed document`() {
        server.enqueue(MockResponse().setBody(currentJson.format("ab")).setHeader("ETag", "\"v1\""))
        val result = client().current("firetv-dock", null)
        assertTrue(result is GrainstormClient.Result.Ok)
        val (current, etag) = (result as GrainstormClient.Result.Ok).value
        assertEquals("a1b2c3d4e5f60718", current.assetId)
        assertEquals("\"v1\"", etag)

        val request = server.takeRequest()
        assertEquals("/v1/devices/firetv-dock/current", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test
    fun `a known etag is sent and a 304 costs nothing`() {
        server.enqueue(MockResponse().setResponseCode(304))
        assertTrue(client().current("firetv-dock", "\"v1\"") is GrainstormClient.Result.NotModified)
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a rejected token is reported as unauthorized, not as a crash`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client().current("firetv-dock", null)
        assertEquals(GrainstormClient.Failure.Unauthorized, (result as GrainstormClient.Result.Err).failure)
    }

    @Test
    fun `an unknown device is reported as not found`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client().current("ghost", null)
        assertEquals(GrainstormClient.Failure.NotFound, (result as GrainstormClient.Result.Err).failure)
    }

    @Test
    fun `a server error keeps its code so the user can be told something useful`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client().current("firetv-dock", null)
        assertEquals(GrainstormClient.Failure.Server(503), (result as GrainstormClient.Result.Err).failure)
    }

    @Test
    fun `an unreachable server is a failure, never an exception`() {
        server.shutdown()
        val result = client().current("firetv-dock", null)
        assertEquals(GrainstormClient.Failure.Unreachable, (result as GrainstormClient.Result.Err).failure)
    }

    @Test
    fun `a malformed body is reported rather than parsed into nonsense`() {
        server.enqueue(MockResponse().setBody("{ not json"))
        val result = client().current("firetv-dock", null)
        assertTrue((result as GrainstormClient.Result.Err).failure is GrainstormClient.Failure.Malformed)
    }

    @Test
    fun `an unconfigured client never touches the network`() {
        val result = GrainstormClient("", token).current("firetv-dock", null)
        assertEquals(GrainstormClient.Failure.NotConfigured, (result as GrainstormClient.Result.Err).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `listing passes the paging parameters through`() {
        server.enqueue(MockResponse().setBody("""{"version":1,"total":0,"assets":[],"nextCursor":null}"""))
        val result = client().listAssets(limit = 12, cursor = "abc")
        assertTrue(result is GrainstormClient.Result.Ok)
        assertEquals("/v1/assets?limit=12&cursor=abc", server.takeRequest().path)
    }

    @Test
    fun `listing omits the cursor on the first page`() {
        server.enqueue(MockResponse().setBody("""{"version":1,"total":0,"assets":[],"nextCursor":null}"""))
        client().listAssets(limit = 24, cursor = null)
        assertEquals("/v1/assets?limit=24", server.takeRequest().path)
    }

    @Test
    fun `registering sends what a screen can know`() {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client().register("firetv-dock", "Dock", 3840, 2160) is GrainstormClient.Result.Ok)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/devices/register", request.path)
        assertEquals(
            """{"key":"firetv-dock","label":"Dock","w":3840,"h":2160}""",
            request.body.readUtf8()
        )
    }

    @Test
    fun `setting the current wallpaper is a PUT for that device`() {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client().setCurrent("firetv-dock", "a1b2c3d4e5f60718") is GrainstormClient.Result.Ok)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/devices/firetv-dock/current", request.path)
        assertEquals("""{"assetId":"a1b2c3d4e5f60718"}""", request.body.readUtf8())
    }

    // ---- downloads ----

    private val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
    private val imageSha get() = GrainstormClient.sha256(imageBytes)

    @Test
    fun `a verified download lands on disk`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(imageBytes)))
        val target = File(tempDir, "w.png")
        val result = client().download("/v1/assets/x/r/full", target, imageSha)
        assertTrue(result is GrainstormClient.Result.Ok)
        assertArrayEquals(imageBytes, target.readBytes())
    }

    @Test
    fun `a download whose hash does not match is refused and never stored`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(imageBytes)))
        val target = File(tempDir, "w.png")
        val result = client().download("/v1/assets/x/r/full", target, "f".repeat(64))
        assertTrue((result as GrainstormClient.Result.Err).failure is GrainstormClient.Failure.Malformed)
        assertFalse("a corrupt image must not become the wallpaper", target.exists())
    }

    @Test
    fun `a download with no expected hash is accepted`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(imageBytes)))
        val target = File(tempDir, "w.png")
        assertTrue(client().download("/v1/assets/x/r/full", target, null) is GrainstormClient.Result.Ok)
    }

    @Test
    fun `a download creates its directory`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(imageBytes)))
        val target = File(File(tempDir, "nested/deep"), "w.png")
        assertTrue(client().download("/v1/assets/x/r/full", target, imageSha) is GrainstormClient.Result.Ok)
        assertTrue(target.isFile)
    }

    @Test
    fun `a failed download leaves no partial file behind`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val target = File(tempDir, "w.png")
        assertTrue(client().download("/v1/assets/x/r/full", target, imageSha) is GrainstormClient.Result.Err)
        assertFalse(target.exists())
        assertNull(tempDir.listFiles()?.firstOrNull { it.name.endsWith(".part") })
    }

    @Test
    fun `sha256 matches the known answer`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GrainstormClient.sha256("abc".toByteArray())
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
