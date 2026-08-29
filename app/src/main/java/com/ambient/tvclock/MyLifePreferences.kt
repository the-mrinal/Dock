package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Where the Dock reads Mrinal's calendar from: the my-life API
 * (openclaw-proposal/spec/my-life.md). The TV holds `DOCK_KEY` — a key that
 * my-life accepts on the two calendar reads only — never the service's own key.
 * Mirrors [HomeLabPreferences].
 */
object MyLifePreferences {
    const val KEY_URL = "mylife_url"
    const val KEY_KEY = "mylife_key"
    const val DEFAULT_URL = "https://fix.mrinal.dev/api"

    fun getUrl(context: Context): String {
        val url = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_URL, DEFAULT_URL)
            ?.trim()
            .orEmpty()
        return url.ifBlank { DEFAULT_URL }.trimEnd('/')
    }

    fun getKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_KEY, "")
            ?.trim()
            .orEmpty()

    /** The calendar can be shown once a key is saved; the URL always has a default. */
    fun isConfigured(context: Context): Boolean = getKey(context).isNotBlank()
}
