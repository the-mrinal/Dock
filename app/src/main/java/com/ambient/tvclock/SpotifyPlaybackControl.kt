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
        return execute(request)
    }

    private fun put(context: Context, url: String, body: okhttp3.RequestBody): Boolean {
        val token = SpotifyApiClient.ensureAccessToken(context) ?: return false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .put(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): Boolean {
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204) {
                    true
                } else {
                    Log.w(TAG, "HTTP ${response.code} ${request.url}: ${response.body?.string()?.take(80)}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
            false
        }
    }
}
