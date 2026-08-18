package com.ambient.tvclock.background

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision the screensaver bug lived in.
 *
 * `BackgroundController` needs an Android Context, so the decision itself is
 * modelled here exactly as the controller composes it — awake preference, then
 * ambient policy — and pinned. If this changes, the screensaver changes.
 */
class AmbientDecisionTest {

    private fun decide(
        playing: Boolean,
        whenPlaying: String,
        whenIdle: String,
        whenAmbient: String?,
        ambient: Boolean,
    ): String {
        val awake = if (playing) whenPlaying else whenIdle
        if (!ambient) return awake
        return AmbientBackgroundPolicy.fromPreference(whenAmbient).ambientSourceId(awake) ?: "black"
    }

    @Test
    fun `awake, the source depends on whether a song is playing`() {
        assertEquals("album_art", decide(true, "album_art", "grainstorm", null, ambient = false))
        assertEquals("grainstorm", decide(false, "album_art", "grainstorm", null, ambient = false))
    }

    @Test
    fun `the old behaviour is preserved when nothing is configured`() {
        // This is the bug as it shipped: idle defaults to black, and going
        // ambient could only ever produce black too.
        assertEquals("black", decide(false, "album_art", "black", null, ambient = true))
        assertEquals("black", decide(true, "album_art", "black", null, ambient = true))
    }

    @Test
    fun `choosing the wallpaper as the screensaver shows it while idle`() {
        assertEquals("grainstorm", decide(false, "album_art", "black", "grainstorm", ambient = true))
    }

    @Test
    fun `the screensaver wins over the awake source, even mid-song`() {
        // Album art while music plays, wallpaper once the room goes quiet.
        assertEquals("album_art", decide(true, "album_art", "black", "grainstorm", ambient = false))
        assertEquals("grainstorm", decide(true, "album_art", "black", "grainstorm", ambient = true))
    }

    @Test
    fun `same-as-awake carries the awake source into the screensaver`() {
        assertEquals("unsplash", decide(false, "album_art", "unsplash", "same", ambient = true))
        assertEquals("album_art", decide(true, "album_art", "unsplash", "same", ambient = true))
    }

    @Test
    fun `explicitly choosing black still gives a black screensaver`() {
        assertEquals("black", decide(false, "album_art", "grainstorm", "black", ambient = true))
    }
}
