package com.ambient.tvclock

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SpotifyPlaybackControl {

    private const val TAG = "SpotifyPlayback"
    private const val PLAYER_URL = "https://api.spotify.com/v1/me/player"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Typed result for playback operations. Lets callers handle the common
     * Spotify failure modes (no active device, free-tier user, rate-limit)
     * with specific UX instead of a generic "failed" toast.
     */
    enum class PlayResult {
        OK,
        NO_DEVICE_404,
        PREMIUM_REQUIRED_403,
        RATE_LIMITED_429,
        ERROR
    }

    fun playPause(context: Context): Boolean {
        val info = NowPlayingCenter.current
        return if (info?.isPlaying == true) {
            post(context, "$PLAYER_URL/pause")
        } else {
            post(context, "$PLAYER_URL/play")
        }
    }

    fun skipToNext(context: Context): Boolean = post(context, "$PLAYER_URL/next")

    fun skipToPrevious(context: Context): Boolean = post(context, "$PLAYER_URL/previous")

    fun playUri(context: Context, uri: String): Boolean {
        val body = JSONObject().put("uris", JSONArray().put(uri))
            .toString()
            .toRequestBody("application/json".toMediaType())
        return put(context, "$PLAYER_URL/play", body)
    }

    /**
     * Plays an ordered list of track URIs as a synthetic queue. Used when we
     * don't have a Spotify "context" URI (e.g. recently-played items streamed
     * via DJ / Smart Shuffle / search, where `context` comes back as null) but
     * still want playback to continue past the clicked track instead of
     * stopping. Spotify accepts up to 50 URIs per call.
     */
    fun playUris(
        context: Context,
        uris: List<String>,
        deviceId: String? = null
    ): PlayResult {
        if (uris.isEmpty()) return PlayResult.ERROR
        val urisArray = JSONArray()
        uris.forEach { urisArray.put(it) }
        val body = JSONObject()
            .put("uris", urisArray)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val url = if (deviceId != null) {
            "$PLAYER_URL/play?device_id=$deviceId"
        } else {
            "$PLAYER_URL/play"
        }
        return mapPlayResult(putWithCode(context, url, body))
    }

    /**
     * Starts playback of [contextUri] (a playlist/album/artist URI) at the
     * track identified by [offsetUri]. Unlike [playUri], Spotify will
     * continue playing through the rest of the context after [offsetUri].
     */
    fun playContext(
        context: Context,
        contextUri: String,
        offsetUri: String,
        deviceId: String? = null
    ): PlayResult {
        val body = JSONObject()
            .put("context_uri", contextUri)
            .put("offset", JSONObject().put("uri", offsetUri))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val url = if (deviceId != null) {
            "$PLAYER_URL/play?device_id=$deviceId"
        } else {
            "$PLAYER_URL/play"
        }
        return mapPlayResult(putWithCode(context, url, body))
    }

    fun transferToDevice(context: Context, deviceId: String): Boolean {
        val body = JSONObject()
            .put("device_ids", JSONArray().put(deviceId))
            .put("play", true)
            .toString()
            .toRequestBody("application/json".toMediaType())
        return put(context, PLAYER_URL, body)
    }

    private fun post(context: Context, url: String): Boolean {
        val token = SpotifyApiClient.ensureAccessToken(context) ?: return false
        if (!SpotifyTokenStore.isConnected(context)) return false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return execute(request) in 200..299
    }

    private fun put(context: Context, url: String, body: okhttp3.RequestBody): Boolean {
        return putWithCode(context, url, body) in 200..299
    }

    private fun putWithCode(context: Context, url: String, body: okhttp3.RequestBody): Int {
        val token = SpotifyApiClient.ensureAccessToken(context) ?: return 401
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .put(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): Int {
        return try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "HTTP $code ${request.url}: ${response.body?.string()?.take(80)}")
                }
                code
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
            -1
        }
    }

    private fun mapPlayResult(code: Int): PlayResult = when {
        code in 200..299 -> PlayResult.OK
        code == 403 -> PlayResult.PREMIUM_REQUIRED_403
        code == 404 -> PlayResult.NO_DEVICE_404
        code == 429 -> PlayResult.RATE_LIMITED_429
        else -> PlayResult.ERROR
    }
}
