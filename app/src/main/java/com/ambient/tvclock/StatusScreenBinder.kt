package com.ambient.tvclock

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ambient.tvclock.receiver.ActiveConnection
import com.ambient.tvclock.receiver.Protocol
import com.ambient.tvclock.vpn.VpnPreferences
import com.ambient.tvclock.vpn.VpnState

/**
 * Combined AirPlay + VPN status page binder. Each side has its own indicator
 * + status copy + action button. Both buttons stay focusable so D-pad
 * LEFT/RIGHT moves between them (nextFocus wired in the layout).
 */
class StatusScreenBinder(
    private val root: View,
    private val onAirplayAction: (AirplayAction) -> Unit,
    private val onVpnAction: (VpnAction) -> Unit
) {

    enum class AirplayAction { TURN_ON, TURN_OFF }
    enum class VpnAction { CONNECT, DISCONNECT, IMPORT }

    // --- AirPlay side
    private val airplayDot: View = root.findViewById(R.id.airplayStatusDot)
    private val airplayHalo: View = root.findViewById(R.id.airplayStatusHalo)
    private val airplayStatus: TextView = root.findViewById(R.id.textAirplayStatus)
    private val airplayDetail: TextView = root.findViewById(R.id.textAirplayDetail)
    val airplayButton: TextView = root.findViewById(R.id.buttonAirplayAction)
    private var currentAirplayAction: AirplayAction = AirplayAction.TURN_ON

    // --- VPN side
    private val vpnDot: View = root.findViewById(R.id.vpnStatusDot)
    private val vpnHalo: View = root.findViewById(R.id.vpnStatusHalo)
    private val vpnStatus: TextView = root.findViewById(R.id.textVpnStatus)
    private val vpnDetail: TextView = root.findViewById(R.id.textVpnDetail)
    val vpnButton: TextView = root.findViewById(R.id.buttonVpnAction)
    private var currentVpnAction: VpnAction = VpnAction.CONNECT

    init {
        airplayButton.setOnClickListener { onAirplayAction(currentAirplayAction) }
        vpnButton.setOnClickListener { onVpnAction(currentVpnAction) }
    }

    fun bindAirplay(activeConnection: ActiveConnection?) {
        val context = root.context
        val receiverEnabled = ReceiverPreferences.isReceiverEnabled(context)

        when {
            !receiverEnabled -> {
                tint(airplayDot, airplayHalo, R.color.status_idle)
                airplayStatus.text = context.getString(R.string.airplay_status_off)
                airplayDetail.text = context.getString(R.string.airplay_detail_off)
                setAirplayAction(AirplayAction.TURN_ON, R.string.airplay_action_turn_on)
            }
            activeConnection != null && activeConnection.protocol == Protocol.AIRPLAY -> {
                tint(airplayDot, airplayHalo, R.color.status_ok)
                airplayStatus.text = context.getString(R.string.airplay_status_streaming)
                airplayDetail.text = context.getString(
                    R.string.airplay_detail_streaming,
                    activeConnection.senderName
                )
                setAirplayAction(AirplayAction.TURN_OFF, R.string.airplay_action_turn_off)
            }
            else -> {
                tint(airplayDot, airplayHalo, R.color.status_ok)
                airplayStatus.text = context.getString(R.string.airplay_status_ready)
                airplayDetail.text = context.getString(R.string.airplay_detail_ready)
                setAirplayAction(AirplayAction.TURN_OFF, R.string.airplay_action_turn_off)
            }
        }
    }

    fun bindVpn(state: VpnState) {
        val context = root.context
        when (state) {
            VpnState.NoConfig -> {
                tint(vpnDot, vpnHalo, R.color.status_idle)
                vpnStatus.text = context.getString(R.string.vpn_status_no_config)
                vpnDetail.text = context.getString(R.string.vpn_detail_no_config)
                setVpnAction(VpnAction.IMPORT, R.string.vpn_action_import_in_settings, enabled = true)
            }
            VpnState.Down -> {
                tint(vpnDot, vpnHalo, R.color.status_idle)
                vpnStatus.text = context.getString(R.string.vpn_status_disconnected)
                vpnDetail.text = context.getString(R.string.vpn_detail_disconnected)
                setVpnAction(VpnAction.CONNECT, R.string.vpn_action_connect, enabled = true)
            }
            VpnState.Connecting -> {
                tint(vpnDot, vpnHalo, R.color.status_pending)
                vpnStatus.text = context.getString(R.string.vpn_status_connecting)
                vpnDetail.text = context.getString(R.string.vpn_detail_connecting)
                setVpnAction(VpnAction.DISCONNECT, R.string.vpn_action_connecting, enabled = false)
            }
            is VpnState.Up -> {
                tint(vpnDot, vpnHalo, R.color.status_ok)
                vpnStatus.text = context.getString(R.string.vpn_status_connected)
                vpnDetail.text = context.getString(R.string.vpn_detail_connected, state.peerEndpoint)
                setVpnAction(VpnAction.DISCONNECT, R.string.vpn_action_disconnect, enabled = true)
            }
            is VpnState.Error -> {
                tint(vpnDot, vpnHalo, R.color.status_error)
                vpnStatus.text = context.getString(R.string.vpn_status_error)
                vpnDetail.text = context.getString(R.string.vpn_detail_error, state.message)
                setVpnAction(VpnAction.CONNECT, R.string.vpn_action_retry, enabled = true)
            }
        }
    }

    private fun setAirplayAction(action: AirplayAction, labelRes: Int) {
        currentAirplayAction = action
        airplayButton.setText(labelRes)
    }

    private fun setVpnAction(action: VpnAction, labelRes: Int, enabled: Boolean) {
        currentVpnAction = action
        vpnButton.setText(labelRes)
        vpnButton.isEnabled = enabled
        vpnButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun tint(dot: View, halo: View, colorRes: Int) {
        val color = ContextCompat.getColor(root.context, colorRes)
        dot.background?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        halo.backgroundTintList = ColorStateList.valueOf(haloTint(color))
    }

    private fun haloTint(color: Int): Int {
        val alpha = 0x24
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
