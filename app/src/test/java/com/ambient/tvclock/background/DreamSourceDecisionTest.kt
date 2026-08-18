package com.ambient.tvclock.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the system screensaver paints.
 *
 * The dream needs a DreamService to instantiate, so the decision it makes is
 * modelled here exactly as GrainstormDreamService.sourceId() composes it, and
 * pinned. It differs from the in-app one in a single way, and that difference
 * is the point of this file.
 */
class DreamSourceDecisionTest {

    private val wallpaper = "grainstorm"

    private fun dreamSource(preference: String?): String? {
        if (preference == AmbientBackgroundPolicy.SAME_AS_AWAKE) return wallpaper
        return AmbientBackgroundPolicy.fromPreference(preference).ambientSourceId(wallpaper)
    }

    @Test
    fun `an unconfigured dock still dreams in black`() {
        assertNull(dreamSource(null))
        assertNull(dreamSource(""))
        assertNull(dreamSource(AmbientBackgroundPolicy.BLACK))
    }

    @Test
    fun `choosing the wallpaper as the screensaver shows it`() {
        assertEquals("grainstorm", dreamSource("grainstorm"))
    }

    /**
     * "Same as above" means "match the dashboard", and out here there is no
     * dashboard to match — so it means the wallpaper, which is what someone
     * choosing it would have meant.
     */
    @Test
    fun `same-as-awake resolves to the wallpaper, since a dream has no awake source`() {
        assertEquals("grainstorm", dreamSource(AmbientBackgroundPolicy.SAME_AS_AWAKE))
    }

    @Test
    fun `a source the dream cannot host is still named, so the caller can refuse it`() {
        // Album art needs the dashboard's MediaSession plumbing, which is not
        // reachable from a DreamService; the service paints black instead.
        assertEquals("album_art", dreamSource("album_art"))
    }
}
