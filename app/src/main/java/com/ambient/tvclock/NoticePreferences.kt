package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Notice board settings. Follows the one-object-per-feature pattern the other
 * *Preferences use: the keys live here, defaults live here, and nothing else
 * parses them.
 *
 * There is no default URL on purpose — the feed is something the user hosts,
 * so an unset board is off rather than pointed at a guess.
 */
object NoticePreferences {
    const val KEY_SHOW_NOTICES = "show_notices"
    const val KEY_NOTICES_URL = "notices_url"

    // The board is a handful of notices that change by the day; two minutes is
    // frequent enough to pick up an edit while barely touching the network.
    private const val POLL_MS = 2 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SHOW_NOTICES, false)

    fun getUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_NOTICES_URL, "")
            ?.trim()
            .orEmpty()

    /** Configured to run at all. Whether the page *shows* also needs a live
     *  notice — MainActivity checks NoticeCenter for that. */
    fun isPageAvailable(context: Context): Boolean =
        isEnabled(context) && getUrl(context).isNotBlank()

    fun pollIntervalMs(): Long = POLL_MS
}
