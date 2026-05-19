package com.ambient.tvclock.vpn

sealed class VpnState {
    object NoConfig : VpnState()
    object Down : VpnState()
    object Connecting : VpnState()
    data class Up(val peerEndpoint: String) : VpnState()
    data class Error(val message: String) : VpnState()
}
