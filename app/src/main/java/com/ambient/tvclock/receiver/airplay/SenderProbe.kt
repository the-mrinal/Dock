package com.ambient.tvclock.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ambient.tvclock.util.Logger
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic probe: gathers every bit of information we can extract about
 * the AirPlay sender so we can pick the right label strategy with real data
 * from each device class (Mac / iPad / iPhone) instead of guessing.
 *
 * Invoked once per AirPlay session from the RECORD / /play hook. Runs an
 * mDNS browse on a handful of Apple service types, resolves each discovered
 * service, and dumps:
 *   - peer IP (v4/v6, link-local zone)
 *   - reverse DNS attempt (libc resolver — Android does NOT consult mDNS here)
 *   - every discovered service instance with its service-type, instance name,
 *     resolved host:port, and TXT attributes
 *   - explicit "MATCH BY IP" hits when a discovered service resolves to the
 *     same IP as the AirPlay sender
 *
 * After [WINDOW_MS] elapses or when discovery is stopped, prints a summary
 * block prefixed with [LOG_PREFIX] so we can grep it out of logcat across
 * three different test devices and compare.
 *
 * Not used in production decision-making yet — the active UI label still
 * comes from [SenderIdentity]. Leave the probe in until we've collected the
 * data we need, then either delete it or promote its findings into a
 * concrete labelling strategy.
 */
class SenderProbe(
    private val context: Context,
    private val peer: InetAddress,
    private val rtspHeaders: Map<String, String>,
    private val rtspMethod: String,
    private val rtspUri: String
) {

    private val nsd: NsdManager? = try {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: Exception) {
        Logger.e("SenderProbe: NSD_SERVICE unavailable", e); null
    }

    private val main = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val matches = ConcurrentHashMap<String, MutableList<String>>()
    private val browsers = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        Logger.i("$LOG_PREFIX ─── BEGIN sender probe ────────────────────────────────")
        Logger.i("$LOG_PREFIX trigger: $rtspMethod $rtspUri")
        Logger.i("$LOG_PREFIX peer ip: ${peer.hostAddress}  (canonical='${runCatching { peer.canonicalHostName }.getOrNull()}')")
        Logger.i("$LOG_PREFIX peer is link-local=${peer.isLinkLocalAddress} loopback=${peer.isLoopbackAddress}")
        Logger.i("$LOG_PREFIX rtsp headers (${rtspHeaders.size}):")
        rtspHeaders.entries.sortedBy { it.key }.forEach { (k, v) ->
            Logger.i("$LOG_PREFIX   $k: $v")
        }

        val mgr = nsd
        if (mgr == null) {
            Logger.w("$LOG_PREFIX NsdManager not available — skipping mDNS browse")
            finish()
            return
        }

        SERVICE_TYPES.forEach { type ->
            val listener = makeDiscoveryListener(mgr, type)
            browsers.add(listener)
            try {
                mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Logger.w("$LOG_PREFIX discoverServices($type) failed: ${e.message}")
            }
        }

        main.postDelayed({ finish() }, WINDOW_MS)
    }

    private fun makeDiscoveryListener(
        mgr: NsdManager,
        serviceType: String
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(s: String) {
            Logger.d("$LOG_PREFIX browse[$serviceType] started")
        }

        override fun onDiscoveryStopped(s: String) {
            Logger.d("$LOG_PREFIX browse[$serviceType] stopped")
        }

        override fun onStartDiscoveryFailed(s: String, code: Int) {
            Logger.w("$LOG_PREFIX browse[$serviceType] start failed code=$code")
        }

        override fun onStopDiscoveryFailed(s: String, code: Int) {
            Logger.w("$LOG_PREFIX browse[$serviceType] stop failed code=$code")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            Logger.i("$LOG_PREFIX browse[$serviceType] found '${info.serviceName}' — resolving…")
            resolveCompat(mgr, info, serviceType)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Logger.d("$LOG_PREFIX browse[$serviceType] lost '${info.serviceName}'")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCompat(
        mgr: NsdManager,
        info: NsdServiceInfo,
        serviceType: String
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(svc: NsdServiceInfo, errorCode: Int) {
                Logger.d("$LOG_PREFIX resolve[$serviceType '${svc.serviceName}'] failed code=$errorCode")
            }

            override fun onServiceResolved(svc: NsdServiceInfo) {
                val host = svc.host?.hostAddress ?: "<null>"
                val port = svc.port
                val name = svc.serviceName ?: "<null>"
                val txt = formatTxt(svc)
                val sameIp = svc.host != null && peer.hostAddress.equals(svc.host.hostAddress, ignoreCase = true)
                val hit = if (sameIp) " *** MATCH BY PEER IP ***" else ""
                Logger.i("$LOG_PREFIX resolved[$serviceType] name='$name' host=$host port=$port$hit")
                if (txt.isNotBlank()) Logger.i("$LOG_PREFIX   txt: $txt")
                if (sameIp) {
                    matches.getOrPut(serviceType) { mutableListOf() }.add(name)
                }
            }
        }

        try {
            mgr.resolveService(info, resolveListener)
        } catch (e: Exception) {
            Logger.w("$LOG_PREFIX resolve[$serviceType '${info.serviceName}'] threw: ${e.message}")
        }
    }

    private fun formatTxt(info: NsdServiceInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return ""
        val attrs = info.attributes ?: return ""
        return attrs.entries.joinToString(" ") { (k, v) ->
            val printable = v?.let { bytes ->
                if (bytes.all { it in 0x20..0x7E }) String(bytes, Charsets.US_ASCII)
                else bytes.joinToString("") { "%02x".format(it) }
            } ?: "<null>"
            "$k=$printable"
        }
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        Logger.i("$LOG_PREFIX ─── SUMMARY (matches by peer IP) ─────────────────────")
        if (matches.isEmpty()) {
            Logger.i("$LOG_PREFIX   (no discovered service resolved to the peer IP within ${WINDOW_MS}ms)")
        } else {
            matches.forEach { (type, names) ->
                Logger.i("$LOG_PREFIX   $type → ${names.joinToString(", ")}")
            }
        }
        Logger.i("$LOG_PREFIX ─── END sender probe ──────────────────────────────────")

        nsd?.let { mgr ->
            browsers.forEach { b ->
                try { mgr.stopServiceDiscovery(b) } catch (_: Exception) { /* idempotent */ }
            }
        }
        browsers.clear()
    }

    companion object {
        private const val LOG_PREFIX = "[SenderProbe]"
        private const val WINDOW_MS = 6_000L

        /**
         * Apple service types we expect Mac / iPad / iPhone to advertise. Cast
         * a wide net for the POC — the more we see, the more we know about
         * what we can rely on for the final label strategy.
         */
        private val SERVICE_TYPES = listOf(
            "_companion-link._tcp.",  // handoff / continuity — every signed-in Apple device
            "_airplay._tcp.",         // AirPlay 2 receivers
            "_raop._tcp.",            // AirPlay audio receivers
            "_apple-mobdev2._tcp.",   // iOS device companion
            "_workstation._tcp.",     // macOS-specific
            "_homekit._tcp.",         // HomeKit accessories
            "_sleep-proxy._udp."      // Apple Wake-on-LAN, often carries hostname
        )
    }
}
