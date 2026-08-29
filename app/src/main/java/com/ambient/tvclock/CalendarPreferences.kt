package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/** Whether the calendar is shown, and how often it is refreshed. Where it comes from is [MyLifePreferences]. */
object CalendarPreferences {
    const val KEY_SHOW_CALENDAR = "show_calendar"

    private const val POLL_MS = 15 * 60 * 1000L

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_CALENDAR, true)
    }

    fun pollIntervalMs(): Long = POLL_MS
}
