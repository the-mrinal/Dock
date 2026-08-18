package com.ambient.tvclock.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screensaver decision. The default has to stay "black" so an existing
 * dock behaves exactly as it did before this feature landed.
 */
class AmbientBackgroundPolicyTest {

    @Test
    fun `an unset preference keeps the original true-black screensaver`() {
        assertNull(AmbientBackgroundPolicy.fromPreference(null).ambientSourceId("unsplash"))
        assertNull(AmbientBackgroundPolicy.fromPreference("").ambientSourceId("unsplash"))
    }

    @Test
    fun `black is black whatever was showing awake`() {
        val policy = AmbientBackgroundPolicy.fromPreference(AmbientBackgroundPolicy.BLACK)
        assertNull(policy.ambientSourceId("grainstorm"))
        assertNull(policy.ambientSourceId("album_art"))
    }

    @Test
    fun `same-as-awake keeps painting whatever was already up`() {
        val policy = AmbientBackgroundPolicy.fromPreference(AmbientBackgroundPolicy.SAME_AS_AWAKE)
        assertEquals("grainstorm", policy.ambientSourceId("grainstorm"))
        assertEquals("album_art", policy.ambientSourceId("album_art"))
    }

    @Test
    fun `naming a source pins the screensaver to it regardless of the awake source`() {
        val policy = AmbientBackgroundPolicy.fromPreference("grainstorm")
        assertEquals("grainstorm", policy.ambientSourceId("album_art"))
        assertEquals("grainstorm", policy.ambientSourceId("black"))
    }

    @Test
    fun `the prebuilt policies behave as named`() {
        assertNull(AmbientBackgroundPolicy.AlwaysBlack.ambientSourceId("x"))
        assertEquals("x", AmbientBackgroundPolicy.KeepAwakeSource.ambientSourceId("x"))
        assertEquals("y", AmbientBackgroundPolicy.fixed("y").ambientSourceId("x"))
    }
}
