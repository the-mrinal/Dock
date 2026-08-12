package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object HomeLabPreferences {
    const val KEY_SHOW_HOMELAB = "show_homelab"
    const val KEY_HOMELAB_URL = "homelab_url"
    const val DEFAULT_URL = "https://home.mrinal.dev"

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_HOMELAB, false)
    }

    fun getUrl(context: Context): String {
        val url = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_HOMELAB_URL, DEFAULT_URL)
            ?.trim()
            .orEmpty()
        return url.ifBlank { DEFAULT_URL }
    }

    fun isPageAvailable(context: Context): Boolean =
        isEnabled(context) && getUrl(context).isNotBlank()
}
