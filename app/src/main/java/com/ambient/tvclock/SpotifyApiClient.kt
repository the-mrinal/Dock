package com.ambient.tvclock

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

object SpotifyApiClient {

    private const val TAG = "SpotifyApiClient"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val QUEUE_URL = "https://api.spotify.com/v1/me/player/queue"
    // Pull a deep window so we can dedupe consecutive replays of the same track
    // and still surface a useful "recently played" list. 50 is the API max.
    private const val RECENT_URL = "https://api.spotify.com/v1/me/player/recently-played?limit=50"
    private const val PLAYER_URL = "https://api.spotify.com/v1/me/player"
    private const val DEVICES_URL = "https://api.spotify.com/v1/me/player/devices"
    private const val PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"
    private const val PLAYLIST_BASE_URL = "https://api.spotify.com/v1/playlists"
    private const val ME_URL = "https://api.spotify.com/v1/me"
    private const val SAVED_TRACKS_URL = "https://api.spotify.com/v1/me/tracks"
    private const val REDIRECT_URI = "com.ambient.tvclock://spotify-callback"
    // `user-library-read` was added so we can surface Liked Songs alongside
    // the user's own playlists. Tokens issued before this scope was added
    // will 403 on /v1/me/tracks — the UI maps that to NEEDS_REAUTH and tells
    // the user to reconnect.
    private const val SCOPES =
        "user-read-playback-state user-read-currently-playing user-read-recently-played " +
            "user-modify-playback-state user-library-read " +
            "playlist-read-private playlist-read-collaborative"

    // Spotify caps these endpoints at 50/100 per page. We fetch in pages until
    // either `next` is null or we hit these soft caps — keeps memory bounded
    // and stays well under the 30s rolling rate-limit window in practice.
    const val PLAYLISTS_PAGE_LIMIT = 50
    const val PLAYLIST_TRACKS_PAGE_LIMIT = 100
    const val PLAYLISTS_MAX = 200
    const val PLAYLIST_TRACKS_MAX = 500
    /** /v1/me/tracks caps the page size at 50. */
    private const val SAVED_TRACKS_PAGE_LIMIT = 50

    // Headroom over the UI's display cap (5) so the poller can still filter the
    // currently-playing track without leaving the recently-played list short.
    private const val RECENT_LIMIT = 10

    // Spotify's per-app rate limit is a rolling window; when we trip it we want
    // every endpoint to pause together until the cool-down expires. The first
    // 429 sets [rateLimitedUntilMs] to (now + Retry-After) and subsequent calls
    // short-circuit with httpCode = 429 instead of issuing more requests.
    private const val DEFAULT_BACKOFF_SECONDS = 30L
    private const val MAX_BACKOFF_SECONDS = 600L

    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    fun rateLimitRemainingMs(): Long =
        (rateLimitedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun trip429(retryAfterHeader: String?) {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
            ?.coerceIn(1L, MAX_BACKOFF_SECONDS)
            ?: DEFAULT_BACKOFF_SECONDS
        val until = System.currentTimeMillis() + seconds * 1000L
        rateLimitedUntilMs = maxOf(rateLimitedUntilMs, until)
        Log.w(TAG, "Rate limited, backing off for ${seconds}s")
    }

    private fun clearRateLimit() {
        rateLimitedUntilMs = 0L
    }

    data class QueueResult(
        val tracks: List<SpotifyQueueTrack>,
        val httpCode: Int,
        /**
         * Album art URL for the `currently_playing` track returned by the
         * queue endpoint. Empty when not reported (Spotify omits the field
         * when nothing is playing or when this is a `recently_played` poll).
         * Used by `MusicScreenBinder` as a network fallback when the
         * MediaSession bitmap is null (Spotify TV doesn't reliably populate
         * it).
         */
        val currentImageUrl: String = ""
    )

    data class SpotifyFeed(
        val queue: QueueResult,
        val recent: QueueResult
    )

    data class PlaylistsResult(
        val playlists: List<SpotifyPlaylist>,
        val httpCode: Int
    )

    data class PlaylistTracksResult(
        val tracks: List<SpotifyQueueTrack>,
        val httpCode: Int
    )

    private val http = HttpClients.shared

    fun clientId(): String = BuildConfig.SPOTIFY_CLIENT_ID.trim()

    fun hasClientId(): Boolean = clientId().isNotEmpty()

    fun buildAuthorizeUrl(verifier: String): String {
        val challenge = SpotifyPkce.challenge(verifier)
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
            .toString()
    }

    fun exchangeCode(context: Context, code: String, verifier: String): Boolean {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId())
            .add("code_verifier", verifier)
            .build()
        return requestTokens(context, body)
    }

