package com.ambient.tvclock.receiver.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import com.ambient.tvclock.receiver.ProtocolState
import com.ambient.tvclock.util.Logger

/**
 * MiracastReceiver — Miracast (Wi-Fi Display / WFD) receiver stub.
 *
 * WHY: Miracast allows Windows 10+ and Android devices to wirelessly mirror
 * their screen without being on the same Wi-Fi network. It uses Wi-Fi Direct
 * (P2P) to create a direct device-to-device connection.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1 (this stub): Architecture defined, P2P manager initialized
 * - Phase 2 (M2): Wi-Fi P2P service discovery advertised
 * - Phase 3 (M3): WFD RTSP session negotiation
 * - Phase 4 (M6): H.264 video decode + audio playback
 *
 * Miracast protocol stack:
 *   Wi-Fi Direct (P2P) → WFD RTSP → RTP/H.264 → MediaCodec → SurfaceView
 *
 * Key Android APIs used:
 *   - [WifiP2pManager]: for discovering peers and accepting connections
 *   - [WifiP2pManager.Channel]: communication channel to the P2P framework
 *   - Custom WFD RTSP: similar to AirPlay RTSP but with WFD-specific methods
 *
 * IMPORTANT LIMITATIONS (see ADR-001):
 * - Miracast requires Wi-Fi Direct, which some Android TV devices disable
 * - The WFD stack on Android TV is partly hidden (system APIs)
 * - Real-world compatibility must be tested on actual hardware
 * - Miracast is NOT available on Fire TV with standard APIs
 *
 * Example (future usage):
 *   val receiver = MiracastReceiver(context) { state -> updateUI(state) }
 *   receiver.start()  // begins P2P service advertisement
 *   receiver.stop()   // stops advertisement and closes session
 */
class MiracastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // Android's Wi-Fi P2P manager — the entry point for all Wi-Fi Direct operations
    private var wifiP2pManager: WifiP2pManager? = null

    // The communication channel between the app and the Wi-Fi P2P framework
    private var channel: Channel? = null

    // Whether the P2P service advertisement is currently active
    @Volatile
    private var isAdvertising = false

    /**
     * Starts the Miracast receiver.
     *
     * Current implementation (Phase 1 stub):
     * - Initializes the WifiP2pManager and Channel
     * - Logs availability of Wi-Fi Direct on this device
     * - TODO (Phase 2): Register P2P service discovery records
     * - TODO (Phase 3): Accept incoming WFD RTSP connections
     */
    fun start() {
        Logger.i("MiracastReceiver starting")
        initializeWifiP2p()
    }

    /**
     * Stops the Miracast receiver.
     *
     * Unregisters P2P service, disconnects any active WFD session,
     * and releases the WifiP2pManager channel.
     */
    // Channel.close() requires API 27; firetv (minSdk 25) needs this guard, but lint
    // analyzing the googletv flavor (minSdk 29) flags the check as obsolete.
    @android.annotation.SuppressLint("ObsoleteSdkInt")
    fun stop() {
        Logger.i("MiracastReceiver stopping")
        try {
            stopP2pAdvertisement()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                channel?.close()
            }
        } catch (e: Exception) {
            Logger.e("Error stopping MiracastReceiver (non-fatal)", e)
        } finally {
            wifiP2pManager = null
            channel = null
            isAdvertising = false
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /**
     * Initializes the WifiP2pManager and Channel.
     *
     * The [WifiP2pManager] is retrieved from Android's system services.
     * The [Channel] is the app's communication link to the P2P framework.
     *
     * If Wi-Fi Direct is not available on this device (some Android TV boxes
     * don't support it), [wifiP2pManager] will be null and we emit an ERROR state.
     */
    private fun initializeWifiP2p() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Logger.w("WifiP2pManager not available on this device — Miracast not supported")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        // Initialize the channel: connects the app to the Wi-Fi P2P framework
        // The looper parameter specifies which thread receives P2P framework callbacks
        channel = wifiP2pManager!!.initialize(
            context,
            context.mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // P2P framework disconnected — this can happen if Wi-Fi is turned off
                    Logger.w("WifiP2p channel disconnected")
                    onStateChanged(ProtocolState.ERROR)
                }
            }
        )

        Logger.i("WifiP2pManager initialized — registering P2P service")
        // TODO Phase 2: registerP2pService()
        onStateChanged(ProtocolState.ADVERTISING)
        isAdvertising = true
    }

    /**
     * Stops the P2P service advertisement.
     * TODO Phase 2: implement removeLocalService() call
     */
    private fun stopP2pAdvertisement() {
        if (!isAdvertising) return
        // TODO Phase 2: wifiP2pManager?.removeLocalService(channel, serviceInfo, listener)
        isAdvertising = false
        Logger.d("P2P service advertisement stopped")
    }

    /**
     * Handles an incoming Miracast WFD RTSP connection.
     *
     * The WFD RTSP handshake is similar to AirPlay RTSP but uses different
     * methods (M1-M7 WFD methods). This method will be implemented in Phase 3.
     *
     * WFD RTSP method sequence:
     *   M1: OPTIONS (capability exchange)
     *   M2: GET_PARAMETER (sink capabilities)
     *   M3: SET_PARAMETER (source capabilities)
     *   M4: SETUP (establish RTP session)
     *   M5: PLAY (start streaming)
     *   M6: PAUSE (pause streaming)
     *   M7: TEARDOWN (stop session)
     *
     * TODO Phase 3: implement WFD RTSP handler
     */
    @Suppress("unused")
    private fun handleWfdSession() {
        // TODO Phase 3: implement WFD RTSP session handling
        // Reference: Wi-Fi Display Specification v2.1, Section 6
        Logger.d("WFD session handling — TODO Phase 3")
    }
}
