package com.ambient.tvclock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlin.concurrent.thread

object SpotifyDevicePicker {

    // Package names of Spotify clients that can act as Spotify Connect playback
    // endpoints when launched on the host device. The dedicated TV app is the
    // expected Fire TV / Google TV install; the phone/tablet app shows up as
    // a fallback on some sideloaded configurations.
    private val SPOTIFY_PACKAGES = listOf(
        "com.spotify.tv.android",
        "com.spotify.music"
    )

    private sealed class Entry {
        class LaunchLocal(val packageName: String) : Entry()
        class Device(val device: SpotifyDevice) : Entry()
    }

    /**
     * Shows the device picker. The caller decides what to do with the
     * selected device id via [onDeviceSelected] — the cast button wants to
     * transfer current playback, a 404-fallback wants to (re-)play a specific
     * track on the chosen device.
     */
    fun show(activity: Activity, onDeviceSelected: (deviceId: String) -> Unit) {
        if (!SpotifyTokenStore.isConnected(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.spotify_pick_device)
                .setMessage(R.string.spotify_queue_not_linked)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        thread(name = "spotify-devices") {
            val devices = SpotifyApiClient.fetchDevices(activity)
            val localSpotifyPackage = findInstalledSpotifyApp(activity)
            activity.runOnUiThread {
                showPicker(activity, devices, localSpotifyPackage, onDeviceSelected)
            }
        }
    }

    private fun showPicker(
        activity: Activity,
        devices: List<SpotifyDevice>,
        localSpotifyPackage: String?,
        onDeviceSelected: (deviceId: String) -> Unit
    ) {
        if (devices.isEmpty() && localSpotifyPackage == null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.spotify_pick_device)
                .setMessage(R.string.spotify_no_devices)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val entries = buildList<Entry> {
            // Put the launch action first — when no Connect devices exist
            // anywhere, it's the only way the user can play anything on this
            // TV, so it deserves the top of the focus list.
            if (localSpotifyPackage != null) {
                add(Entry.LaunchLocal(localSpotifyPackage))
            }
            devices.forEach { add(Entry.Device(it)) }
        }

        val labels = entries.map { entry ->
            when (entry) {
                is Entry.LaunchLocal -> activity.getString(R.string.spotify_launch_on_this_tv)
                is Entry.Device -> {
                    val active = if (entry.device.isActive) " ✓" else ""
                    "${entry.device.name}$active"
                }
            }
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(R.string.spotify_pick_device)
            .setItems(labels) { _, which ->
                when (val entry = entries[which]) {
                    is Entry.LaunchLocal -> {
                        launchSpotifyApp(activity, entry.packageName)
                        Toast.makeText(
                            activity,
                            R.string.spotify_launching_hint,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is Entry.Device -> {
                        onDeviceSelected(entry.device.id)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun findInstalledSpotifyApp(context: Context): String? {
        val pm = context.packageManager
        return SPOTIFY_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun launchSpotifyApp(activity: Activity, packageName: String) {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
