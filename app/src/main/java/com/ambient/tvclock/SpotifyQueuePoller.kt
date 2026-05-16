package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class SpotifyQueuePoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = 8_000L

    private val pollRunnable = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        stop()
        publish()
        handler.postDelayed(pollRunnable, intervalMs)
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
    }

    fun publishNow() {
        publish()
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
            thread(name = "spotify-recent") {
                val recent = try {
                    SpotifyApiClient.fetchRecentlyPlayed(appContext)
                } catch (_: Exception) {
                    null
                }
                val deviceName = SpotifyApiClient.fetchPlayerState(appContext)?.device?.name
                postSnapshot(
                    SpotifyQueueSnapshot(
                        state = SpotifyQueueState.NOT_PLAYING,
                        recentlyPlayed = recent?.tracks.orEmpty(),
                        recentState = if (recent == null) {
                            SpotifyQueueState.API_ERROR
                        } else {
                            mapRecentState(recent)
                        },
                        activeDeviceName = deviceName
                    )
                )
            }
            return
        }

        thread(name = "spotify-queue") {
            val feed = try {
                SpotifyApiClient.fetchFeed(appContext)
            } catch (_: Exception) {
                null
            }
            val deviceName = SpotifyApiClient.fetchPlayerState(appContext)?.device?.name
            if (feed == null) {
                postSnapshot(
                    SpotifyQueueSnapshot(
                        state = SpotifyQueueState.API_ERROR,
                        recentState = SpotifyQueueState.API_ERROR,
                        activeDeviceName = deviceName
                    )
                )
                return@thread
            }
            val current = NowPlayingCenter.current
            val recentFiltered = feed.recent.tracks.filterNot { track ->
                current != null &&
                    track.title.equals(current.title, ignoreCase = true) &&
                    track.artist.equals(current.artist, ignoreCase = true)
            }.take(5)

            postSnapshot(
                SpotifyQueueSnapshot(
                    upNext = feed.queue.tracks.firstOrNull(),
                    recentlyPlayed = recentFiltered,
                    state = mapQueueState(feed.queue),
                    recentState = mapRecentState(feed.recent),
                    activeDeviceName = deviceName
                )
            )
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
}
