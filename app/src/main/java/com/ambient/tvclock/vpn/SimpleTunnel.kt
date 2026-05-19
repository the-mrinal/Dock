package com.ambient.tvclock.vpn

import com.wireguard.android.backend.Tunnel

/**
 * Minimal [Tunnel] implementation. The library only needs a name and a state-change
 * callback; we route the callback into [WireGuardStateBus] downstream.
 */
class SimpleTunnel(
    private val tunnelName: String,
    private val onState: (Tunnel.State) -> Unit,
) : Tunnel {
    override fun getName(): String = tunnelName
    override fun onStateChange(newState: Tunnel.State) {
        onState(newState)
    }
}
