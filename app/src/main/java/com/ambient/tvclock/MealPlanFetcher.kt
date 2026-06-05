package com.ambient.tvclock

import android.util.Log
import okhttp3.Request

object MealPlanFetcher {

    private const val TAG = "MealPlanFetcher"

    private val client = HttpClients.shared

    fun fetch(url: String): String? {
        if (url.isBlank()) return null
        val request = Request.Builder()
            .url(url.trim())
            .get()
            .header("Accept", "application/json,*/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for meal plan")
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
