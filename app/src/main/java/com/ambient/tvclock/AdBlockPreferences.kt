package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Settings for the thin "Protected" ad-block status card. The card only ever
 * reads a summary from the homelab dashboard — all filtering/debloating work
 * lives on the homelab, not here. Mirrors [HomeLabPreferences].
 */
object AdBlockPreferences {
    const val KEY_SHOW_ADBLOCK = "show_adblock"
    const val KEY_DASHBOARD_URL = "adblock_dashboard_url"
    const val DEFAULT_URL = "http://homelab.local:8099"

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SHOW_ADBLOCK, false)

    fun getDashboardUrl(context: Context): String {
        val url = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_DASHBOARD_URL, DEFAULT_URL)
            ?.trim()
            .orEmpty()
        return url.ifBlank { DEFAULT_URL }.trimEnd('/')
    }

    /** Endpoint the card polls for the block summary. */
    fun getSummaryUrl(context: Context): String = getDashboardUrl(context) + "/api/summary"

    fun isPageAvailable(context: Context): Boolean =
        isEnabled(context) && getDashboardUrl(context).isNotBlank()
}
