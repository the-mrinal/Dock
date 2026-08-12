package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Owns the rotating page of Unsplash photos that drive the background when
 * the user has chosen the Unsplash source.
 *
 * **Rate-limit discipline** (Demo tier is 50 req/hr):
 *  - One `/search/photos` call returns a page of up to 30 URLs. We cycle
 *    through them locally.
 *  - The page is persisted to SharedPreferences with the keyword set it was
 *    fetched for. Reopening the app reuses it.
 *  - A refetch happens only when:
 *      1. Keywords changed (cache key mismatch),
 *      2. The page is exhausted (cycled through all 30 and starting over —
 *         we go to page 2, 3, etc.), or
 *      3. The cache is older than [CACHE_TTL_MS] (24 h).
 *  - The shuffle Handler never calls the API by itself — it only advances
 *    the cursor through the in-memory list.
 *
 * Threading: the start/stop/resume/pause API is main-thread. Network work
 * happens on a single background thread; results post back to main and emit
 * via [tickListener].
 */
class UnsplashBackgroundSource(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    // Recreated on every start(): stop() shuts the pool down, and the activity
    // restarts the same source instance on every onStop/onStart cycle (e.g.
    // returning from Settings) — executing on the terminated pool would throw
    // RejectedExecutionException and crash.
    private var ioExecutor = newIoExecutor()

    private fun newIoExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unsplash-source").apply { isDaemon = true }
    }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(appContext) }

    private var tickListener: ((UnsplashClient.Photo) -> Unit)? = null
    private var page: List<UnsplashClient.Photo> = emptyList()
    private var pageKeywords: String = ""
    private var pageFetchedAtMs: Long = 0L
    private var pageNumber: Int = 1
    private var cursor: Int = 0
    private var paused: Boolean = true
    private var fetchInFlight: Boolean = false

    private val tickRunnable = Runnable { onShuffleTick() }

    /**
     * Begin listening. Restores the persisted page if any, emits the current
     * photo immediately if available, and schedules the first shuffle tick.
     * Does NOT hit the network unless the persisted page is stale or absent.
     */
    fun start(listener: (UnsplashClient.Photo) -> Unit) {
        if (ioExecutor.isShutdown) {
            ioExecutor = newIoExecutor()
        }
        tickListener = listener
        restoreCache()
        paused = false
        // Caller (BackgroundController) may have set the source to something
        // other than Unsplash; it pauses us again immediately in that case.
        scheduleNextTick()
        ensurePageAvailable()
    }

    fun stop() {
        tickListener = null
        mainHandler.removeCallbacks(tickRunnable)
        paused = true
        ioExecutor.shutdownNow()
        // shutdownNow() can kill a fetch before it resets this on main;
        // clear it so the next start() isn't blocked from fetching forever.
        fetchInFlight = false
    }

    fun pause() {
        if (paused) return
        paused = true
        mainHandler.removeCallbacks(tickRunnable)
    }

    fun resume() {
        if (!paused) return
        paused = false
        scheduleNextTick()
        ensurePageAvailable()
    }

    /** Current photo without advancing the cursor — used to repaint on a
     *  preference change without re-fetching. Null if cache is empty. */
    fun currentPhoto(): UnsplashClient.Photo? = page.getOrNull(cursor)

    /** Called when the shuffle interval preference changes — drops the
     *  pending tick and rebases on the new interval. */
    fun onIntervalChanged() {
        mainHandler.removeCallbacks(tickRunnable)
        scheduleNextTick()
    }

    /**
     * Manually advance to the next photo in the cached page. Triggered by the
     * "Shuffle photo now" Settings button and the D-pad UP shortcut on Home.
     * Costs zero API requests — it only cycles the cursor through whatever
     * page is already in memory. Auto-shuffle timer is restarted so the user's
     * manual tap effectively resets the countdown to the next auto-cycle.
     */
    fun shuffleNow() {
        if (page.isEmpty()) {
            // Nothing cached yet — kick off a fetch so the next tick has data.
            ensurePageAvailable()
            return
        }
        advanceCursor()
        emitCurrent()
        mainHandler.removeCallbacks(tickRunnable)
        scheduleNextTick()
    }

    /**
     * Called when the user changes keywords (after passing the 2h lock).
     * Clears the in-memory page so the next [ensurePageAvailable] hits the
     * API with the new query. The persisted cache is overwritten on success.
     */
    fun onKeywordsChanged() {
        val current = currentKeywordSignature()
        if (current == pageKeywords) return
        page = emptyList()
        pageNumber = 1
        cursor = 0
        ensurePageAvailable()
    }

    private fun scheduleNextTick() {
        if (paused) return
        if (tickListener == null) return
        mainHandler.removeCallbacks(tickRunnable)
        val interval = BackgroundPreferences.shuffleIntervalMs(appContext)
            .coerceAtLeast(60_000L) // hard floor so a misconfigured pref can't burn CPU
        mainHandler.postDelayed(tickRunnable, interval)
    }

    private fun onShuffleTick() {
        if (paused) return
        advanceCursor()
        emitCurrent()
        scheduleNextTick()
    }

    private fun advanceCursor() {
        if (page.isEmpty()) return
        cursor++
        if (cursor >= page.size) {
            // Move to the next API page for fresh content. Wrap back to 1
            // after a small cap so we don't drift forever — Unsplash's later
            // pages are progressively less relevant for most queries.
            cursor = 0
            pageNumber = (pageNumber % MAX_PAGE_DEPTH) + 1
            page = emptyList()
            ensurePageAvailable()
        }
        persistCursor()
    }

    private fun emitCurrent() {
        val photo = currentPhoto() ?: return
        // Per Unsplash ToS, register a "download" hit when a photo is shown.
        ioExecutor.execute { UnsplashClient.trackDownload(photo) }
        tickListener?.invoke(photo)
    }

    /**
     * Decide whether the in-memory page is still good. If it is, this is a
     * no-op. Otherwise schedules a background fetch.
     */
    private fun ensurePageAvailable() {
        if (paused) return
        if (fetchInFlight) return
        if (!UnsplashClient.hasAccessKey()) return
        val signature = currentKeywordSignature()
        val freshEnough = pageFetchedAtMs > 0 &&
            System.currentTimeMillis() - pageFetchedAtMs < CACHE_TTL_MS
        if (page.isNotEmpty() && pageKeywords == signature && freshEnough) {
            // Cache is good; nothing to do. Emit current photo if we haven't
            // already (e.g. after start() restored it).
            emitCurrent()
            return
        }
        if (signature != pageKeywords) {
            pageNumber = 1
            cursor = 0
        }
        fetchInFlight = true
        ioExecutor.execute { fetchPage(signature, pageNumber) }
    }

    private fun fetchPage(signature: String, page: Int) {
        val keywords = BackgroundPreferences.mergedKeywords(appContext)
        val result = UnsplashClient.searchLandscape(keywords, page)
        mainHandler.post {
            fetchInFlight = false
            if (result.photos.isEmpty()) {
                Log.w(TAG, "Unsplash returned ${result.photos.size} photos (HTTP ${result.httpCode})")
                // Leave any prior cache in place; better to keep showing the
                // last photo than fall back to black on a transient failure.
                return@post
            }
            this.page = result.photos
            this.pageKeywords = signature
            this.pageFetchedAtMs = System.currentTimeMillis()
            this.cursor = 0
            persistCache()
            emitCurrent()
        }
    }

    private fun currentKeywordSignature(): String =
        BackgroundPreferences.mergedKeywords(appContext)
            .joinToString("|") { it.lowercase() }

    // --- Persistence ---------------------------------------------------------

    private fun restoreCache() {
        val json = prefs.getString(KEY_CACHE, null) ?: return
        try {
            val obj = JSONObject(json)
            pageKeywords = obj.optString("keywords", "")
            pageFetchedAtMs = obj.optLong("fetchedAt", 0L)
            pageNumber = obj.optInt("pageNumber", 1).coerceAtLeast(1)
            cursor = obj.optInt("cursor", 0).coerceAtLeast(0)
            val arr = obj.optJSONArray("photos") ?: return
            page = buildList {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    add(
                        UnsplashClient.Photo(
                            id = p.optString("id", ""),
                            imageUrl = p.optString("imageUrl", ""),
                            downloadLocation = p.optString("downloadLocation", ""),
                            photographerName = p.optString("photographerName", ""),
                            photographerUsername = p.optString("photographerUsername", ""),
                            description = p.optString("description", ""),
                        )
                    )
                }
            }
            if (cursor >= page.size) cursor = 0
        } catch (e: Exception) {
            Log.w(TAG, "restoreCache failed: ${e.message}")
            page = emptyList()
        }
    }

    private fun persistCache() {
        val arr = JSONArray()
        for (p in page) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("imageUrl", p.imageUrl)
                    .put("downloadLocation", p.downloadLocation)
                    .put("photographerName", p.photographerName)
                    .put("photographerUsername", p.photographerUsername)
                    .put("description", p.description)
            )
        }
        val obj = JSONObject()
            .put("keywords", pageKeywords)
            .put("fetchedAt", pageFetchedAtMs)
            .put("pageNumber", pageNumber)
            .put("cursor", cursor)
            .put("photos", arr)
        prefs.edit().putString(KEY_CACHE, obj.toString()).apply()
    }

    private fun persistCursor() {
        // Lightweight: rewrite only the cursor field. SharedPreferences doesn't
        // support partial JSON updates so we re-serialise — it's still cheap.
        if (page.isEmpty()) return
        persistCache()
    }

    companion object {
        private const val TAG = "UnsplashSource"
        private const val KEY_CACHE = "background_unsplash_cache"

        /** Photos are cycled within a fetched page until exhausted, but after
         *  this much time we refetch even mid-page to pick up new content. */
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        /** How many `/search/photos` pages we'll walk before wrapping back to 1.
         *  Bounds how far we drift into low-relevance long-tail results. */
        private const val MAX_PAGE_DEPTH = 4
    }
}
