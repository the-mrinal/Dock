package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object NowPlayingPreferences {
    const val KEY_SHOW_NOW_PLAYING = "show_now_playing"

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_NOW_PLAYING, true)
    }
}
