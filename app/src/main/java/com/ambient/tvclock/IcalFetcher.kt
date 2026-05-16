package com.ambient.tvclock

import android.util.Log
import okhttp3.Request

object IcalFetcher {

    private const val TAG = "IcalFetcher"

    private val client = HttpClients.shared

    fun fetch(url: String): String? {
        if (url.isBlank()) {
            return null
        }
        val trimmed = url.trim()
        val request = Request.Builder()
            .url(trimmed)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; TV Awake Clock) AppleWebKit/537.36"
            )
            .header("Accept", "text/calendar,*/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for calendar feed")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
