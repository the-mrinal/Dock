package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object CalendarPreferences {
    const val KEY_SHOW_CALENDAR = "show_calendar"
    const val KEY_PERSONAL_URL = "personal_calendar_url"
    const val KEY_WORK_URL = "work_calendar_url"

    private const val POLL_MS = 5 * 60 * 1000L

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_CALENDAR, true)
    }

    fun getPersonalUrl(context: Context): String =
        url(context, KEY_PERSONAL_URL)

    fun getWorkUrl(context: Context): String =
        url(context, KEY_WORK_URL)

    fun pollIntervalMs(): Long = POLL_MS

    private fun url(context: Context, key: String): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(key, "")
            ?.trim()
            .orEmpty()
    }
}
