package com.ambient.tvclock

import android.view.View
import androidx.core.content.ContextCompat
import com.ambient.tvclock.receiver.ActiveConnection
import com.ambient.tvclock.receiver.Protocol
import com.ambient.tvclock.ui.PillButton
import com.ambient.tvclock.ui.ServiceColumnView
import com.ambient.tvclock.vpn.VpnState

/**
 * Drives the redesigned Connect surface (artboards 07 / 08).
 *
 * Two `ServiceColumnView`s sit side by side (AirPlay | VPN); this binder
 * owns the small per-side state machine and drives `setState(...)` on each.
 *
 * AirPlay states: Off / Ready / Streaming.
 * VPN states:     Disconnected / Connecting / Connected / Error / NoConfig.
 *
 * MainActivity expects the action button IDs `buttonAirplayAction` /
 * `buttonVpnAction` to participate in D-pad edge detection. We assign those
 * IDs at runtime so the internal `serviceColumnAction` view found inside
 * each merge still routes focus the same way the legacy layout did.
 */
class StatusScreenBinder(
    private val root: View,
    private val onAirplayAction: (AirplayAction) -> Unit,
    private val onVpnAction: (VpnAction) -> Unit
) {

    enum class AirplayAction { TURN_ON, TURN_OFF }
    enum class VpnAction { CONNECT, DISCONNECT, IMPORT }

    private val airplayColumn: ServiceColumnView = root.findViewById(R.id.serviceColumnAirplay)
    private val vpnColumn: ServiceColumnView = root.findViewById(R.id.serviceColumnVpn)

    /** Exposed for MainActivity's `vpnButton.post { requestFocus() }` calls. */
    val airplayButton: PillButton get() = airplayColumn.actionButton
    val vpnButton: PillButton get() = vpnColumn.actionButton

    private var currentAirplayAction: AirplayAction = AirplayAction.TURN_ON
    private var currentVpnAction: VpnAction = VpnAction.CONNECT

    init {
        // Re-id the inner action buttons so MainActivity's
        // shouldNavigatePages() can still match `R.id.buttonAirplayAction` /
        // `R.id.buttonVpnAction` on the focused view.
        airplayColumn.actionButton.id = R.id.buttonAirplayAction
        vpnColumn.actionButton.id = R.id.buttonVpnAction

        // Wire LEFT/RIGHT explicitly so D-pad bounces between the two
        // service buttons (parent-level checks block page-paging only on the
        // outermost edge).
        airplayColumn.actionButton.nextFocusRightId = R.id.buttonVpnAction
        vpnColumn.actionButton.nextFocusLeftId = R.id.buttonAirplayAction

        airplayColumn.setOnActionClickListener { onAirplayAction(currentAirplayAction) }
        vpnColumn.setOnActionClickListener { onVpnAction(currentVpnAction) }
    }

    fun bindAirplay(activeConnection: ActiveConnection?) {
        val context = root.context
        val receiverEnabled = ReceiverPreferences.isReceiverEnabled(context)
        val accent = ContextCompat.getColor(context, R.color.c_airplay)
        val eyebrowText = context.getString(R.string.connect_airplay_eyebrow)

        when {
            !receiverEnabled -> {
                airplayColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.airplay_status_off),
                    detail = context.getString(R.string.airplay_detail_off),
                    actionLabel = context.getString(R.string.connect_action_turn_on),
                    accent = accent,
                    active = false,
                )
                currentAirplayAction = AirplayAction.TURN_ON
            }
            activeConnection != null && activeConnection.protocol == Protocol.AIRPLAY -> {
                airplayColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.airplay_status_streaming),
                    detail = context.getString(
                        R.string.connect_airplay_detail_streaming,
                        activeConnection.senderName
                    ),
                    actionLabel = context.getString(R.string.connect_action_stop),
                    accent = accent,
                    active = true,
                    filled = false,
                )
                currentAirplayAction = AirplayAction.TURN_OFF
            }
            else -> {
                airplayColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.airplay_status_ready),
                    detail = context.getString(R.string.airplay_detail_ready),
                    actionLabel = context.getString(R.string.airplay_action_turn_off),
                    accent = accent,
                    active = false,
                )
                currentAirplayAction = AirplayAction.TURN_OFF
            }
        }
    }

    fun bindVpn(state: VpnState) {
        val context = root.context
        val activeColor = ContextCompat.getColor(context, R.color.c_vpn)
        val idleAccent = ContextCompat.getColor(context, R.color.c_vpn)
        val errAccent = ContextCompat.getColor(context, R.color.status_error)
        val pendingAccent = ContextCompat.getColor(context, R.color.c_amber)
        val eyebrowText = context.getString(R.string.connect_vpn_eyebrow)

        when (state) {
            VpnState.NoConfig -> {
                vpnColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.vpn_status_no_config),
                    detail = context.getString(R.string.vpn_detail_no_config),
                    actionLabel = context.getString(R.string.connect_action_receive_config),
                    accent = idleAccent,
                    active = false,
                )
                currentVpnAction = VpnAction.IMPORT
            }
            VpnState.Down -> {
                vpnColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.vpn_status_disconnected),
                    detail = context.getString(R.string.vpn_detail_disconnected),
                    actionLabel = context.getString(R.string.connect_action_connect),
                    accent = idleAccent,
                    active = false,
                )
                currentVpnAction = VpnAction.CONNECT
            }
            VpnState.Connecting -> {
                vpnColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.vpn_status_connecting),
                    detail = context.getString(R.string.vpn_detail_connecting),
                    actionLabel = context.getString(R.string.connect_action_disconnect),
                    accent = pendingAccent,
                    active = true,
                    actionEnabled = false,
                )
                currentVpnAction = VpnAction.DISCONNECT
            }
            is VpnState.Up -> {
                vpnColumn.setState(
                    eyebrowText = eyebrowText,
                    title = state.peerEndpoint.ifBlank {
                        context.getString(R.string.vpn_status_connected)
                    },
                    detail = context.getString(
                        R.string.connect_vpn_detail_connected,
                        state.peerEndpoint
                    ),
                    actionLabel = context.getString(R.string.connect_action_disconnect),
                    accent = activeColor,
                    active = true,
                    filled = false,
                )
                currentVpnAction = VpnAction.DISCONNECT
            }
            is VpnState.Error -> {
                vpnColumn.setState(
                    eyebrowText = eyebrowText,
                    title = context.getString(R.string.vpn_status_error),
                    detail = state.message,
                    actionLabel = context.getString(R.string.connect_action_retry),
                    accent = errAccent,
                    active = false,
                )
                currentVpnAction = VpnAction.CONNECT
            }
        }
    }
}
