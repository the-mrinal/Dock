package com.ambient.tvclock

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            updateNotificationAccessSummary()

            findPreference<SwitchPreferenceCompat>(NowPlayingPreferences.KEY_SHOW_NOW_PLAYING)
                ?.setOnPreferenceChangeListener { _, _ ->
                    NowPlayingSessionReader.publish(requireContext())
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
        }

        override fun onResume() {
            super.onResume()
            updateNotificationAccessSummary()
            NotificationAccess.requestListenerReconnect(requireContext())
            NowPlayingSessionReader.publish(requireContext())
        }

        private fun updateNotificationAccessSummary() {
            val pref = findPreference<Preference>("grant_notification_access") ?: return
            pref.summary = if (NotificationAccess.isListenerEnabled(requireContext())) {
                getString(R.string.notification_access_granted)
            } else {
                getString(R.string.notification_access_required)
            }
        }
    }
}