    fun refreshAccessToken(context: Context): Boolean {
        val refresh = SpotifyTokenStore.getRefreshToken(context) ?: return false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId())
            .build()
        return requestTokens(context, body)
    }

    private fun requestTokens(context: Context, body: FormBody): Boolean {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token error ${response.code}: $raw")
                    return false
                }
                val json = JSONObject(raw)
                val access = json.optString("access_token", "")
                if (access.isBlank()) {
                    return false
                }
                val refresh = json.optString("refresh_token", "")
                    .takeIf { it.isNotBlank() }
                    ?: SpotifyTokenStore.getRefreshToken(context)
                val expiresIn = json.optInt("expires_in", 3600)
                SpotifyTokenStore.saveTokens(context, access, refresh, expiresIn)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token request failed: ${e.message}")
            false
        }
    }

    fun ensureAccessToken(context: Context): String? {
        if (SpotifyTokenStore.isAccessTokenValid(context)) {
            return SpotifyTokenStore.getAccessToken(context)
        }
        if (!refreshAccessToken(context)) {
            return null
        }
        return SpotifyTokenStore.getAccessToken(context)
    }

    /**
     * Returns the linked user's Spotify ID, fetching it from /v1/me on first
     * use and persisting it. Used to filter [fetchPlaylists] down to playlists
     * the user actually owns. Returns null on a hard network failure; callers
     * should treat that as "filter unavailable" and show the unfiltered list
     * rather than nothing.
     */
    private fun ensureUserId(context: Context): String? {
        SpotifyTokenStore.getUserId(context)?.let { return it }
        val token = ensureAccessToken(context) ?: return null
        val request = Request.Builder()
            .url(ME_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "/v1/me HTTP ${response.code}")
                    return null
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val id = json.optString("id", "").trim()
                if (id.isNotEmpty()) {
                    SpotifyTokenStore.saveUserId(context, id)
                    id
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "/v1/me failed: ${e.message}")
            null
        }
    }

    fun fetchFeed(context: Context): SpotifyFeed {
        return SpotifyFeed(
            queue = fetchQueue(context),
            recent = fetchRecentlyPlayed(context)
        )
    }

    fun fetchQueue(context: Context): QueueResult {
        if (isRateLimited()) return QueueResult(emptyList(), 429)
        val token = ensureAccessToken(context) ?: return QueueResult(emptyList(), 401)
        return getJsonArrayTracks(QUEUE_URL, token)
    }

    fun fetchRecentlyPlayed(context: Context): QueueResult {
        if (isRateLimited()) return QueueResult(emptyList(), 429)
        val token = ensureAccessToken(context) ?: return QueueResult(emptyList(), 401)
        return getRecentTracks(RECENT_URL, token)
    }

    fun fetchPlayerState(context: Context): SpotifyPlayerState? {
        if (isRateLimited()) return null
        val token = ensureAccessToken(context) ?: return null
        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    trip429(response.header("Retry-After"))
                    return null
                }
                if (response.code == 204 || !response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val deviceJson = json.optJSONObject("device") ?: return null
                val device = parseDevice(deviceJson) ?: return null
                val contextUri = json.optJSONObject("context")
                    ?.optString("uri", "")
                    ?.trim()
                    .orEmpty()
                SpotifyPlayerState(
                    device = device,
                    isPlaying = json.optBoolean("is_playing", false),
                    contextUri = contextUri
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Player state failed: ${e.message}")
            null
        }
    }

    fun fetchDevices(context: Context): List<SpotifyDevice> {
        val token = ensureAccessToken(context) ?: return emptyList()
        val request = Request.Builder()
            .url(DEVICES_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("devices") ?: return emptyList()
                val devices = mutableListOf<SpotifyDevice>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseDevice(item)?.let { devices.add(it) }
                }
                devices
            }
        } catch (e: Exception) {
            Log.w(TAG, "Devices failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches up to [PLAYLISTS_MAX] of the user's playlists, paginating in
     * [PLAYLISTS_PAGE_LIMIT] chunks. Returns the HTTP code of the first
     * non-2xx response (or 200 on full success). 403 indicates the user
     * authenticated before the playlist scopes were added — they need to
     * reconnect via SpotifyAuthActivity.
     */
    fun fetchPlaylists(context: Context): PlaylistsResult {
        if (isRateLimited()) return PlaylistsResult(emptyList(), 429)
        val token = ensureAccessToken(context) ?: return PlaylistsResult(emptyList(), 401)
        val myUserId = ensureUserId(context)

        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        var pageCode = 200
        var hitError = false
        while (collected.size < PLAYLISTS_MAX) {
            val url = "$PLAYLISTS_URL?limit=$PLAYLISTS_PAGE_LIMIT&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    pageCode = code
                    if (code == 429) {
                        trip429(response.header("Retry-After"))
                        hitError = true
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Playlists HTTP $code: ${response.body?.string()?.take(120)}")
                        hitError = true
                        return@use
                    }
                    clearRateLimit()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val items = json.optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        offset = Int.MAX_VALUE
                        return@use
                    }
                    for (i in 0 until items.length()) {
                        if (collected.size >= PLAYLISTS_MAX) break
                        val item = items.optJSONObject(i) ?: continue
                        parsePlaylist(item)?.let { collected.add(it) }
                    }
                    val next = json.optString("next", "")
                    if (next.isBlank() || items.length() < PLAYLISTS_PAGE_LIMIT) {
                        offset = Int.MAX_VALUE
                    } else {
                        offset += PLAYLISTS_PAGE_LIMIT
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playlists request failed: ${e.message}")
                return PlaylistsResult(finalisePlaylists(context, token, collected, myUserId), -1)
            }
            if (hitError || offset == Int.MAX_VALUE) break
        }
        return PlaylistsResult(
            finalisePlaylists(context, token, collected, myUserId),
            pageCode
        )
    }

    /**
     * Filter playlists to ones owned by the linked user (drops Spotify-curated
     * mixes, followed playlists, and friends' shared playlists) and prepend
     * a synthetic Liked Songs entry. If `myUserId` is null we couldn't reach
     * /v1/me — keep the full list rather than blank the UI.
     */
    private fun finalisePlaylists(
        context: Context,
        token: String,
        collected: List<SpotifyPlaylist>,
        myUserId: String?
    ): List<SpotifyPlaylist> {
        val owned = if (myUserId != null) {
            collected.filter { it.ownerId == myUserId }
        } else {
            collected
        }
        val likedCount = fetchSavedTrackCount(token)
        return listOf(SpotifyPlaylist.likedSongs(likedCount)) + owned
    }

    private fun fetchSavedTrackCount(token: String): Int {
        val request = Request.Builder()
            .url("$SAVED_TRACKS_URL?limit=1")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) 0
                else JSONObject(response.body?.string().orEmpty()).optInt("total", 0)
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Fetches up to [PLAYLIST_TRACKS_MAX] tracks for a single playlist. Same
     * pagination & rate-limit conventions as [fetchPlaylists]. Local files
     * and null tracks are silently skipped (Spotify includes them in the
     * paginated stream for legacy reasons).
     */
    fun fetchPlaylistTracks(context: Context, playlistId: String): PlaylistTracksResult {
        if (playlistId == SpotifyPlaylist.LIKED_SONGS_ID) {
            return fetchSavedTracks(context)
        }
        if (isRateLimited()) return PlaylistTracksResult(emptyList(), 429)
        val token = ensureAccessToken(context) ?: return PlaylistTracksResult(emptyList(), 401)

        val collected = mutableListOf<SpotifyQueueTrack>()
        var offset = 0
        // Earlier we sent a `fields=` filter to keep the response slim, but
        // OkHttp's URL encoding of nested parens looked enough like a malformed
        // query that Spotify returned 403. Fetching the full track object
        // costs a few KB per page — fine for our caching cadence.
        while (collected.size < PLAYLIST_TRACKS_MAX) {
            // Spotify's own `/me/playlists` response advertises the playlist
            // contents under `/v1/playlists/{id}/items` now; the older
            // `/tracks` path returns 403 Forbidden for some accounts even
            // though their docs still document it. Use the URL Spotify hands
            // back to us — it works for both new and legacy accounts.
            val url = "$PLAYLIST_BASE_URL/$playlistId/items" +
                "?limit=$PLAYLIST_TRACKS_PAGE_LIMIT&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 429) {
                        trip429(response.header("Retry-After"))
                        return PlaylistTracksResult(collected, 429)
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Playlist tracks HTTP $code: ${response.body?.string()?.take(120)}")
                        return PlaylistTracksResult(collected, code)
                    }
                    clearRateLimit()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val items = json.optJSONArray("items") ?: return PlaylistTracksResult(collected, code)
                    if (items.length() == 0) return PlaylistTracksResult(collected, code)
                    for (i in 0 until items.length()) {
                        if (collected.size >= PLAYLIST_TRACKS_MAX) break
                        val item = items.optJSONObject(i) ?: continue
                        // Spotify renamed the row's track field too — `/items`
                        // returns `{ item: {...} }` where `/tracks` used to
                        // return `{ track: {...} }`. Try the new key first.
                        val trackObj = item.optJSONObject("item")
                            ?: item.optJSONObject("track")
                            ?: continue
                        parseTrack(trackObj)?.let { collected.add(it) }
                    }
                    val next = json.optString("next", "")
                    if (next.isBlank() || items.length() < PLAYLIST_TRACKS_PAGE_LIMIT) {
                        return PlaylistTracksResult(collected, code)
                    }
                    offset += PLAYLIST_TRACKS_PAGE_LIMIT
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playlist tracks request failed: ${e.message}")
                return PlaylistTracksResult(collected, -1)
            }
        }
        return PlaylistTracksResult(collected, 200)
    }

    /**
     * Loads the user's Liked Songs ("Saved Tracks"). Same pagination shape as
     * [fetchPlaylistTracks]; uses /v1/me/tracks which caps each page at 50.
     * Requires the `user-library-read` scope — 403 on the first page means
     * the token was issued before that scope existed.
     */
    private fun fetchSavedTracks(context: Context): PlaylistTracksResult {
        if (isRateLimited()) return PlaylistTracksResult(emptyList(), 429)
        val token = ensureAccessToken(context) ?: return PlaylistTracksResult(emptyList(), 401)

        val collected = mutableListOf<SpotifyQueueTrack>()
        var offset = 0
        while (collected.size < PLAYLIST_TRACKS_MAX) {
            val url = "$SAVED_TRACKS_URL?limit=$SAVED_TRACKS_PAGE_LIMIT&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 429) {
                        trip429(response.header("Retry-After"))
                        return PlaylistTracksResult(collected, 429)
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Saved tracks HTTP $code: ${response.body?.string()?.take(120)}")
                        return PlaylistTracksResult(collected, code)
                    }
                    clearRateLimit()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val items = json.optJSONArray("items")
                        ?: return PlaylistTracksResult(collected, code)
                    if (items.length() == 0) return PlaylistTracksResult(collected, code)
                    for (i in 0 until items.length()) {
                        if (collected.size >= PLAYLIST_TRACKS_MAX) break
                        val item = items.optJSONObject(i) ?: continue
                        val trackObj = item.optJSONObject("track") ?: continue
                        parseTrack(trackObj)?.let { collected.add(it) }
                    }
                    val next = json.optString("next", "")
                    if (next.isBlank() || items.length() < SAVED_TRACKS_PAGE_LIMIT) {
                        return PlaylistTracksResult(collected, code)
                    }
                    offset += SAVED_TRACKS_PAGE_LIMIT
                }
            } catch (e: Exception) {
                Log.e(TAG, "Saved tracks request failed: ${e.message}")
                return PlaylistTracksResult(collected, -1)
            }
        }
        return PlaylistTracksResult(collected, 200)
    }

    private fun parsePlaylist(item: JSONObject): SpotifyPlaylist? {
        val id = item.optString("id", "").trim()
        val name = item.optString("name", "").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        val imageUrl = pickImageUrl(item.optJSONArray("images"))
        // Spotify renamed `tracks` → `items` in the simplified playlist
        // object. Try the new key first, fall back to the legacy one for
        // any account whose backend still serves the old shape.
        val trackCount = item.optJSONObject("items")?.optInt("total", -1)
            ?.takeIf { it >= 0 }
            ?: item.optJSONObject("tracks")?.optInt("total", 0)
            ?: 0
        val ownerObj = item.optJSONObject("owner")
        val ownerName = ownerObj?.optString("display_name", "").orEmpty()
        val ownerId = ownerObj?.optString("id", "").orEmpty().trim()
        return SpotifyPlaylist(
            id = id,
            name = name,
            imageUrl = imageUrl,
            trackCount = trackCount,
            ownerName = ownerName,
            ownerId = ownerId
        )
    }

    private fun parseDevice(item: JSONObject): SpotifyDevice? {
        val id = item.optString("id", "").trim()
        val name = item.optString("name", "").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        return SpotifyDevice(
            id = id,
            name = name,
            isActive = item.optBoolean("is_active", false),
            type = item.optString("type", "").trim()
        )
    }

    private fun getJsonArrayTracks(url: String, token: String): QueueResult {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 429) {
                    trip429(response.header("Retry-After"))
                    return QueueResult(emptyList(), 429)
                }
                if (code == 204 || !response.isSuccessful) {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP $code: ${response.body?.string()?.take(120)}")
                    }
                    return QueueResult(emptyList(), code)
                }
                clearRateLimit()
                val json = JSONObject(response.body?.string().orEmpty())
                val currentImageUrl = json.optJSONObject("currently_playing")
                    ?.optJSONObject("album")
                    ?.let { pickImageUrl(it.optJSONArray("images")) }
                    .orEmpty()
                val queue = json.optJSONArray("queue")
                    ?: return QueueResult(emptyList(), code, currentImageUrl)
                QueueResult(parseTrackArray(queue, maxItems = 1), code, currentImageUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            QueueResult(emptyList(), -1)
        }
    }

    private fun getRecentTracks(url: String, token: String): QueueResult {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 429) {
                    trip429(response.header("Retry-After"))
                    return QueueResult(emptyList(), 429)
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "Recent HTTP $code: ${response.body?.string()?.take(120)}")
                    return QueueResult(emptyList(), code)
                }
                clearRateLimit()
                val json = JSONObject(response.body?.string().orEmpty())
                val items = json.optJSONArray("items") ?: return QueueResult(emptyList(), code)
                val tracks = mutableListOf<SpotifyQueueTrack>()
                val seen = HashSet<String>()
                for (i in 0 until items.length()) {
                    if (tracks.size >= RECENT_LIMIT) break
                    val item = items.optJSONObject(i) ?: continue
                    val track = item.optJSONObject("track") ?: continue
                    val parsed = parseTrack(track) ?: continue
                    // Each recently-played item carries the context (playlist /
                    // album / artist) it was streamed from. Without it, clicking
                    // a row would play the track in isolation and stop.
                    val contextUri = item.optJSONObject("context")
                        ?.optString("uri", "")
                        ?.trim()
                        .orEmpty()
                    val stamped = if (contextUri.isNotEmpty()) {
                        parsed.copy(contextUri = contextUri)
                    } else {
                        parsed
                    }
                    val key = dedupeKey(stamped)
                    if (!seen.add(key)) continue
                    tracks.add(stamped)
                }
                QueueResult(tracks, code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recent fetch failed: ${e.message}")
            QueueResult(emptyList(), -1)
        }
    }

    private fun parseTrackArray(array: org.json.JSONArray, maxItems: Int): List<SpotifyQueueTrack> {
        val tracks = mutableListOf<SpotifyQueueTrack>()
        for (i in 0 until array.length()) {
            if (tracks.size >= maxItems) break
            val item = array.optJSONObject(i) ?: continue
            parseTrack(item)?.let { tracks.add(it) }
        }
        return tracks
    }

    /**
     * Stable identity for collapsing duplicate rows: prefer the Spotify URI, fall
     * back to a normalised title+artist pair when (rarely) the URI is missing.
     */
    fun dedupeKey(track: SpotifyQueueTrack): String {
        val uri = track.uri.trim()
        if (uri.isNotEmpty()) return uri
        return "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
    }

    private fun parseTrack(item: JSONObject): SpotifyQueueTrack? {
        val title = item.optString("name", "").trim()
        if (title.isEmpty()) return null
        val artists = item.optJSONArray("artists")
        val artist = if (artists != null && artists.length() > 0) {
            artists.optJSONObject(0)?.optString("name", "").orEmpty()
        } else {
            ""
        }
        val id = item.optString("id", "").trim()
        val uri = item.optString("uri", "").trim()
            .ifBlank { if (id.isNotEmpty()) "spotify:track:$id" else "" }
        val album = item.optJSONObject("album")
        val imageUrl = pickImageUrl(album?.optJSONArray("images"))
        val albumName = album?.optString("name", "")?.trim().orEmpty()
        val durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0L)
        return SpotifyQueueTrack(title, artist, imageUrl, uri, durationMs = durationMs, albumName = albumName)
    }

    private fun pickImageUrl(images: org.json.JSONArray?): String {
        if (images == null || images.length() == 0) return ""
        // Spotify returns largest first; pick ~64–128px thumbnail when possible.
        var fallback = ""
        for (i in images.length() - 1 downTo 0) {
            val img = images.optJSONObject(i) ?: continue
            val url = img.optString("url", "").trim()
            if (url.isEmpty()) continue
            if (fallback.isEmpty()) fallback = url
            val height = img.optInt("height", 0)
            if (height in 48..160) return url
        }
        return fallback
    }
}
