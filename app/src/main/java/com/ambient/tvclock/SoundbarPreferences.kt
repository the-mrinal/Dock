package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Settings for the soundbar keep-alive tone. Off by default — the app never
 * emits audio, however faint, unless the user opts in. The interval picker is
 * a ListPreference storing milliseconds as a string, like the other timers.
 */
object SoundbarPreferences {
    const val KEY_KEEPALIVE_ENABLED = "soundbar_keepalive_enabled"
    const val KEY_KEEPALIVE_INTERVAL_MS = "soundbar_keepalive_interval_ms"

    fun isKeepAliveEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false) &&
            getKeepAliveIntervalMs(context) > 0L
    }

    fun getKeepAliveIntervalMs(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(
            KEY_KEEPALIVE_INTERVAL_MS,
            context.getString(R.string.soundbar_keepalive_default_value)
        ) ?: context.getString(R.string.soundbar_keepalive_default_value)
        return stored.toLongOrNull() ?: DEFAULT_KEEPALIVE_INTERVAL_MS
    }

    private const val DEFAULT_KEEPALIVE_INTERVAL_MS = 300_000L
}
