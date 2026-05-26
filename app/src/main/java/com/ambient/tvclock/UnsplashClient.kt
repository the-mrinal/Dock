package com.ambient.tvclock

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Thin wrapper around Unsplash's public photo search API. Caller threads must be
 * background threads — every method here is synchronous I/O. Designed to stay
 * well under the Demo-tier 50 req/hr ceiling by returning paged results that
 * the caller cycles through locally.
 *
 * Auth: a single Access Key (Client-ID) is read from BuildConfig at compile
 * time (set via local.properties → app/build.gradle.kts). No OAuth.
 *
 * Rate-limit handling mirrors [SpotifyApiClient]: a tripped 429 (or
 * `X-Ratelimit-Remaining: 0`) parks every request for [DEFAULT_BACKOFF_SECONDS]
 * unless the server returned a Retry-After hint. We also read
 * `X-Ratelimit-Remaining` on every 2xx response so callers can throttle
 * pre-emptively as the bucket drains.
 */
object UnsplashClient {

    private const val TAG = "UnsplashClient"
    private const val SEARCH_URL = "https://api.unsplash.com/search/photos"
    private const val DEFAULT_FALLBACK_QUERY = "landscape"

    /** Demo-tier cap on per-page results. */
    const val PAGE_SIZE = 30

    private const val DEFAULT_BACKOFF_SECONDS = 60L * 15 // 15 min — Unsplash quota resets hourly
    private const val MAX_BACKOFF_SECONDS = 60L * 60

    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    @Volatile
    private var lastRemaining: Int = -1

    private val http = HttpClients.shared

    fun accessKey(): String = BuildConfig.UNSPLASH_ACCESS_KEY.trim()

    fun hasAccessKey(): Boolean = accessKey().isNotEmpty()

    fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    /** Most recent value of the `X-Ratelimit-Remaining` header, or -1 if unknown. */
    fun lastRemaining(): Int = lastRemaining

    data class Photo(
        val id: String,
        /** ~1080 px landscape URL, served by images.unsplash.com (not rate-limited). */
        val imageUrl: String,
        /** Unsplash-required hit for usage tracking. Issue a GET on this when the
         *  photo is actually shown to the user. */
        val downloadLocation: String,
        /** Photographer's display name — used for the on-screen credit. */
        val photographerName: String,
        /** Username (without `@`) — caption links can be built as
         *  https://unsplash.com/@{username}?utm_source=…&utm_medium=referral */
        val photographerUsername: String,
        /** Best-effort description string. Empty when Unsplash returned neither
         *  `description` nor `alt_description`. */
        val description: String,
    )

    data class SearchResult(
        val photos: List<Photo>,
        val httpCode: Int,
    )

    /**
     * Search Unsplash for up to [PAGE_SIZE] landscape-orientation photos
     * matching [keywords]. Multiple keywords are space-joined (Unsplash treats
     * the query as full-text search across photo tags/descriptions).
     *
     * Returns an empty list with httpCode = 0 if no key is configured, so
     * callers can render a sensible "set up an API key" hint instead of
     * spamming the network.
     */
    fun searchLandscape(keywords: List<String>, page: Int = 1): SearchResult {
        if (!hasAccessKey()) return SearchResult(emptyList(), 0)
        if (isRateLimited()) return SearchResult(emptyList(), 429)

        val query = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { DEFAULT_FALLBACK_QUERY }

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("orientation", "landscape")
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("content_filter", "high")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Client-ID ${accessKey()}")
            .header("Accept-Version", "v1")
            .get()
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                response.header("X-Ratelimit-Remaining")?.toIntOrNull()
                    ?.also { lastRemaining = it }
                if (code == 429) {
                    trip429(response.header("Retry-After"))
                    return SearchResult(emptyList(), 429)
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "search HTTP $code: ${response.body?.string()?.take(160)}")
                    return SearchResult(emptyList(), code)
                }
                if ((lastRemaining in 0..2)) {
                    Log.w(TAG, "Approaching Unsplash quota: $lastRemaining remaining")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val results = json.optJSONArray("results")
                    ?: return SearchResult(emptyList(), code)
                val photos = buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        parsePhoto(item)?.let { add(it) }
                    }
                }
                SearchResult(photos, code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            SearchResult(emptyList(), -1)
        }
    }

    /**
     * Required by the Unsplash API Guidelines: when a photo is actually
     * displayed to the user, GET the `links.download_location` URL once.
     * Does not download a JPEG — just registers the usage event. Fire and
     * forget; failures are logged at debug level and ignored.
     */
    fun trackDownload(photo: Photo) {
        if (!hasAccessKey() || photo.downloadLocation.isBlank()) return
        if (isRateLimited()) return
        val request = Request.Builder()
            .url(photo.downloadLocation)
            .header("Authorization", "Client-ID ${accessKey()}")
            .header("Accept-Version", "v1")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                response.header("X-Ratelimit-Remaining")?.toIntOrNull()
                    ?.also { lastRemaining = it }
                if (response.code == 429) {
                    trip429(response.header("Retry-After"))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "trackDownload swallowed: ${e.message}")
        }
    }

    private fun parsePhoto(item: JSONObject): Photo? {
        val id = item.optString("id", "").trim()
        if (id.isEmpty()) return null
        val urls = item.optJSONObject("urls") ?: return null
        // `full` is ~2400 px wide — the native sweet spot for 4K TV backgrounds
        // in sharp wallpaper mode. We previously used `regular` (~1080 px) but
        // it upscaled visibly on 4K panels. Fall back to `regular` if `full`
        // is missing for some reason; both come from the same CDN domain
        // (images.unsplash.com) which doesn't count against the API quota.
        val imageUrl = urls.optString("full", "").trim()
            .ifEmpty { urls.optString("regular", "").trim() }
        if (imageUrl.isEmpty()) return null
        val downloadLocation = item.optJSONObject("links")
            ?.optString("download_location", "")
            ?.trim()
            .orEmpty()
        val user = item.optJSONObject("user")
        val photographerName = user?.optString("name", "")?.trim().orEmpty()
        val photographerUsername = user?.optString("username", "")?.trim().orEmpty()
        val description = item.optString("description", "")
            .takeIf { it.isNotBlank() }
            ?: item.optString("alt_description", "")
        return Photo(
            id = id,
            imageUrl = imageUrl,
            downloadLocation = downloadLocation,
            photographerName = photographerName,
            photographerUsername = photographerUsername,
            description = description.trim(),
        )
    }

    private fun trip429(retryAfterHeader: String?) {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
            ?.coerceIn(1L, MAX_BACKOFF_SECONDS)
            ?: DEFAULT_BACKOFF_SECONDS
        val until = System.currentTimeMillis() + seconds * 1000L
        rateLimitedUntilMs = maxOf(rateLimitedUntilMs, until)
        Log.w(TAG, "Rate limited, backing off for ${seconds}s")
    }
}
