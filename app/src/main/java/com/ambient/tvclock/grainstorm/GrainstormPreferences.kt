package com.ambient.tvclock.grainstorm

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Everything the dock needs to talk to a grainstorm library server.
 *
 * Follows the one-object-per-feature pattern the other ten `*Preferences` use:
 * the keys live here, defaults live here, and nothing else parses them.
 *
 * The token stored here is the **scoped device token**. It lets this screen
 * enrol itself and choose its own wallpaper; it can never upload or delete.
 * The full-access token stays on the Mac and in the web creator.
 */
object GrainstormPreferences {

    const val KEY_SERVER_URL = "wallpaper_server_url"
    const val KEY_DEVICE_TOKEN = "wallpaper_device_token"
    const val KEY_DEVICE_KEY = "wallpaper_device_key"
    const val KEY_POLL_INTERVAL_MS = "wallpaper_poll_interval_ms"

    /** Set by the picker/poller, not by the user: the last thing we fetched. */
    const val KEY_LAST_ETAG = "wallpaper_last_etag"
    const val KEY_CACHED_FILE = "wallpaper_cached_file"
    const val KEY_CACHED_SHA = "wallpaper_cached_sha"

    const val DEFAULT_DEVICE_KEY = "firetv-dock"
    const val DEFAULT_POLL_INTERVAL_MS = 15L * 60L * 1000L

    /** A poll cheaper than this would hammer the server for no benefit — the
     *  Mac's rotation only moves once a day. */
    const val MIN_POLL_INTERVAL_MS = 60L * 1000L

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "").orEmpty().trim().trimEnd('/')

    fun deviceToken(context: Context): String =
        prefs(context).getString(KEY_DEVICE_TOKEN, "").orEmpty().trim()

    fun deviceKey(context: Context): String =
        prefs(context).getString(KEY_DEVICE_KEY, DEFAULT_DEVICE_KEY)
            .orEmpty().trim().ifBlank { DEFAULT_DEVICE_KEY }

    /** Whether the dock has been pointed at a server at all. Until this is
     *  true the grainstorm source stays dormant and the screensaver keeps its
     *  original behaviour. */
    fun isConfigured(context: Context): Boolean = serverUrl(context).isNotBlank()

    fun pollIntervalMs(context: Context): Long {
        val raw = prefs(context).getString(KEY_POLL_INTERVAL_MS, null)
        val parsed = raw?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_MS
        return parsed.coerceAtLeast(MIN_POLL_INTERVAL_MS)
    }

    fun lastEtag(context: Context): String? =
        prefs(context).getString(KEY_LAST_ETAG, null)?.ifBlank { null }

    fun cachedFile(context: Context): String? =
        prefs(context).getString(KEY_CACHED_FILE, null)?.ifBlank { null }

    fun cachedSha(context: Context): String? =
        prefs(context).getString(KEY_CACHED_SHA, null)?.ifBlank { null }

    fun rememberCached(context: Context, etag: String?, file: String, sha: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ETAG, etag)
            .putString(KEY_CACHED_FILE, file)
            .putString(KEY_CACHED_SHA, sha)
            .apply()
    }

    /**
     * Drop the remembered ETag so the next poll re-fetches. Called when the
     * server or device identity changes — the cached image belongs to the old
     * one and must not be trusted against the new.
     */
    fun forgetCache(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_ETAG)
            .remove(KEY_CACHED_FILE)
            .remove(KEY_CACHED_SHA)
            .apply()
    }

    /** Preference keys that invalidate what we have cached. */
    fun isIdentityKey(key: String?): Boolean =
        key == KEY_SERVER_URL || key == KEY_DEVICE_KEY || key == KEY_DEVICE_TOKEN
}
