package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Polls Spotify Web API for the up-next queue, recently played and active device.
 *
 * Per-endpoint cadence (so we don't trip Spotify's 429 rolling window):
 *   - queue     : refreshes fast, since it changes any time the user skips
 *   - player    : moderate, just for active-device name
 *   - recent    : slow, since a track can only enter "recently played" when one
 *                 finishes — ~every minute is plenty
 *
 * When any single call returns 429, [SpotifyApiClient] sets a global cool-down
 * and every subsequent call short-circuits until it expires. The poller also
 * holds onto the last successful `recentlyPlayed` list across transient errors
 * so the UI doesn't flicker to "No recent tracks yet" on a momentary failure.
 */
class SpotifyQueuePoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)

    private var lastQueueFetchAt: Long = 0L
    private var lastRecentFetchAt: Long = 0L
    private var lastPlayerFetchAt: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            publish()
            scheduleNext()
        }
    }

    fun start() {
        stop()
        // Force a full refresh on start so the UI gets data immediately.
        lastQueueFetchAt = 0L
        lastRecentFetchAt = 0L
        lastPlayerFetchAt = 0L
        publish()
        scheduleNext()
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
    }

    fun publishNow() {
        // External "user did something" nudges only force the cheap endpoints to
        // re-fetch; recent stays on its own cadence to keep us under the limit.
        lastQueueFetchAt = 0L
        lastPlayerFetchAt = 0L
        handler.removeCallbacks(pollRunnable)
        publish()
        scheduleNext()
    }

    /**
     * Post the next wakeup at the earliest moment any endpoint becomes due.
     * Drops idle-page wakes from one-every-5s to one-every-60s, while playing
     * stays close to the 10s queue cadence.
     */
    private fun scheduleNext() {
        handler.removeCallbacks(pollRunnable)

        // Not linked or in 429 cool-down: just check back in a minute.
        if (!SpotifyTokenStore.isConnected(appContext) || SpotifyApiClient.isRateLimited()) {
            handler.postDelayed(pollRunnable, MAX_DELAY_MS)
            return
        }

        val info = NowPlayingCenter.current
        val spotifyHere = info != null &&
            info.hasActiveSession &&
            MediaSessionHelper.isSpotify(info.packageName)
        val iv = if (spotifyHere && info!!.isPlaying) IntervalSet.PLAYING else IntervalSet.IDLE
        val now = SystemClock.elapsedRealtime()
        val nextQueue = if (spotifyHere) iv.queueMs - (now - lastQueueFetchAt) else Long.MAX_VALUE
        val nextPlayer = iv.playerMs - (now - lastPlayerFetchAt)
        val nextRecent = iv.recentMs - (now - lastRecentFetchAt)
        val next = minOf(nextQueue, nextPlayer, nextRecent).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        handler.postDelayed(pollRunnable, next)
    }

    private fun publish() {
        if (!SpotifyTokenStore.isConnected(appContext)) {
            postSnapshot(
                SpotifyQueueSnapshot(
                    state = SpotifyQueueState.NOT_LINKED,
                    recentState = SpotifyQueueState.NOT_LINKED
                )
            )
            return
        }
        if (SpotifyApiClient.isRateLimited()) {
            // Keep what we have — no point firing more requests during the cool-down.
            return
        }

        val info = NowPlayingCenter.current
        val spotifyHere = info != null &&
            info.hasActiveSession &&
            MediaSessionHelper.isSpotify(info.packageName)

        val now = SystemClock.elapsedRealtime()
        val activeIntervals = if (spotifyHere && info!!.isPlaying) IntervalSet.PLAYING else IntervalSet.IDLE

        val queueDue = spotifyHere && (now - lastQueueFetchAt) >= activeIntervals.queueMs
        val recentDue = (now - lastRecentFetchAt) >= activeIntervals.recentMs
        val playerDue = (now - lastPlayerFetchAt) >= activeIntervals.playerMs

        if (!queueDue && !recentDue && !playerDue) return

        val queueTask = if (queueDue) {
            lastQueueFetchAt = now
            submitOrNull { SpotifyApiClient.fetchQueue(appContext) }
        } else null
        val recentTask = if (recentDue) {
            lastRecentFetchAt = now
            submitOrNull { SpotifyApiClient.fetchRecentlyPlayed(appContext) }
        } else null
        val playerTask = if (playerDue) {
            lastPlayerFetchAt = now
            submitOrNull { SpotifyApiClient.fetchPlayerState(appContext) }
        } else null

        executor.execute {
            val queue = queueTask?.get()
            val recent = recentTask?.get()
            val player = playerTask?.get()

            val previous = SpotifyQueueCenter.current

            val nextUpNext = when {
                queue != null && queue.tracks.isNotEmpty() -> queue.tracks.first()
                queue != null -> null
                else -> previous.upNext
            }
            val nextQueueState = when {
                !spotifyHere -> SpotifyQueueState.NOT_PLAYING
                queue != null -> mapQueueState(queue)
                else -> previous.state
            }

            val current = NowPlayingCenter.current
            val freshRecent = recent?.let { dedupeRecent(it.tracks, current) }
            val (nextRecentTracks, nextRecentState) = computeRecentTracks(
                fresh = recent,
                freshDeduped = freshRecent,
                previous = previous
            )

            val nextDeviceName = player?.device?.name ?: previous.activeDeviceName
            // Same carry-over policy as device name: prefer a fresh value
            // from the player fetch, otherwise reuse what we last knew so a
            // queue-only tick doesn't strip context off the Up Next track.
            val nextContextUri = player?.contextUri?.takeIf { it.isNotBlank() }
                ?: previous.activeContextUri
            val stampedUpNext = nextUpNext?.let { track ->
                if (track.contextUri.isBlank() && !nextContextUri.isNullOrBlank()) {
                    track.copy(contextUri = nextContextUri)
                } else {
                    track
                }
            }

            postSnapshot(
                SpotifyQueueSnapshot(
                    upNext = stampedUpNext,
                    recentlyPlayed = nextRecentTracks,
                    state = nextQueueState,
                    recentState = nextRecentState,
                    activeDeviceName = nextDeviceName,
                    activeContextUri = nextContextUri
                )
            )
        }
    }

    /**
     * Decide what `recentlyPlayed` + recentState to publish:
     *  - We didn't fetch recent this tick  -> carry over previous unchanged
     *  - Fetch failed / 429                -> carry over previous list, keep its state
     *                                          (only show API_ERROR if we have nothing cached)
     *  - Fetch returned 0 tracks          -> NO_QUEUE
     *  - Fetch returned tracks            -> OK with the new list
     */
    private fun computeRecentTracks(
        fresh: SpotifyApiClient.QueueResult?,
        freshDeduped: List<SpotifyQueueTrack>?,
        previous: SpotifyQueueSnapshot
    ): Pair<List<SpotifyQueueTrack>, SpotifyQueueState> {
        if (fresh == null) {
            return previous.recentlyPlayed to previous.recentState
        }
        val deduped = freshDeduped ?: emptyList()
        val failed = fresh.httpCode != 200 && fresh.httpCode != 204
        if (failed) {
            val errorState = when {
                fresh.httpCode == 429 || SpotifyApiClient.isRateLimited() ->
                    SpotifyQueueState.RATE_LIMITED
                else -> SpotifyQueueState.API_ERROR
            }
            return if (previous.recentlyPlayed.isNotEmpty()) {
                previous.recentlyPlayed to SpotifyQueueState.OK
            } else {
                emptyList<SpotifyQueueTrack>() to errorState
            }
        }
        val state = when {
            fresh.httpCode == 401 || fresh.httpCode == 403 -> SpotifyQueueState.API_ERROR
            deduped.isEmpty() -> SpotifyQueueState.NO_QUEUE
            else -> SpotifyQueueState.OK
        }
        return deduped to state
    }

    /**
     * Drop the currently-playing track, collapse consecutive replays, then cap
     * to the UI's display window. The API already dedupes once but a fresh
     * collision can appear after the current-track filter pulls something out.
     */
    private fun dedupeRecent(
        tracks: List<SpotifyQueueTrack>?,
        current: NowPlayingInfo?
    ): List<SpotifyQueueTrack> {
        if (tracks.isNullOrEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<SpotifyQueueTrack>(RECENT_DISPLAY_LIMIT)
        for (track in tracks) {
            if (result.size >= RECENT_DISPLAY_LIMIT) break
            if (current != null &&
                track.title.equals(current.title, ignoreCase = true) &&
                track.artist.equals(current.artist, ignoreCase = true)
            ) {
                continue
            }
            if (!seen.add(SpotifyApiClient.dedupeKey(track))) continue
            result.add(track)
        }
        return result
    }

    private fun <T> submitOrNull(call: () -> T): java.util.concurrent.Future<T>? {
        return try {
            executor.submit(call)
        } catch (_: Exception) {
            null
        }
    }

    private fun mapQueueState(result: SpotifyApiClient.QueueResult): SpotifyQueueState {
        return when {
            result.httpCode == 429 -> SpotifyQueueState.RATE_LIMITED
            result.httpCode == 401 || result.httpCode == 403 -> SpotifyQueueState.API_ERROR
            result.httpCode == 204 -> SpotifyQueueState.NO_QUEUE
            result.tracks.isEmpty() -> SpotifyQueueState.NO_QUEUE
            else -> SpotifyQueueState.OK
        }
    }

    private fun postSnapshot(snapshot: SpotifyQueueSnapshot) {
        handler.post { SpotifyQueueCenter.update(snapshot) }
    }

    /** Per-endpoint refresh windows. */
    private data class IntervalSet(
        val queueMs: Long,
        val playerMs: Long,
        val recentMs: Long
    ) {
        companion object {
            val PLAYING = IntervalSet(queueMs = 10_000L, playerMs = 20_000L, recentMs = 60_000L)
            val IDLE = IntervalSet(queueMs = 60_000L, playerMs = 60_000L, recentMs = 120_000L)
        }
    }

    companion object {
        /** Never sleep less than this — keeps a hot-loop bug from spinning the main thread. */
        private const val MIN_DELAY_MS = 1_000L
        /** Cap so we always recheck within two minutes even if all counters drift. */
        private const val MAX_DELAY_MS = 120_000L
        private const val RECENT_DISPLAY_LIMIT = 5
    }
}
