package com.ambient.tvclock.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSourceRegistryTest {

    private class FakeSource(override val id: String) : BackgroundSource {
        var started = false
        var paused = false
        var settings = mutableListOf<String>()
        override fun start(listener: (BackgroundImage) -> Unit) { started = true }
        override fun stop() { started = false }
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun current(): BackgroundImage? = null
        override fun onSettingChanged(key: String) { settings += key }
    }

    @Test
    fun `a registered source is reachable by id`() {
        val registry = BackgroundSourceRegistry.builder()
            .register("unsplash") { FakeSource("unsplash") }
            .build()
        assertTrue(registry.isKnown("unsplash"))
        assertEquals("unsplash", registry.get("unsplash")?.id)
    }

    @Test
    fun `an unregistered id is null, not an exception`() {
        val registry = BackgroundSourceRegistry.builder().build()
        assertFalse(registry.isKnown("nope"))
        assertNull(registry.get("nope"))
    }

    @Test
    fun `a source is built once and reused`() {
        var built = 0
        val registry = BackgroundSourceRegistry.builder()
            .register("x") { built++; FakeSource("x") }
            .build()
        val first = registry.get("x")
        assertSame(first, registry.get("x"))
        assertEquals(1, built)
    }

    @Test
    fun `registering a source costs nothing until it is selected`() {
        var built = 0
        BackgroundSourceRegistry.builder().register("x") { built++; FakeSource("x") }.build()
        assertEquals(0, built)
    }

    @Test
    fun `only built sources are reported as instantiated`() {
        val registry = BackgroundSourceRegistry.builder()
            .register("a") { FakeSource("a") }
            .register("b") { FakeSource("b") }
            .build()
        assertTrue(registry.instantiated().isEmpty())
        registry.get("a")
        assertEquals(listOf("a"), registry.instantiated().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registering the same id twice is a programming error`() {
        BackgroundSourceRegistry.builder()
            .register("a") { FakeSource("a") }
            .register("a") { FakeSource("a") }
    }

    @Test
    fun `ids are reported in registration order`() {
        val registry = BackgroundSourceRegistry.builder()
            .register("black") { FakeSource("black") }
            .register("album_art") { FakeSource("album_art") }
            .register("grainstorm") { FakeSource("grainstorm") }
            .build()
        assertEquals(listOf("black", "album_art", "grainstorm"), registry.ids.toList())
    }
}
