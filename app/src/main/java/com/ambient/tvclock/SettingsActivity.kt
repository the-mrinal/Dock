package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.ambient.tvclock.receiver.ReceiverController
import androidx.lifecycle.lifecycleScope
import com.ambient.tvclock.vpn.ConfigImportActivity
import com.ambient.tvclock.vpn.VpnOverlayService
import com.ambient.tvclock.vpn.VpnPreferences
import com.ambient.tvclock.vpn.VpnState
import com.ambient.tvclock.vpn.WireGuardConfigStore
import com.ambient.tvclock.vpn.WireGuardController
import com.ambient.tvclock.vpn.WireGuardStateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnboardingPreferences.markSettingsVisited(this)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var spotifyAuthLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
        private lateinit var vpnConsentLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
        private var vpnStateJob: Job? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            spotifyAuthLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                updateSpotifyStatus()
                if (result.resultCode == Activity.RESULT_OK) {
                    Toast.makeText(requireContext(), R.string.spotify_connected, Toast.LENGTH_SHORT).show()
                    SpotifyQueuePoller(requireContext().applicationContext).publishNow()
                }
            }
            vpnConsentLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val ctx = requireContext().applicationContext
                if (result.resultCode == Activity.RESULT_OK) {
                    VpnPreferences.setEnabled(ctx, true)
                    findPreference<SwitchPreferenceCompat>(VpnPreferences.KEY_VPN_ENABLED)?.isChecked = true
                    WireGuardController.start(ctx)
                } else {
                    Toast.makeText(ctx, R.string.vpn_not_authorized, Toast.LENGTH_LONG).show()
                }
                updateVpnStatus()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            updateNotificationAccessSummary()

            findPreference<Preference>("remote_setup")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SetupActivity::class.java))
                true
            }
            updateSpotifyStatus()
            updateUnsplashKeyStatus()

            findPreference<SwitchPreferenceCompat>(NowPlayingPreferences.KEY_SHOW_NOW_PLAYING)
                ?.setOnPreferenceChangeListener { _, _ ->
                    NowPlayingSessionReader.publish(requireContext())
                    true
                }

            findPreference<SwitchPreferenceCompat>(CalendarPreferences.KEY_SHOW_CALENDAR)
                ?.setOnPreferenceChangeListener { _, _ ->
                    CalendarPoller(requireContext()).publishNow()
                    true
                }

            findPreference<EditTextPreference>(CalendarPreferences.KEY_PERSONAL_URL)
                ?.setOnPreferenceChangeListener { _, _ ->
                    CalendarPoller(requireContext()).publishNow()
                    true
                }

            findPreference<EditTextPreference>(CalendarPreferences.KEY_WORK_URL)
                ?.setOnPreferenceChangeListener { _, _ ->
                    CalendarPoller(requireContext()).publishNow()
                    true
                }

            findPreference<Preference>("grant_notification_access")?.setOnPreferenceClickListener {
                val context = requireContext()
                if (NotificationAccess.openListenerSettings(context)) {
                    true
                } else {
                    Toast.makeText(
                        context,
                        NotificationAccess.adbGrantCommands(context),
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
            }

            findPreference<Preference>("spotify_connect")?.setOnPreferenceClickListener {
                val context = requireContext()
                if (!SpotifyApiClient.hasClientId()) {
                    Toast.makeText(context, R.string.spotify_no_client_id, Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }
                spotifyAuthLauncher.launch(SpotifyAuthActivity.intent(requireActivity()))
                true
            }

            findPreference<Preference>("spotify_disconnect")?.setOnPreferenceClickListener {
                SpotifyTokenStore.clear(requireContext())
                SpotifyQueueCenter.update(SpotifyQueueSnapshot(state = SpotifyQueueState.NOT_LINKED))
                Toast.makeText(requireContext(), R.string.spotify_disconnected_toast, Toast.LENGTH_SHORT)
                    .show()
                updateSpotifyStatus()
                true
            }

            wireReceiverPreferences()
            wireVpnPreferences()
            wireBackgroundKeywordLock()
            wireShuffleNow()
        }

        private fun wireShuffleNow() {
            findPreference<Preference>("background_shuffle_now")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                if (!BackgroundPreferences.isUnsplashConfigured(ctx)) {
                    Toast.makeText(
                        ctx,
                        R.string.background_shuffle_toast_no_unsplash,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    BackgroundPreferences.pulseShuffleSignal(ctx)
                }
                true
            }
        }

        private fun wireBackgroundKeywordLock() {
            val presets = findPreference<MultiSelectListPreference>(BackgroundPreferences.KEY_KEYWORD_PRESETS)
            val custom = findPreference<EditTextPreference>(BackgroundPreferences.KEY_CUSTOM_KEYWORDS)

            val gate = Preference.OnPreferenceChangeListener { _, _ ->
                val ctx = requireContext()
                val remaining = BackgroundPreferences.keywordsLockRemainingMs(ctx)
                if (remaining > 0L) {
                    Toast.makeText(
                        ctx,
                        getString(R.string.background_keywords_locked_toast, formatRemaining(remaining)),
                        Toast.LENGTH_LONG,
                    ).show()
                    false // reject the change; preference value is rolled back
                } else {
                    BackgroundPreferences.markKeywordsChanged(ctx)
                    // Refresh both summaries so the sibling preference also locks.
                    refreshKeywordLockSummaries()
                    true
                }
            }
            presets?.onPreferenceChangeListener = gate
            custom?.onPreferenceChangeListener = gate
            refreshKeywordLockSummaries()
        }

        private fun refreshKeywordLockSummaries() {
            val ctx = requireContext()
            val remaining = BackgroundPreferences.keywordsLockRemainingMs(ctx)
            val presets = findPreference<MultiSelectListPreference>(BackgroundPreferences.KEY_KEYWORD_PRESETS)
            val custom = findPreference<EditTextPreference>(BackgroundPreferences.KEY_CUSTOM_KEYWORDS)
            if (remaining > 0L) {
                val text = getString(R.string.background_keywords_locked_summary, formatRemaining(remaining))
                presets?.isEnabled = false
                custom?.isEnabled = false
                presets?.summary = text
                custom?.summary = text
            } else {
                presets?.isEnabled = true
                custom?.isEnabled = true
                // Restore the default summary providers' output.
                presets?.summary = getString(R.string.pref_background_keyword_presets_summary)
                custom?.summary = custom?.text?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.pref_background_custom_keywords_summary)
            }
        }

        private fun formatRemaining(ms: Long): String {
            val totalMinutes = (ms + 59_999L) / 60_000L
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 -> getString(R.string.background_keywords_unit_hours, hours.toInt(), minutes.toInt())
                minutes > 0 -> getString(R.string.background_keywords_unit_minutes, minutes.toInt())
                else -> getString(R.string.background_keywords_unit_seconds)
            }
        }

        private fun wireVpnPreferences() {
            findPreference<SwitchPreferenceCompat>(VpnPreferences.KEY_VPN_ENABLED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val ctx = requireContext().applicationContext
                    if (newValue == true) {
                        if (!VpnPreferences.hasConfig(ctx)) {
                            Toast.makeText(ctx, R.string.pref_vpn_status_no_config, Toast.LENGTH_LONG).show()
                            return@setOnPreferenceChangeListener false
                        }
                        val consent = VpnService.prepare(ctx)
                        if (consent != null) {
                            vpnConsentLauncher.launch(consent)
                            false // wait for the launcher callback to flip the toggle
                        } else {
                            VpnPreferences.setEnabled(ctx, true)
                            WireGuardController.start(ctx)
                            true
                        }
                    } else {
                        VpnPreferences.setEnabled(ctx, false)
                        WireGuardController.stop(ctx)
                        true
                    }
                }

            findPreference<Preference>("vpn_receive_config")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ConfigImportActivity::class.java))
                true
            }

            findPreference<Preference>("vpn_clear_config")?.setOnPreferenceClickListener {
                val ctx = requireContext().applicationContext
                WireGuardController.stop(ctx)
                WireGuardConfigStore(ctx).clear()
                VpnPreferences.setEnabled(ctx, false)
                findPreference<SwitchPreferenceCompat>(VpnPreferences.KEY_VPN_ENABLED)?.isChecked = false
                updateVpnStatus()
                Toast.makeText(ctx, R.string.pref_vpn_status_no_config, Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<Preference>("vpn_killswitch")?.setOnPreferenceClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                true
            }

            findPreference<SwitchPreferenceCompat>(VpnPreferences.KEY_OVERLAY_ENABLED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val ctx = requireContext().applicationContext
                    if (newValue == true) {
                        if (!android.provider.Settings.canDrawOverlays(ctx)) {
                            Toast.makeText(ctx, R.string.vpn_overlay_needs_permission, Toast.LENGTH_LONG).show()
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                            return@setOnPreferenceChangeListener false
                        }
                        if (WireGuardStateBus.state.value is VpnState.Up) {
                            VpnOverlayService.start(ctx)
                        }
                    } else {
                        VpnOverlayService.stop(ctx)
                    }
                    true
                }
        }

        private fun updateVpnStatus() {
            val pref = findPreference<Preference>("vpn_status") ?: return
            val ctx = requireContext().applicationContext
            val hasConfig = VpnPreferences.hasConfig(ctx)
            pref.summary = when {
                !hasConfig -> getString(R.string.pref_vpn_status_no_config)
                !VpnPreferences.isEnabled(ctx) -> getString(R.string.pref_vpn_status_down)
                else -> when (val s = WireGuardStateBus.state.value) {
                    VpnState.Down, VpnState.NoConfig -> getString(R.string.pref_vpn_status_down)
                    VpnState.Connecting -> getString(R.string.pref_vpn_status_connecting)
                    is VpnState.Up -> getString(R.string.pref_vpn_status_up, s.peerEndpoint.ifBlank { "—" })
                    is VpnState.Error -> getString(R.string.pref_vpn_status_error, s.message)
                }
            }
            // Gate Enable VPN + Clear config rows on whether we have a config.
            findPreference<SwitchPreferenceCompat>(VpnPreferences.KEY_VPN_ENABLED)?.isEnabled = hasConfig
            findPreference<Preference>("vpn_clear_config")?.isEnabled = hasConfig
        }

        private fun wireReceiverPreferences() {
            findPreference<SwitchPreferenceCompat>(ReceiverPreferences.KEY_RECEIVER_ENABLED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val ctx = requireContext().applicationContext
                    if (newValue == true) ReceiverController.start(ctx)
                    else ReceiverController.stop(ctx)
                    true
                }

            val restartOnChange = Preference.OnPreferenceChangeListener { _, _ ->
                val ctx = requireContext().applicationContext
                if (ReceiverPreferences.isReceiverEnabled(ctx)) {
                    ReceiverController.restart(ctx)
                }
                true
            }
            for (key in arrayOf(
                ReceiverPreferences.KEY_AIRPLAY_ENABLED,
                ReceiverPreferences.KEY_CAST_ENABLED,
                ReceiverPreferences.KEY_MIRACAST_ENABLED,
                ReceiverPreferences.KEY_AIRPLAY_PIN,
                ReceiverPreferences.KEY_DEVICE_NAME,
            )) {
                findPreference<Preference>(key)?.onPreferenceChangeListener = restartOnChange
            }
        }

        override fun onResume() {
            super.onResume()
            updateNotificationAccessSummary()
            updateSpotifyStatus()
            updateUnsplashKeyStatus()
            refreshKeywordLockSummaries()
            updateVpnStatus()
            NotificationAccess.requestListenerReconnect(requireContext())
            NowPlayingSessionReader.publish(requireContext())
            CalendarPoller(requireContext()).publishNow()
            vpnStateJob = viewLifecycleOwner.lifecycleScope.launch {
                WireGuardStateBus.state.collect { updateVpnStatus() }
            }
        }

        override fun onPause() {
            vpnStateJob?.cancel()
            vpnStateJob = null
            super.onPause()
        }

        private fun updateNotificationAccessSummary() {
            val pref = findPreference<Preference>("grant_notification_access") ?: return
            pref.summary = if (NotificationAccess.isListenerEnabled(requireContext())) {
                getString(R.string.notification_access_granted)
            } else {
                getString(R.string.notification_access_required)
            }
        }

        private fun updateSpotifyStatus() {
            val pref = findPreference<Preference>("spotify_status") ?: return
            pref.summary = when {
                !SpotifyApiClient.hasClientId() -> getString(R.string.spotify_no_client_id)
                SpotifyTokenStore.isConnected(requireContext()) -> getString(R.string.spotify_connected)
                else -> getString(R.string.spotify_not_connected)
            }
        }

        private fun updateUnsplashKeyStatus() {
            val pref = findPreference<Preference>("background_unsplash_key_status") ?: return
            pref.summary = if (UnsplashClient.hasAccessKey()) {
                getString(R.string.pref_background_unsplash_key_ready)
            } else {
                getString(R.string.pref_background_unsplash_no_key)
            }
        }
    }
}
