package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object BackgroundPreferences {
    const val KEY_WHEN_IDLE = "background_when_idle"
    const val KEY_WHEN_PLAYING = "background_when_playing"
    const val KEY_BLUR = "background_blur"
    const val KEY_KEYWORD_PRESETS = "background_keyword_presets"
    const val KEY_CUSTOM_KEYWORDS = "background_custom_keywords"
    const val KEY_SHUFFLE_INTERVAL_MS = "background_shuffle_interval_ms"
    /** Wall-clock millis of the last keyword change. Used to enforce the
     *  client-side 2 h re-edit window so Unsplash's per-app rate limit is
     *  protected from a rapid-fire knob-twiddling user. */
    const val KEY_KEYWORDS_LAST_CHANGED_MS = "background_keywords_last_changed_ms"
    /** Bumped to a fresh timestamp whenever the user taps "Shuffle photo now"
     *  from Settings. [BackgroundController] watches this key (any change) and
     *  cycles to the next cached photo — no API call, unlimited. */
    const val KEY_SHUFFLE_SIGNAL = "background_shuffle_signal"

    /** Minimum gap between user-initiated keyword changes. */
    const val KEYWORDS_LOCK_WINDOW_MS = 2L * 60L * 60L * 1000L

    const val SOURCE_BLACK = "black"
    const val SOURCE_UNSPLASH = "unsplash"
    const val SOURCE_ALBUM_ART = "album_art"

    const val DEFAULT_WHEN_IDLE = SOURCE_BLACK
    const val DEFAULT_WHEN_PLAYING = SOURCE_ALBUM_ART
    const val DEFAULT_BLUR = true
    const val DEFAULT_SHUFFLE_INTERVAL_MS = 600_000L // 10 min

    enum class Source { BLACK, UNSPLASH, ALBUM_ART }

    fun whenIdleSource(context: Context): Source =
        parseSource(stringPref(context, KEY_WHEN_IDLE, DEFAULT_WHEN_IDLE))

    fun whenPlayingSource(context: Context): Source =
        parseSource(stringPref(context, KEY_WHEN_PLAYING, DEFAULT_WHEN_PLAYING))

    fun blurEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_BLUR, DEFAULT_BLUR)

    fun shuffleIntervalMs(context: Context): Long {
        val raw = stringPref(context, KEY_SHUFFLE_INTERVAL_MS, DEFAULT_SHUFFLE_INTERVAL_MS.toString())
        return raw.toLongOrNull() ?: DEFAULT_SHUFFLE_INTERVAL_MS
    }

    /**
     * Returns the merged keyword list to feed to Unsplash search: selected presets +
     * custom comma-separated keywords. Empties are dropped, duplicates removed,
     * order preserved (presets first).
     */
    fun mergedKeywords(context: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val presets = prefs.getStringSet(KEY_KEYWORD_PRESETS, emptySet()).orEmpty()
        val custom = prefs.getString(KEY_CUSTOM_KEYWORDS, "").orEmpty()
        val customSplit = custom.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = linkedSetOf<String>()
        for (k in presets) if (k.isNotBlank()) seen += k.trim()
        for (k in customSplit) seen += k
        return seen.toList()
    }

    fun keywordsLastChangedMs(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(KEY_KEYWORDS_LAST_CHANGED_MS, 0L)

    /** Millis remaining until the keyword fields are user-editable again.
     *  0 (or negative) means unlocked. */
    fun keywordsLockRemainingMs(context: Context): Long {
        val last = keywordsLastChangedMs(context)
        if (last == 0L) return 0L
        val elapsed = System.currentTimeMillis() - last
        return (KEYWORDS_LOCK_WINDOW_MS - elapsed).coerceAtLeast(0L)
    }

    fun keywordsAreLocked(context: Context): Boolean =
        keywordsLockRemainingMs(context) > 0L

    /** Record that the user just changed a keyword preference. Starts the
     *  2 h lock countdown. Call from the preference-change listener only. */
    fun markKeywordsChanged(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(KEY_KEYWORDS_LAST_CHANGED_MS, System.currentTimeMillis())
            .apply()
    }

    /** Pulse the shuffle signal — flips the value so the prefs listener in
     *  [BackgroundController] (running inside MainActivity) treats it as a
     *  change event and cycles to the next cached photo. */
    fun pulseShuffleSignal(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(KEY_SHUFFLE_SIGNAL, System.currentTimeMillis())
            .apply()
    }

    /** True if either the idle or now-playing source is set to Unsplash. */
    fun isUnsplashConfigured(context: Context): Boolean =
        whenIdleSource(context) == Source.UNSPLASH ||
            whenPlayingSource(context) == Source.UNSPLASH

    private fun stringPref(context: Context, key: String, default: String): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(key, default) ?: default

    private fun parseSource(raw: String): Source = when (raw) {
        SOURCE_UNSPLASH -> Source.UNSPLASH
        SOURCE_ALBUM_ART -> Source.ALBUM_ART
        else -> Source.BLACK
    }
}
