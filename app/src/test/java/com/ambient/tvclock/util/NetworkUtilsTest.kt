package com.ambient.tvclock.util

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NetworkUtilsTest — Unit tests for NetworkUtils.
 *
 * WHY: NetworkUtils reads critical information (device name, MAC address) that
 * is used in mDNS TXT records. If these values are wrong or malformed, macOS
 * may reject the device or fail to connect. We need to verify:
 * - The device name is correctly read and sanitized
 * - Invalid characters in device names are removed (security + compatibility)
 * - Fallback values are used when system settings are unavailable
 */
class NetworkUtilsTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver

        // Enable static mocking for Android Settings APIs
        mockkStatic(Settings.Global::class)
        mockkStatic(Settings.Secure::class)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /**
     * Test: getDeviceName returns the device name from Settings.Global.
     */
    @Test
    fun `getDeviceName returns device name from system settings`() {
        every { Settings.Global.getString(mockContentResolver, "device_name") } returns "My TV"

        val name = NetworkUtils.getDeviceName(mockContext)

        assertEquals("My TV", name)
    }

    /**
     * Test: getDeviceName uses fallback when settings return null.
     */
    @Test
    fun `getDeviceName returns default when settings return null`() {
        every { Settings.Global.getString(mockContentResolver, "device_name") } returns null
        every { Settings.Secure.getString(mockContentResolver, "bluetooth_name") } returns null

        val name = NetworkUtils.getDeviceName(mockContext)

        assertNotNull("Device name should never be null", name)
        assertFalse("Device name should not be empty", name.isEmpty())
        assertEquals("PhairPlay", name)
    }

    /**
     * Test: getDeviceName sanitizes special characters.
     */
    @Test
    fun `getDeviceName strips special characters from device name`() {
        every { Settings.Global.getString(mockContentResolver, "device_name") } returns "My <TV> & Device!"

        val name = NetworkUtils.getDeviceName(mockContext)

        assertFalse("Should not contain <", "<" in name)
        assertFalse("Should not contain >", ">" in name)
        assertFalse("Should not contain &", "&" in name)
        assertFalse("Should not contain !", "!" in name)
    }

    /**
     * Test: getDeviceName returns fallback for a name that is all special characters.
     */
    @Test
    fun `getDeviceName returns fallback when name is all special characters`() {
        every { Settings.Global.getString(mockContentResolver, "device_name") } returns "!!!"
        every { Settings.Secure.getString(mockContentResolver, "bluetooth_name") } returns null

        val name = NetworkUtils.getDeviceName(mockContext)

        assertFalse("Name should not be empty after sanitization", name.isEmpty())
    }

    /**
     * Test: getMacAddress returns a string in valid MAC address format.
     *
     * NOTE: In the JVM test environment, NetworkInterface returns the host's real
     * network interfaces. If none have a hardware address, the fallback
     * "aa:bb:cc:dd:ee:ff" is returned — which also satisfies the format check.
     */
    @Test
    fun `getMacAddress returns a valid MAC address format`() {
        val mac = NetworkUtils.getMacAddress()

        assertNotNull("MAC address should not be null", mac)
        assertTrue(
            "MAC address should match format xx:xx:xx:xx:xx:xx but was: $mac",
            mac.matches(Regex("[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}"))
        )
    }
}
