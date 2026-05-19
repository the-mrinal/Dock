package com.ambient.tvclock.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ambient.tvclock.R
import com.ambient.tvclock.vpn.ConfigImportActivity
import com.ambient.tvclock.vpn.VpnOverlayService
import com.ambient.tvclock.vpn.VpnPreferences
import com.ambient.tvclock.vpn.VpnState
import com.ambient.tvclock.vpn.WireGuardConfigStore
import com.ambient.tvclock.vpn.WireGuardController
import com.ambient.tvclock.vpn.WireGuardStateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * VPN page. The service layer (WireGuardController, state bus, kill-switch
 * intent) is untouched — only the UI surface moves to PrefRow.
 *
 * State-row updates piggyback on `WireGuardStateBus.state` so a tunnel toggling
 * up while this fragment is visible refreshes the row in real time.
 */
class VpnSettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_vpn) {

    private lateinit var vpnConsentLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    private lateinit var enableRow: PrefRow
    private lateinit var statusRow: PrefRow
    private lateinit var clearRow: PrefDangerRow

    private var vpnStateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val ctx = appCtx()
            if (result.resultCode == Activity.RESULT_OK) {
                VpnPreferences.setEnabled(ctx, true)
                enableRow.setToggleState(true)
                WireGuardController.start(ctx)
            } else {
                Toast.makeText(ctx, R.string.vpn_not_authorized, Toast.LENGTH_LONG).show()
                enableRow.setToggleState(false)
            }
            updateStatus()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        enableRow = view.findViewById(R.id.prefVpnEnable)
        enableRow.label = getString(R.string.settings_vpn_enable)
        enableRow.setToggle(VpnPreferences.isEnabled(ctx)) { newValue ->
            val app = appCtx()
            if (newValue) {
                if (!VpnPreferences.hasConfig(app)) {
                    Toast.makeText(app, R.string.pref_vpn_status_no_config, Toast.LENGTH_LONG).show()
                    enableRow.setToggleState(false)
                    return@setToggle
                }
                val consent = VpnService.prepare(app)
                if (consent != null) {
                    // Roll the toggle back; flip it on once consent is granted.
                    enableRow.setToggleState(false)
                    vpnConsentLauncher.launch(consent)
                } else {
                    VpnPreferences.setEnabled(app, true)
                    WireGuardController.start(app)
                }
            } else {
                VpnPreferences.setEnabled(app, false)
                WireGuardController.stop(app)
            }
            updateStatus()
        }

        statusRow = view.findViewById(R.id.prefVpnStatus)
        statusRow.label = getString(R.string.settings_vpn_status)
        statusRow.isFocusable = false

        val receiveRow = view.findViewById<PrefRow>(R.id.prefVpnReceive)
        receiveRow.label = getString(R.string.settings_vpn_receive)
        receiveRow.setHint(getString(R.string.settings_vpn_receive_hint))
        receiveRow.setValue("↵")
        receiveRow.setOnClickListener {
            startActivity(Intent(ctx, ConfigImportActivity::class.java))
        }

        clearRow = view.findViewById(R.id.prefVpnClear)
        clearRow.label = getString(R.string.settings_vpn_clear)
        clearRow.setHint(getString(R.string.settings_vpn_clear_hint))
        clearRow.setValue("↵")
        clearRow.setOnClickListener {
            val app = appCtx()
            WireGuardController.stop(app)
            WireGuardConfigStore(app).clear()
            VpnPreferences.setEnabled(app, false)
            enableRow.setToggleState(false)
            updateStatus()
            Toast.makeText(app, R.string.pref_vpn_status_no_config, Toast.LENGTH_SHORT).show()
        }

        val killRow = view.findViewById<PrefRow>(R.id.prefVpnKillswitch)
        killRow.label = getString(R.string.settings_vpn_killswitch)
        killRow.setHint(getString(R.string.settings_vpn_killswitch_hint))
        killRow.setValue("›")
        killRow.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        val overlayRow = view.findViewById<PrefRow>(R.id.prefVpnOverlay)
        overlayRow.label = getString(R.string.settings_vpn_overlay)
        overlayRow.setToggle(VpnPreferences.isOverlayEnabled(ctx)) { newValue ->
            val app = appCtx()
            if (newValue) {
                if (!Settings.canDrawOverlays(app)) {
                    Toast.makeText(app, R.string.vpn_overlay_needs_permission, Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${app.packageName}"),
                        )
                    )
                    overlayRow.setToggleState(false)
                    return@setToggle
                }
                prefs().edit().putBoolean(VpnPreferences.KEY_OVERLAY_ENABLED, true).apply()
                if (WireGuardStateBus.state.value is VpnState.Up) {
                    VpnOverlayService.start(app)
                }
            } else {
                prefs().edit().putBoolean(VpnPreferences.KEY_OVERLAY_ENABLED, false).apply()
                VpnOverlayService.stop(app)
            }
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        vpnStateJob = viewLifecycleOwner.lifecycleScope.launch {
            WireGuardStateBus.state.collect { updateStatus() }
        }
    }

    override fun onPause() {
        vpnStateJob?.cancel()
        vpnStateJob = null
        super.onPause()
    }

    private fun updateStatus() {
        val ctx = appCtx()
        val hasConfig = VpnPreferences.hasConfig(ctx)
        statusRow.setValue(
            when {
                !hasConfig -> getString(R.string.pref_vpn_status_no_config)
                !VpnPreferences.isEnabled(ctx) -> getString(R.string.pref_vpn_status_down)
                else -> when (val s = WireGuardStateBus.state.value) {
                    VpnState.Down, VpnState.NoConfig -> getString(R.string.pref_vpn_status_down)
                    VpnState.Connecting -> getString(R.string.pref_vpn_status_connecting)
                    is VpnState.Up -> getString(R.string.pref_vpn_status_up, s.peerEndpoint.ifBlank { "—" })
                    is VpnState.Error -> getString(R.string.pref_vpn_status_error, s.message)
                }
            }
        )
        // Reflect config presence on the rows that need it.
        enableRow.isEnabled = hasConfig
        enableRow.alpha = if (hasConfig) 1f else 0.5f
        clearRow.isEnabled = hasConfig
        clearRow.alpha = if (hasConfig) 1f else 0.5f
    }
}
