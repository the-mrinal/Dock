package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Polls Spotify Web API for the up-next queue, recently played and active device.
 *
 * Cadence is adaptive:
 *   - Spotify actively playing on this Fire TV  -> ACTIVE_INTERVAL_MS
 *   - Spotify paused / playing on another device -> PAUSED_INTERVAL_MS
 *   - Not connected or not playing at all        -> IDLE_INTERVAL_MS
 *
 * The three sub-fetches (queue / recent / player) run in parallel on a small
 * dedicated executor so a slow Spotify endpoint never blocks the others.
 */
class SpotifyQueuePoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)

    private val pollRunnable = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, nextDelayMs())
        }
    }

    fun start() {
        stop()
        publish()
        handler.postDelayed(pollRunnable, nextDelayMs())
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
    }

    fun publishNow() {
        publish()
    }

    private fun nextDelayMs(): Long {
        if (!SpotifyTokenStore.isConnected(appContext)) {
            return IDLE_INTERVAL_MS
        }
        val info = NowPlayingCenter.current
        val spotifyHere = info != null &&
            info.hasActiveSession &&
            MediaSessionHelper.isSpotify(info.packageName)
        return when {
            spotifyHere && info!!.isPlaying -> ACTIVE_INTERVAL_MS
            spotifyHere -> PAUSED_INTERVAL_MS
            else -> IDLE_INTERVAL_MS
        }
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

        val info = NowPlayingCenter.current
        val spotifyPlaying = info != null &&
            info.hasActiveSession &&
            MediaSessionHelper.isSpotify(info.packageName)

        if (!spotifyPlaying) {
            publishNotPlayingSnapshot()
        } else {
            publishPlayingSnapshot()
        }
    }

    private fun publishNotPlayingSnapshot() {
        val recentTask = submitOrNull { SpotifyApiClient.fetchRecentlyPlayed(appContext) }
        val playerTask = submitOrNull { SpotifyApiClient.fetchPlayerState(appContext) }

        executor.execute {
            val recent = recentTask?.get()
            val player = playerTask?.get()
            postSnapshot(
                SpotifyQueueSnapshot(
                    state = SpotifyQueueState.NOT_PLAYING,
                    recentlyPlayed = dedupeRecent(recent?.tracks, current = null),
                    recentState = if (recent == null) {
                        SpotifyQueueState.API_ERROR
                    } else {
                        mapRecentState(recent)
                    },
                    activeDeviceName = player?.device?.name
                )
            )
        }
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

    private fun publishPlayingSnapshot() {
        val queueTask = submitOrNull { SpotifyApiClient.fetchQueue(appContext) }
        val recentTask = submitOrNull { SpotifyApiClient.fetchRecentlyPlayed(appContext) }
        val playerTask = submitOrNull { SpotifyApiClient.fetchPlayerState(appContext) }

        executor.execute {
            val queue = queueTask?.get()
            val recent = recentTask?.get()
            val player = playerTask?.get()

            if (queue == null && recent == null) {
                postSnapshot(
                    SpotifyQueueSnapshot(
                        state = SpotifyQueueState.API_ERROR,
                        recentState = SpotifyQueueState.API_ERROR,
                        activeDeviceName = player?.device?.name
                    )
                )
                return@execute
            }

            val current = NowPlayingCenter.current
            val recentFiltered = dedupeRecent(recent?.tracks, current)

            postSnapshot(
                SpotifyQueueSnapshot(
                    upNext = queue?.tracks?.firstOrNull(),
                    recentlyPlayed = recentFiltered,
                    state = queue?.let { mapQueueState(it) } ?: SpotifyQueueState.API_ERROR,
                    recentState = recent?.let { mapRecentState(it) } ?: SpotifyQueueState.API_ERROR,
                    activeDeviceName = player?.device?.name
                )
            )
        }
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
            result.httpCode == 401 || result.httpCode == 403 -> SpotifyQueueState.API_ERROR
            result.httpCode == 204 -> SpotifyQueueState.NO_QUEUE
            result.tracks.isEmpty() -> SpotifyQueueState.NO_QUEUE
            else -> SpotifyQueueState.OK
        }
    }

    private fun mapRecentState(result: SpotifyApiClient.QueueResult): SpotifyQueueState {
        return when {
            result.httpCode == 401 || result.httpCode == 403 -> SpotifyQueueState.API_ERROR
            result.tracks.isEmpty() -> SpotifyQueueState.NO_QUEUE
            else -> SpotifyQueueState.OK
        }
    }

    private fun postSnapshot(snapshot: SpotifyQueueSnapshot) {
        handler.post { SpotifyQueueCenter.update(snapshot) }
    }

    companion object {
        private const val ACTIVE_INTERVAL_MS = 6_000L
        private const val PAUSED_INTERVAL_MS = 30_000L
        private const val IDLE_INTERVAL_MS = 60_000L
        private const val RECENT_DISPLAY_LIMIT = 5
    }
}
