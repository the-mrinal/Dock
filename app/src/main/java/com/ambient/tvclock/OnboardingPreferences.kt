package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Backing storage + visibility rules for the one-time "enable phone mirroring"
 * tip pill shown on the Home page. Per plan decision #5:
 *   - never re-appears once dismissed
 *   - auto-dismisses 7 days after first launch
 *   - auto-dismisses after the user opens Settings once
 *   - hidden if the receiver master toggle is already on
 */
object OnboardingPreferences {
    private const val KEY_DISMISSED = "onboarding_mirroring_dismissed"
    private const val KEY_FIRST_LAUNCH_TS = "onboarding_first_launch_ts"
    private const val KEY_SETTINGS_VISITED = "onboarding_settings_visited"

    private const val ONBOARDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    fun ensureFirstLaunchRecorded(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getLong(KEY_FIRST_LAUNCH_TS, 0L) == 0L) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH_TS, System.currentTimeMillis()).apply()
        }
    }

    fun shouldShowMirroringTip(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_DISMISSED, false)) return false
        if (prefs.getBoolean(KEY_SETTINGS_VISITED, false)) return false
        if (ReceiverPreferences.isReceiverEnabled(context)) return false
        val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_TS, 0L)
        if (firstLaunch == 0L) return true
        return System.currentTimeMillis() - firstLaunch < ONBOARDING_WINDOW_MS
    }

    fun markDismissed(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()
    }

    fun markSettingsVisited(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_SETTINGS_VISITED, true)
            .apply()
    }
}
