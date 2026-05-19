package com.ambient.tvclock.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.StringReader

/**
 * Owns the singleton [GoBackend] for the process and exposes a coarse-grained
 * `connect()` / `disconnect()` surface to the Settings UI. Mirrors the role of
 * [com.ambient.tvclock.receiver.ReceiverController] for the receiver subsystem.
 *
 * The library handles the actual [android.net.VpnService] lifecycle internally
 * via [GoBackend.VpnService] — see AndroidManifest.xml.
 */
object WireGuardController {

    private const val TUNNEL_NAME = "wg0"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var backend: GoBackend? = null

    private val tunnel: Tunnel = SimpleTunnel(TUNNEL_NAME) { newState ->
        // The library calls this on its own thread; publish into the StateFlow.
        when (newState) {
            Tunnel.State.DOWN -> WireGuardStateBus.publish(VpnState.Down)
            Tunnel.State.UP -> {
                // Endpoint is filled in by the caller after a successful setState.
                if (WireGuardStateBus.state.value !is VpnState.Up) {
                    WireGuardStateBus.publish(VpnState.Up(peerEndpoint = ""))
                }
            }
            Tunnel.State.TOGGLE -> Unit
        }
    }

    /** Fires off an async connect using the currently-stored config. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch { connect(app) }
    }

    /** Fires off an async disconnect. */
    @Suppress("UNUSED_PARAMETER")
    fun stop(context: Context) {
        scope.launch { disconnect() }
    }

    private fun connect(context: Context) {
        val store = WireGuardConfigStore(context)
        val raw = store.loadRaw()
        if (raw == null) {
            WireGuardStateBus.publish(VpnState.Error("No config imported"))
            return
        }
        val patched = Rfc1918Carveout.applyV4LanBypass(raw)
        val config: Config = try {
            Config.parse(BufferedReader(StringReader(patched)))
        } catch (e: Exception) {
            Timber.w(e, "WireGuardController: patched config does not parse")
            WireGuardStateBus.publish(VpnState.Error(e.message ?: "config parse failed"))
            return
        }

        WireGuardStateBus.publish(VpnState.Connecting)
        val be = backend(context)
        try {
            be.setState(tunnel, Tunnel.State.UP, config)
            val endpoint = config.peers.firstOrNull()
                ?.endpoint?.orElse(null)
                ?.toString()
                .orEmpty()
            WireGuardStateBus.publish(VpnState.Up(endpoint))
            Timber.i("WireGuardController: tunnel up · peer=%s", endpoint)
        } catch (e: Exception) {
            Timber.w(e, "WireGuardController: setState(UP) failed")
            WireGuardStateBus.publish(VpnState.Error(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun disconnect() {
        val be = backend
        if (be == null) {
            WireGuardStateBus.publish(VpnState.Down)
            return
        }
        try {
            be.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Timber.w(e, "WireGuardController: setState(DOWN) failed")
        }
        WireGuardStateBus.publish(VpnState.Down)
    }

    @Synchronized
    private fun backend(context: Context): GoBackend {
        return backend ?: GoBackend(context.applicationContext).also { backend = it }
    }
}
