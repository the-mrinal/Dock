package com.ambient.tvclock.grainstorm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract with the grainstorm sync server, as documented in that
 * repo's docs/specs/sync-protocol.md. Parsing is deliberately tolerant of
 * unknown fields and intolerant of an unknown major version.
 */
class SyncContractTest {

    private val currentJson = """
        {
          "version": 1,
          "deviceKey": "firetv-dock",
          "assetId": "a1b2c3d4e5f60718",
          "seed": 4805,
          "updatedAt": "2026-08-17T09:00:14Z",
          "image": {
            "url": "/v1/assets/a1b2c3d4e5f60718/r/device%3Afiretv-dock",
            "w": 3840, "h": 2160,
            "sha256": "b81474abf174dd477cfab24575dd4c916dcc2466b08e9b572f0301ec552748fe"
          }
        }
    """.trimIndent()

    @Test
    fun `parses what a screen should show right now`() {
        val current = SyncContract.parseCurrent(currentJson)!!
        assertEquals("firetv-dock", current.deviceKey)
        assertEquals("a1b2c3d4e5f60718", current.assetId)
        assertEquals(4805, current.seed)
        assertEquals(3840, current.image.width)
        assertEquals(2160, current.image.height)
        assertEquals(
            "b81474abf174dd477cfab24575dd4c916dcc2466b08e9b572f0301ec552748fe",
            current.image.sha256
        )
    }

    @Test
    fun `tolerates fields it has never heard of`() {
        val forward = currentJson.replace("\"version\": 1", "\"version\": 1, \"mood\": \"calm\"")
        assertEquals("a1b2c3d4e5f60718", SyncContract.parseCurrent(forward)!!.assetId)
    }

    @Test
    fun `refuses an unknown major version rather than half-understanding it`() {
        assertNull(SyncContract.parseCurrent(currentJson.replace("\"version\": 1", "\"version\": 2")))
    }

    @Test
    fun `refuses a document missing the image or its url`() {
        assertNull(SyncContract.parseCurrent("""{"version":1,"deviceKey":"d","assetId":"a"}"""))
        assertNull(SyncContract.parseCurrent("""{"version":1,"deviceKey":"d","assetId":"a","image":{"w":1,"h":1}}"""))
    }

    @Test
    fun `refuses malformed json instead of throwing`() {
        assertNull(SyncContract.parseCurrent("{ not json"))
        assertNull(SyncContract.parseCurrent(""))
    }

    @Test
    fun `resolves a relative image url against the server base`() {
        val current = SyncContract.parseCurrent(currentJson)!!
        assertEquals(
            "http://homelab:8079/v1/assets/a1b2c3d4e5f60718/r/device%3Afiretv-dock",
            SyncContract.absoluteUrl("http://homelab:8079", current.image.url)
        )
    }

    @Test
    fun `tolerates a trailing slash on the configured base url`() {
        assertEquals(
            "http://homelab:8079/v1/assets/x",
            SyncContract.absoluteUrl("http://homelab:8079/", "/v1/assets/x")
        )
    }

    @Test
    fun `leaves an already-absolute image url alone`() {
        assertEquals(
            "https://cdn.example/x.png",
            SyncContract.absoluteUrl("http://homelab:8079", "https://cdn.example/x.png")
        )
    }

    // ---- the asset listing the picker browses ----

    private val listJson = """
        {
          "version": 1,
          "total": 2,
          "nextCursor": "bbbbbbbbbbbbbbbb",
          "assets": [
            { "version": 1, "id": "aaaaaaaaaaaaaaaa", "seed": 41,
              "createdAt": "2026-08-17T09:00:00Z", "color": "#0e1418", "aspect": 1.5397,
              "design": { "hash": "e", "scene": "landscape", "quote": "quiet work" },
              "renditions": {
                "thumb": { "url": "/v1/assets/aaaaaaaaaaaaaaaa/r/thumb", "w": 400, "h": 260, "bytes": 1, "sha256": "aa" },
                "full":  { "url": "/v1/assets/aaaaaaaaaaaaaaaa/r/full", "w": 3024, "h": 1964, "bytes": 2, "sha256": "bb" }
              } },
            { "version": 1, "id": "bbbbbbbbbbbbbbbb", "seed": 42,
              "createdAt": "2026-08-16T09:00:00Z",
              "design": {},
              "renditions": {
                "full": { "url": "/v1/assets/bbbbbbbbbbbbbbbb/r/full", "w": 800, "h": 600, "bytes": 3, "sha256": "cc" }
              } }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a page of assets with their resolutions`() {
        val page = SyncContract.parseAssetPage(listJson)!!
        assertEquals(2, page.assets.size)
        assertEquals("bbbbbbbbbbbbbbbb", page.nextCursor)
        assertEquals(2, page.total)

        val first = page.assets[0]
        assertEquals("aaaaaaaaaaaaaaaa", first.id)
        assertEquals(41, first.seed)
        assertEquals("quiet work", first.quote)
        assertEquals("#0e1418", first.color)
        assertEquals(listOf(400, 3024), first.renditions.map { it.width }.sorted())
    }

    @Test
    fun `a null nextCursor means the last page`() {
        val page = SyncContract.parseAssetPage(listJson.replace("\"bbbbbbbbbbbbbbbb\",", "null,"))!!
        assertNull(page.nextCursor)
    }

    @Test
    fun `prefers the thumb rendition for a grid cell and falls back to the smallest`() {
        val page = SyncContract.parseAssetPage(listJson)!!
        assertEquals(400, page.assets[0].previewRendition()!!.width)
        // The second asset has only `full` — better a large preview than none.
        assertEquals(800, page.assets[1].previewRendition()!!.width)
    }

    @Test
    fun `skips an asset with no usable renditions rather than dropping the page`() {
        val broken = listJson.replace(
            """"renditions": {
                "full": { "url": "/v1/assets/bbbbbbbbbbbbbbbb/r/full", "w": 800, "h": 600, "bytes": 3, "sha256": "cc" }
              }""".trimIndent().replace("\n", "\n"), """"renditions": {}"""
        )
        val page = SyncContract.parseAssetPage(broken)
        assertTrue(page != null)
    }

    @Test
    fun `refuses a listing of an unknown version`() {
        assertNull(SyncContract.parseAssetPage(listJson.replace("\"version\": 1,\n  \"total\"", "\"version\": 9,\n  \"total\"")))
    }

    @Test
    fun `an empty library is a valid, empty page`() {
        val page = SyncContract.parseAssetPage("""{"version":1,"total":0,"assets":[],"nextCursor":null}""")!!
        assertTrue(page.assets.isEmpty())
        assertNull(page.nextCursor)
    }

    // ---- what the TV sends ----

    @Test
    fun `builds the self-registration body from what a screen can know`() {
        assertEquals(
            """{"key":"firetv-dock","label":"Living room","w":3840,"h":2160}""",
            SyncContract.registerBody("firetv-dock", "Living room", 3840, 2160)
        )
    }

    @Test
    fun `escapes a label that contains quotes`() {
        val body = SyncContract.registerBody("k", "Mrinal's \"Dock\"", 100, 100)
        assertTrue(body, body.contains("\\\"Dock\\\""))
    }

    @Test
    fun `builds the set-wallpaper body`() {
        assertEquals("""{"assetId":"a1b2c3d4e5f60718"}""", SyncContract.setCurrentBody("a1b2c3d4e5f60718"))
    }
}
