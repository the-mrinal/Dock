package com.ambient.tvclock.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.ambient.tvclock.NotificationAccess
import com.ambient.tvclock.NowPlayingPreferences
import com.ambient.tvclock.NowPlayingSessionReader
import com.ambient.tvclock.R
import com.ambient.tvclock.SpotifyApiClient
import com.ambient.tvclock.SpotifyAuthActivity
import com.ambient.tvclock.SpotifyQueueCenter
import com.ambient.tvclock.SpotifyQueuePoller
import com.ambient.tvclock.SpotifyQueueSnapshot
import com.ambient.tvclock.SpotifyQueueState
import com.ambient.tvclock.SpotifyTokenStore

/**
 * Music sources fragment. Notification access + Spotify connection — same
 * surfaces the old PreferenceFragment exposed.
 *
 * Status rows are non-focusable so the D-pad skips past them.
 */
class MusicSettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_music) {

    private lateinit var spotifyAuthLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    private lateinit var accessRow: PrefRow
    private lateinit var spotifyStatusRow: PrefRow

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // Show music widget on Home.
        val showRow = view.findViewById<PrefRow>(R.id.prefMusicShow)
        showRow.label = getString(R.string.settings_music_show_label)
        showRow.setToggle(NowPlayingPreferences.isEnabled(ctx)) { newValue ->
            prefs().edit().putBoolean(NowPlayingPreferences.KEY_SHOW_NOW_PLAYING, newValue).apply()
            NowPlayingSessionReader.publish(ctx)
        }

        // Notification access status — non-focusable display row.
        accessRow = view.findViewById(R.id.prefMusicAccess)
        accessRow.label = getString(R.string.settings_music_access_label)
        accessRow.isFocusable = false
        updateAccessRow()

        // Open notification access settings.
        val openAccess = view.findViewById<PrefRow>(R.id.prefMusicOpenAccess)
        openAccess.label = getString(R.string.settings_music_open_access_label)
        openAccess.setHint(getString(R.string.settings_music_open_access_hint))
        openAccess.setValue("›")
        openAccess.setOnClickListener {
            if (!NotificationAccess.openListenerSettings(ctx)) {
                Toast.makeText(
                    ctx,
                    NotificationAccess.adbGrantCommands(ctx),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // Spotify connect.
        val connectRow = view.findViewById<PrefRow>(R.id.prefMusicSpotifyConnect)
        connectRow.label = getString(R.string.settings_music_spotify_connect)
        connectRow.setValue("›")
        connectRow.setOnClickListener {
            if (!SpotifyApiClient.hasClientId()) {
                Toast.makeText(ctx, R.string.spotify_no_client_id, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            spotifyAuthLauncher.launch(SpotifyAuthActivity.intent(requireActivity()))
        }

        // Spotify disconnect.
        val disconnectRow = view.findViewById<PrefRow>(R.id.prefMusicSpotifyDisconnect)
        disconnectRow.label = getString(R.string.settings_music_spotify_disconnect)
        disconnectRow.setValue("›")
        disconnectRow.setOnClickListener {
            SpotifyTokenStore.clear(ctx)
            SpotifyQueueCenter.update(SpotifyQueueSnapshot(state = SpotifyQueueState.NOT_LINKED))
            Toast.makeText(ctx, R.string.spotify_disconnected_toast, Toast.LENGTH_SHORT).show()
            updateSpotifyStatus()
        }

        // Spotify status row — non-focusable.
        spotifyStatusRow = view.findViewById(R.id.prefMusicSpotifyStatus)
        spotifyStatusRow.label = getString(R.string.settings_music_spotify_status)
        spotifyStatusRow.isFocusable = false
        updateSpotifyStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessRow()
        updateSpotifyStatus()
        NotificationAccess.requestListenerReconnect(requireContext())
        NowPlayingSessionReader.publish(requireContext())
    }

    private fun updateAccessRow() {
        val ctx = requireContext()
        accessRow.setValue(
            if (NotificationAccess.isListenerEnabled(ctx)) {
                getString(R.string.notification_access_granted)
            } else {
                getString(R.string.notification_access_required)
            }
        )
    }

    private fun updateSpotifyStatus() {
        val ctx = requireContext()
        spotifyStatusRow.setValue(
            when {
                !SpotifyApiClient.hasClientId() -> getString(R.string.spotify_no_client_id)
                SpotifyTokenStore.isConnected(ctx) -> getString(R.string.spotify_connected)
                else -> getString(R.string.spotify_not_connected)
            }
        )
    }
}
