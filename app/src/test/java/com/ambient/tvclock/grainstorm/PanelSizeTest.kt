package com.ambient.tvclock.grainstorm

import com.ambient.tvclock.grainstorm.DisplayMetricsProvider.PanelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSizeTest {

    @Test
    fun `describes a 4K panel the way Settings shows it`() {
        assertEquals("3840×2160 · 16:9", PanelSize(3840, 2160).describe())
    }

    @Test
    fun `recognises the common television and monitor shapes`() {
        assertEquals("16:9", PanelSize(1920, 1080).aspectLabel())
        assertEquals("16:10", PanelSize(1920, 1200).aspectLabel())
        assertEquals("21:9", PanelSize(3440, 1440).aspectLabel())
        assertEquals("4:3", PanelSize(1024, 768).aspectLabel())
        assertEquals("3:2", PanelSize(2160, 1440).aspectLabel())
        assertEquals("9:16", PanelSize(1080, 1920).aspectLabel())
    }

    @Test
    fun `reduces an unusual ratio rather than mislabelling it`() {
        assertEquals("5:1", PanelSize(1000, 200).aspectLabel())
    }

    @Test
    fun `an unreadable display does not produce a divide by zero`() {
        assertEquals("unknown", PanelSize(0, 0).aspectLabel())
        assertFalse(PanelSize(0, 0).isUsable)
    }

    @Test
    fun `a panel is usable only at a size the library will accept`() {
        // The device schema rejects anything under 200px in either axis.
        assertTrue(PanelSize(3840, 2160).isUsable)
        assertTrue(PanelSize(200, 200).isUsable)
        assertFalse(PanelSize(199, 1080).isUsable)
        assertFalse(PanelSize(1920, 199).isUsable)
    }
}
