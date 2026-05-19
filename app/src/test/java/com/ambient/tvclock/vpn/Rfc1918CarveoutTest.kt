package com.ambient.tvclock.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc1918CarveoutTest {

    @Test
    fun `default route is expanded into carveout list`() {
        val raw = """
            [Interface]
            PrivateKey = abc
            Address = 10.7.0.2/32

            [Peer]
            PublicKey = def
            AllowedIPs = 0.0.0.0/0
            Endpoint = 1.2.3.4:51820
        """.trimIndent()

        val patched = Rfc1918Carveout.applyV4LanBypass(raw)

        assertTrue(
            "default route should be removed",
            !patched.lines().any { it.trim() == "AllowedIPs = 0.0.0.0/0" }
        )
        Rfc1918Carveout.IPV4_PUBLIC_CIDRS.forEach { cidr ->
            assertTrue(
                "carveout should contain $cidr",
                patched.contains(cidr)
            )
        }
    }

    @Test
    fun `RFC1918 ranges never appear in the carveout`() {
        val excluded = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4")
        excluded.forEach { cidr ->
            assertTrue(
                "$cidr must not appear in IPV4_PUBLIC_CIDRS",
                cidr !in Rfc1918Carveout.IPV4_PUBLIC_CIDRS
            )
        }
    }

    @Test
    fun `split-tunnel narrow routes are preserved untouched`() {
        val raw = """
            [Peer]
            AllowedIPs = 10.7.0.0/16, 8.8.8.8/32
        """.trimIndent()

        val patched = Rfc1918Carveout.applyV4LanBypass(raw)

        assertTrue(patched.contains("10.7.0.0/16"))
        assertTrue(patched.contains("8.8.8.8/32"))
        // No carveout list should have been inserted for narrow ranges.
        assertTrue(!patched.contains("0.0.0.0/5"))
    }

    @Test
    fun `non-AllowedIPs lines pass through unchanged`() {
        val raw = """
            [Interface]
            PrivateKey = abc123
            Address = 10.7.0.2/32
            DNS = 1.1.1.1
        """.trimIndent()

        val patched = Rfc1918Carveout.applyV4LanBypass(raw)

        assertEquals(raw, patched)
    }

    @Test
    fun `mixed default plus extra entry expands only the default`() {
        val raw = "AllowedIPs = 0.0.0.0/0, 198.51.100.0/24"
        val patched = Rfc1918Carveout.applyV4LanBypass(raw)
        assertTrue(patched.contains("198.51.100.0/24"))
        assertTrue(patched.contains("0.0.0.0/5"))
        assertTrue(!patched.contains("0.0.0.0/0"))
    }
}
