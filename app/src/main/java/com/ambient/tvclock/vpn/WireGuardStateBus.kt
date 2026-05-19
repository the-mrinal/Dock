package com.ambient.tvclock.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process state bridge between [WireGuardController] and the Settings UI.
 * Mirrors the pattern used by [com.ambient.tvclock.receiver.ReceiverStateBus].
 */
object WireGuardStateBus {

    private val _state = MutableStateFlow<VpnState>(VpnState.NoConfig)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun publish(state: VpnState) {
        _state.value = state
    }
}
