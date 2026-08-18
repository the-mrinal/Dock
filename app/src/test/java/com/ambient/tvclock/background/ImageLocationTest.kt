package com.ambient.tvclock.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract between whoever hands out an image URI and whoever reads it.
 *
 * A cached wallpaper is published as `File.toURI().toString()` and consumed by
 * the background surface. When the reader only recognised `file://` — two
 * slashes — every wallpaper surface on the dock went black with no error
 * anywhere, because the unrecognised local URI was retried as a network
 * request and the resulting scheme exception was swallowed.
 *
 * These tests pin both ends: the shape the producer emits, and the reader's
 * answer for it.
 */
class ImageLocationTest {

    // ---- the producer's shape ----

    @Test
    fun `File toURI yields the single-slash form, not the one most code checks for`() {
        val uri = File("/data/data/app/files/grainstorm/wallpaper.png").toURI().toString()

        // This is the whole trap, stated out loud: the natural way to publish
        // a file URI does not produce the natural thing to check for.
        assertTrue("expected a file: URI, got $uri", uri.startsWith("file:"))
        assertTrue("expected the single-slash form, got $uri", !uri.startsWith("file://"))
    }

    @Test
    fun `what the wallpaper cache publishes resolves to the file it names`() {
        val cached = File("/data/data/app/files/grainstorm/604ed830.png")

        val location = ImageLocation.of(cached.toURI().toString())

        assertEquals(ImageLocation.OnDisk(cached), location)
    }

    // ---- the reader's answers ----

    @Test
    fun `every spelling of a file URI reads from disk`() {
        val expected = ImageLocation.OnDisk(File("/data/wallpaper.png"))

        assertEquals(expected, ImageLocation.of("file:/data/wallpaper.png"))
        assertEquals(expected, ImageLocation.of("file:///data/wallpaper.png"))
    }

    @Test
    fun `a bare absolute path reads from disk`() {
        assertEquals(
            ImageLocation.OnDisk(File("/data/wallpaper.png")),
            ImageLocation.of("/data/wallpaper.png"),
        )
    }

    @Test
    fun `http and https are fetched`() {
        assertEquals(
            ImageLocation.Url("https://images.unsplash.com/photo-1.jpg"),
            ImageLocation.of("https://images.unsplash.com/photo-1.jpg"),
        )
        assertEquals(
            ImageLocation.Url("http://homelab:8079/v1/assets/abc/r/device%3Afiretv-dock"),
            ImageLocation.of("http://homelab:8079/v1/assets/abc/r/device%3Afiretv-dock"),
        )
    }

    @Test
    fun `an encoded path survives untouched, so a rendered asset URL still resolves`() {
        // The library renders per device, and the device key rides in the path
        // as `device%3Akey`. Re-encoding or decoding it would 404.
        val url = "https://wallpapers.example/v1/assets/1d94b0ce/r/device%3Afiretv-dock"

        assertEquals(ImageLocation.Url(url), ImageLocation.of(url))
    }

    // ---- what must never happen ----

    @Test
    fun `a file URI that will not parse is unusable, never a URL to retry`() {
        val location = ImageLocation.of("file:/data/a b.png")

        // The point is not which of null-or-OnDisk it is; it is that a local
        // URI never becomes a network request. That retry is what failed
        // silently and blacked out the wallpaper.
        assertTrue(
            "a file: URI must never resolve to a Url, got $location",
            location !is ImageLocation.Url,
        )
    }

    @Test
    fun `nothing at all is nothing to load`() {
        assertNull(ImageLocation.of(""))
        assertNull(ImageLocation.of("   "))
    }
}
