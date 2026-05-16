package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object TimeoutPreferences {
    const val KEY_INACTIVITY_TIMEOUT_MS = "inactivity_timeout_ms"

    fun getInactivityTimeoutMs(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(
            KEY_INACTIVITY_TIMEOUT_MS,
            context.getString(R.string.timeout_default_value)
        ) ?: context.getString(R.string.timeout_default_value)
        return stored.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
    }

    fun isWatchdogEnabled(context: Context): Boolean =
        getInactivityTimeoutMs(context) > 0L

    private const val DEFAULT_TIMEOUT_MS = 3 * 60 * 60 * 1000L
}
