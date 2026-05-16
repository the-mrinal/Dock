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
    private const val REDIRECT_URI = "com.ambient.tvclock://spotify-callback"
    private const val SCOPES =
        "user-read-playback-state user-read-currently-playing user-read-recently-played user-modify-playback-state"

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
        val httpCode: Int
    )

    data class SpotifyFeed(
        val queue: QueueResult,
        val recent: QueueResult
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
                SpotifyPlayerState(
                    device = device,
                    isPlaying = json.optBoolean("is_playing", false)
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
                val queue = json.optJSONArray("queue") ?: return QueueResult(emptyList(), code)
                QueueResult(parseTrackArray(queue, maxItems = 1), code)
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
                    val key = dedupeKey(parsed)
                    if (!seen.add(key)) continue
                    tracks.add(parsed)
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
        return SpotifyQueueTrack(title, artist, imageUrl, uri)
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
