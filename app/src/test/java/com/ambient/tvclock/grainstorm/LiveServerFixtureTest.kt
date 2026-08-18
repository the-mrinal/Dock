package com.ambient.tvclock.grainstorm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the contract.
 *
 * `SyncContractTest` pins what this app *expects*; these fixtures are what a
 * real grainstorm sync server actually sent, captured verbatim from a running
 * instance. Hand-written expectations can drift from the server together with
 * the parser and still agree with each other — recorded bytes cannot.
 *
 * Regenerate by running the server and saving:
 *   GET /v1/devices/<key>/current  -> current.json
 *   GET /v1/assets?limit=1         -> assets.json
 */
class LiveServerFixtureTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("grainstorm/$name")) {
            "missing test fixture grainstorm/$name"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `a real current document parses`() {
        val current = SyncContract.parseCurrent(fixture("current.json"))
        assertNotNull("the server's own output must parse", current)
        current!!
        assertEquals("firetv-dock", current.deviceKey)
        assertEquals("a1b2c3d4e5f60718", current.assetId)
        assertEquals(4805, current.seed)
    }

    @Test
    fun `the server picks this screen's exact rendition, so no size matching is needed here`() {
        val current = SyncContract.parseCurrent(fixture("current.json"))!!
        assertEquals(3840, current.image.width)
        assertEquals(2160, current.image.height)
        assertTrue(
            "the exact device rendition should have been chosen",
            current.image.url.contains("device%3Afiretv-dock")
        )
    }

    @Test
    fun `the image is content-addressed so a download can be verified`() {
        val current = SyncContract.parseCurrent(fixture("current.json"))!!
        assertEquals(64, current.image.sha256.length)
        assertTrue(current.image.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `a relative image url resolves against a configured server`() {
        val current = SyncContract.parseCurrent(fixture("current.json"))!!
        assertEquals(
            "http://homelab:8079/v1/assets/a1b2c3d4e5f60718/r/device%3Afiretv-dock",
            SyncContract.absoluteUrl("http://homelab:8079", current.image.url)
        )
    }

    @Test
    fun `a real asset listing parses, newest first`() {
        val page = SyncContract.parseAssetPage(fixture("assets.json"))
        assertNotNull("the server's own listing must parse", page)
        page!!
        assertEquals(2, page.total)
        assertEquals(1, page.assets.size)
        assertEquals("b2c3d4e5f6071829", page.assets[0].id)
        assertEquals(6653, page.assets[0].seed)
    }

    @Test
    fun `every resolution the server stored is visible to the picker`() {
        val asset = SyncContract.parseAssetPage(fixture("assets.json"))!!.assets[0]
        assertEquals(
            listOf("device:firetv-dock", "full", "thumb"),
            asset.renditions.map { it.name }.sorted()
        )
        assertEquals(400, asset.previewRendition()!!.width)
        assertEquals(3840, asset.largestRendition()!!.width)
    }

    @Test
    fun `a cursor is offered while more pages remain`() {
        val page = SyncContract.parseAssetPage(fixture("assets.json"))!!
        assertNotNull("limit=1 of 2 assets must offer a next page", page.nextCursor)
        assertEquals("b2c3d4e5f6071829", page.nextCursor)
    }

    @Test
    fun `a multiline quote survives the round trip`() {
        val asset = SyncContract.parseAssetPage(fixture("assets.json"))!!.assets[0]
        assertEquals("quiet work\n*compounds*.", asset.quote)
    }
}
