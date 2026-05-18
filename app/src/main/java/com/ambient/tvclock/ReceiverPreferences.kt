package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager
import com.ambient.tvclock.receiver.settings.AppSettings

/**
 * Reads the dock's receiver-subsystem settings out of the shared SharedPreferences
 * store. Sits next to AmbientPreferences/CalendarPreferences so the receiver code
 * doesn't need its own DataStore.
 *
 * Master toggle defaults off — service never auto-starts (plan decision #3).
 */
object ReceiverPreferences {
    const val KEY_RECEIVER_ENABLED = "receiver_enabled"
    const val KEY_AIRPLAY_ENABLED = "receiver_airplay_enabled"
    const val KEY_CAST_ENABLED = "receiver_cast_enabled"
    const val KEY_MIRACAST_ENABLED = "receiver_miracast_enabled"
    const val KEY_AIRPLAY_PIN = "receiver_airplay_pin"
    const val KEY_START_ON_BOOT = "receiver_start_on_boot"
    const val KEY_DEVICE_NAME = "receiver_device_name"

    fun isReceiverEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_RECEIVER_ENABLED, false)

    fun isStartOnBootEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_START_ON_BOOT, false)

    /**
     * Builds an [AppSettings] snapshot from current preference values. Defaults
     * mirror the data class defaults except the protocol toggles, which default
     * to true (so flipping the master toggle on without further configuration
     * advertises all three protocols).
     */
    fun read(context: Context): AppSettings {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return AppSettings(
            displayName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty(),
            airPlayEnabled = prefs.getBoolean(KEY_AIRPLAY_ENABLED, true),
            miracastEnabled = prefs.getBoolean(KEY_MIRACAST_ENABLED, true),
            castEnabled = prefs.getBoolean(KEY_CAST_ENABLED, true),
            airPlayPinAuthEnabled = prefs.getBoolean(KEY_AIRPLAY_PIN, false),
            startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false),
            showDebugOverlay = false,
        )
    }
}
