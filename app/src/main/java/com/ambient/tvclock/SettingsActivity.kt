package com.ambient.tvclock

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var spotifyAuthLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

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
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            updateNotificationAccessSummary()
            updateSpotifyStatus()

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
        }

        override fun onResume() {
            super.onResume()
            updateNotificationAccessSummary()
            updateSpotifyStatus()
            NotificationAccess.requestListenerReconnect(requireContext())
            NowPlayingSessionReader.publish(requireContext())
            CalendarPoller(requireContext()).publishNow()
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
    }
}
