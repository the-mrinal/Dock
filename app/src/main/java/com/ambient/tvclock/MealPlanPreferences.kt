package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object MealPlanPreferences {
    const val KEY_MEAL_PLAN_URL = "meal_plan_url"
    private const val KEY_CACHE_JSON = "meal_plan_cache_json"
    private const val KEY_LAST_FETCH_MS = "meal_plan_last_fetch_ms"

    private const val POLL_MS = 6 * 60 * 60 * 1000L

    fun getUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_MEAL_PLAN_URL, "")
            ?.trim()
            .orEmpty()

    fun pollIntervalMs(): Long = POLL_MS

    fun saveCache(context: Context, json: String, fetchedAtMs: Long) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_CACHE_JSON, json)
            .putLong(KEY_LAST_FETCH_MS, fetchedAtMs)
            .apply()
    }

    fun cachedJson(context: Context): String? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_CACHE_JSON, null)
        return if (raw.isNullOrBlank()) null else raw
    }

    fun cachedFetchAtMs(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(KEY_LAST_FETCH_MS, 0L)
}
