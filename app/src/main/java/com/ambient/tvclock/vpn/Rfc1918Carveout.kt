package com.ambient.tvclock.vpn

/**
 * IPv4 CIDR list that covers 0.0.0.0/0 minus the RFC1918 private ranges,
 * link-local (169.254/16), and multicast/reserved (224/4). Pre-computed so
 * we don't ship a CIDR-subtract algorithm just to express a static result.
 *
 * Used to rewrite `AllowedIPs = 0.0.0.0/0` in the user's WireGuard config so
 * that Spotify Connect mDNS, AirPlay/Cast/Miracast advertisements, and
 * adb-over-LAN keep working while the tunnel is up.
 */
object Rfc1918Carveout {

    /** IPv4 0.0.0.0/0 minus [10/8, 169.254/16, 172.16/12, 192.168/16, 224/4]. */
    val IPV4_PUBLIC_CIDRS: List<String> = listOf(
        "0.0.0.0/5",
        "8.0.0.0/7",
        "11.0.0.0/8",
        "12.0.0.0/6",
        "16.0.0.0/4",
        "32.0.0.0/3",
        "64.0.0.0/2",
        "128.0.0.0/3",
        "160.0.0.0/5",
        "168.0.0.0/8",
        "169.0.0.0/9",
        "169.128.0.0/10",
        "169.192.0.0/11",
        "169.224.0.0/12",
        "169.240.0.0/13",
        "169.248.0.0/14",
        "169.252.0.0/15",
        "169.255.0.0/16",
        "170.0.0.0/7",
        "172.0.0.0/12",
        "172.32.0.0/11",
        "172.64.0.0/10",
        "172.128.0.0/9",
        "173.0.0.0/8",
        "174.0.0.0/7",
        "176.0.0.0/4",
        "192.0.0.0/9",
        "192.128.0.0/11",
        "192.160.0.0/13",
        "192.169.0.0/16",
        "192.170.0.0/15",
        "192.172.0.0/14",
        "192.176.0.0/12",
        "192.192.0.0/10",
        "193.0.0.0/8",
        "194.0.0.0/7",
        "196.0.0.0/6",
        "200.0.0.0/5",
        "208.0.0.0/4",
    )

    private val DEFAULT_V4_PATTERNS = listOf("0.0.0.0/0")
    private val DEFAULT_V6_PATTERNS = listOf("::/0")

    /**
     * Rewrites every `AllowedIPs = …` line in [rawConfig] so that `0.0.0.0/0`
     * is replaced by [IPV4_PUBLIC_CIDRS]. Other entries (specific subnets,
     * IPv6) are passed through unchanged.
     *
     * The user's intent is preserved for split-tunnel configs: only the
     * literal default route gets expanded.
     */
    fun applyV4LanBypass(rawConfig: String): String {
        val out = StringBuilder(rawConfig.length + 512)
        for (line in rawConfig.lineSequence()) {
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore('=', missingDelimiterValue = "").trim()
            if (!key.equals("AllowedIPs", ignoreCase = true)) {
                out.append(line).append('\n')
                continue
            }
            val value = trimmed.substringAfter('=').trim()
            val entries = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val rewritten = entries.flatMap { cidr ->
                if (cidr in DEFAULT_V4_PATTERNS) IPV4_PUBLIC_CIDRS
                else listOf(cidr)
            }
            out.append("AllowedIPs = ").append(rewritten.joinToString(", ")).append('\n')
        }
        // Drop the trailing newline we appended after the last line if the original didn't end in one.
        if (!rawConfig.endsWith('\n') && out.endsWith('\n')) out.setLength(out.length - 1)
        return out.toString()
    }
}
