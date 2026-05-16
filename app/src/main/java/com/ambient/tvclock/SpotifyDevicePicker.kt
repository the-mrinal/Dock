package com.ambient.tvclock

import android.app.Activity
import android.app.AlertDialog
import kotlin.concurrent.thread

object SpotifyDevicePicker {

    fun show(activity: Activity, onTransferred: () -> Unit) {
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
            activity.runOnUiThread {
                if (devices.isEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.spotify_pick_device)
                        .setMessage(R.string.spotify_no_devices)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                val names = devices.map { device ->
                    val active = if (device.isActive) " ✓" else ""
                    "${device.name}$active"
                }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(R.string.spotify_pick_device)
                    .setItems(names) { _, which ->
                        val device = devices[which]
                        thread {
                            SpotifyPlaybackControl.transferToDevice(activity, device.id)
                            activity.runOnUiThread { onTransferred() }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
