package com.ambient.tvclock

import android.content.Context
import android.util.Log

object MealPlanRepository {

    private const val TAG = "MealPlanRepository"
    private const val COALESCE_WINDOW_MS = 60_000L

    fun refresh(context: Context): MealPlanSnapshot {
        val url = MealPlanPreferences.getUrl(context)
        val now = System.currentTimeMillis()

        if (url.isBlank()) {
            return MealPlanSnapshot(
                plan = null,
                lastUpdatedMillis = now,
                error = MealPlanError.URL_EMPTY,
                isFromCache = false
            )
        }

        val cached = MealPlanCenter.current
        if (cached.plan != null &&
            !cached.isFromCache &&
            cached.error == MealPlanError.NONE &&
            now - cached.lastUpdatedMillis < COALESCE_WINDOW_MS
        ) {
            return cached
        }

        val body = MealPlanFetcher.fetch(url)
        if (body != null) {
            val plan = MealPlanParser.parse(body)
            if (plan != null) {
                MealPlanPreferences.saveCache(context, body, now)
                Log.i(TAG, "Fetched + parsed meal plan (days=${plan.days.size}, routine=${plan.dailyRoutine.size})")
                return MealPlanSnapshot(plan, now, MealPlanError.NONE, isFromCache = false)
            }
            // Fetch succeeded but parse failed — fall through to cache.
            Log.w(TAG, "Parse failed for fresh body; falling back to cache")
            val fallback = parseCached(context)
            if (fallback != null) return fallback
            return MealPlanSnapshot(
                plan = null,
                lastUpdatedMillis = now,
                error = MealPlanError.PARSE_FAILED,
                isFromCache = false
            )
        }

        // Fetch failed — serve cache if we have it.
        val fallback = parseCached(context)
        if (fallback != null) {
            return fallback.copy(error = MealPlanError.FETCH_FAILED, isFromCache = true)
        }
        return MealPlanSnapshot(
            plan = null,
            lastUpdatedMillis = now,
            error = MealPlanError.FETCH_FAILED,
            isFromCache = false
        )
    }

    private fun parseCached(context: Context): MealPlanSnapshot? {
        val raw = MealPlanPreferences.cachedJson(context) ?: return null
        val plan = MealPlanParser.parse(raw) ?: return null
        val ts = MealPlanPreferences.cachedFetchAtMs(context)
        return MealPlanSnapshot(
            plan = plan,
            lastUpdatedMillis = ts,
            error = MealPlanError.NONE,
            isFromCache = true
        )
    }
}
