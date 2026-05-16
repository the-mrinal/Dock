package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Controls when the dashboard transitions into ambient mode (calendar / now
 * playing widgets fade out, clock starts drifting). A value of 0 disables
 * ambient mode entirely so the full dashboard stays on screen until the
 * sleep-timer watchdog terminates the app.
 */
object AmbientPreferences {
    const val KEY_AMBIENT_DELAY_MS = "ambient_delay_ms"

    fun getAmbientDelayMs(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(
            KEY_AMBIENT_DELAY_MS,
            context.getString(R.string.ambient_default_value)
        ) ?: context.getString(R.string.ambient_default_value)
        return stored.toLongOrNull() ?: DEFAULT_AMBIENT_DELAY_MS
    }

    fun isAmbientEnabled(context: Context): Boolean =
        getAmbientDelayMs(context) > 0L

    private const val DEFAULT_AMBIENT_DELAY_MS = 90_000L
}
