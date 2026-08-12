package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import java.net.InetAddress

/**
 * Resolves a human-readable sender label ("Mrinal's Mac", "iPhone", "Apple TV")
 * from the headers AirPlay senders attach to RTSP / AirPlay control requests.
 *
 * Strategy, in order of preference:
 *   1. A friendly-name header — `X-Apple-Client-Name` or `Client-Name` — when
 *      the sender includes one. macOS Sonoma+ tends to send the system name
 *      here (e.g. "Mrinal's MacBook Pro"), which is exactly what we want on
 *      the overlay pill.
 *   2. Device class derived from `User-Agent` keywords. Apple's AirPlay 2
 *      framework reports `AirPlay/<ver>` without device info, but some senders
 *      (especially during /play video-URL flow) include "iPhone" / "iPad" /
 *      "Macintosh" / "macOS" tokens we can match on.
 *   3. A generic "Apple device" fallback so the pill never reads "AirPlay" or
 *      empty.
 *
 * All header lookups are case-insensitive — RTSP allows either casing and
 * different sender versions disagree.
 */
object SenderIdentity {

    fun label(headers: Map<String, String>?, peer: InetAddress? = null): String {
        if (headers != null) {
            val friendly = headerCaseInsensitive(headers, "X-Apple-Client-Name")
                ?: headerCaseInsensitive(headers, "Client-Name")
            if (!friendly.isNullOrBlank()) return friendly.trim()

            val userAgent = headerCaseInsensitive(headers, "User-Agent")
            val uaLabel = classifyUserAgent(userAgent)
            if (uaLabel != DEFAULT_LABEL) return uaLabel
        }

        // Apple's AirPlay 2 framework sends `User-Agent: AirPlay/<ver>` and no
        // identity headers, so the heuristic above usually falls through. As a
        // last resort, try a reverse hostname lookup on the peer IP: macOS /
        // iOS publish their friendly name via mDNS as `<computer>.local`, and
        // when the LAN resolver can reach that record we get back something
        // like "Mrinals-MacBook-Pro.local" — which both identifies the device
        // class (MacBook → Mac) and provides a friendly name. We humanise it
        // by stripping `.local` and turning dashes into spaces.
        peer?.let { addr ->
            val host = runCatching { addr.canonicalHostName }.getOrNull()
            val humanised = host?.let(::humaniseMdnsHost)
            if (!humanised.isNullOrBlank()) {
                Logger.i("SenderIdentity: reverse-resolved ${addr.hostAddress} → '$humanised'")
                return humanised
            } else {
                Logger.i("SenderIdentity: reverse lookup of ${addr.hostAddress} returned '$host' (unusable)")
            }
        }
        return DEFAULT_LABEL
    }

    /**
     * Turns an mDNS hostname like `Mrinals-MacBook-Pro.local` into the friendly
     * label `Mrinals MacBook Pro`. Returns null when the input is an IP literal
     * (canonicalHostName falls back to that when the resolver can't reach DNS
     * or mDNS) so the caller can fall through to a generic device label.
     */
    private fun humaniseMdnsHost(host: String): String? {
        if (host.isBlank()) return null
        if (host.any { it.isDigit() && !host.contains('-') } && host.matches(IP_REGEX)) return null
        val stripped = host.removeSuffix(".local.").removeSuffix(".local")
        if (stripped.isBlank()) return null
        if (stripped.matches(IP_REGEX)) return null
        return stripped.replace('-', ' ').replace('_', ' ').trim()
    }

    private val IP_REGEX = Regex("""^[0-9a-fA-F:.%]+$""")

    /**
     * Classifies a User-Agent string to a device family. Exposed so other
     * call sites (e.g. the video-URL /play path) that have only the UA string
     * can share the same heuristic.
     */
    fun classifyUserAgent(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_LABEL
        val ua = userAgent
        return when {
            ua.contains("iPad", ignoreCase = true) -> "iPad"
            ua.contains("iPhone", ignoreCase = true) -> "iPhone"
            ua.contains("Apple TV", ignoreCase = true) ||
                ua.contains("AppleTV", ignoreCase = true) -> "Apple TV"
            ua.contains("Macintosh", ignoreCase = true) ||
                ua.contains("macOS", ignoreCase = true) ||
                ua.contains("Mac OS X", ignoreCase = true) ||
                ua.contains("Darwin", ignoreCase = true) -> "Mac"
            else -> DEFAULT_LABEL
        }
    }

    /**
     * Diagnostic log of every header that might carry sender identity. Called
     * when a session first connects so we can verify the heuristics against
     * real-world senders without having to run a packet capture.
     */
    fun logIdentityHeaders(prefix: String, headers: Map<String, String>) {
        val keys = listOf(
            "User-Agent",
            "X-Apple-Client-Name",
            "Client-Name",
            "Client-Bundle-ID",
            "X-Apple-Device-ID",
            "X-Apple-ProtocolVersion",
            "X-Apple-Session-ID"
        )
        val parts = keys.mapNotNull { key ->
            val value = headerCaseInsensitive(headers, key) ?: return@mapNotNull null
            "$key=\"$value\""
        }
        if (parts.isNotEmpty()) {
            Logger.i("$prefix sender headers: ${parts.joinToString(" ")}")
        } else {
            Logger.i("$prefix sender headers: <none of the known identity fields present>")
        }
    }

    private fun headerCaseInsensitive(headers: Map<String, String>, name: String): String? {
        headers[name]?.let { return it }
        val lower = name.lowercase()
        for ((k, v) in headers) {
            if (k.lowercase() == lower) return v
        }
        return null
    }

    private const val DEFAULT_LABEL = "Apple device"
}
