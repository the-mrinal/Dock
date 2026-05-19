package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository that glues [SpotifyApiClient] to [SpotifyPlaylistCache] with
 * stale-while-revalidate semantics. Callers register a callback; the
 * callback receives the cached snapshot first (if any), then a fresh
 * snapshot once the network refresh completes.
 *
 * A generation counter prevents an in-flight request for playlist A from
 * overwriting the UI after the user has already drilled into playlist B.
 */
object SpotifyPlaylistRepository {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spotify-playlists").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val indexGen = AtomicInteger(0)
    private val trackGen = AtomicInteger(0)

    /**
     * Emits the cached playlists immediately (state=OK, fromCache=true) if
     * any are cached, then refreshes when the cache is stale. If nothing is
     * cached and the network fails, emits an error state.
     */
    fun loadPlaylists(context: Context, callback: (SpotifyPlaylistSnapshot) -> Unit) {
        val appCtx = context.applicationContext
        val myGen = indexGen.incrementAndGet()

        if (!SpotifyTokenStore.isConnected(appCtx)) {
            callback(SpotifyPlaylistSnapshot(state = SpotifyPlaylistBrowseState.NOT_LINKED))
            return
        }

        val cached = SpotifyPlaylistCache.getIndex()
        val needsFetch = cached == null || !cached.isFresh()

        if (cached != null && cached.value.isNotEmpty()) {
            callback(
                SpotifyPlaylistSnapshot(
                    playlists = cached.value,
                    state = SpotifyPlaylistBrowseState.OK,
                    fromCache = true
                )
            )
        } else {
            callback(SpotifyPlaylistSnapshot(state = SpotifyPlaylistBrowseState.LOADING))
        }

        if (!needsFetch) return

        executor.execute {
            val result = SpotifyApiClient.fetchPlaylists(appCtx)
            if (myGen != indexGen.get()) return@execute
            val snapshot = mapPlaylistsResult(result, cached?.value.orEmpty())
            if (snapshot.state == SpotifyPlaylistBrowseState.OK) {
                SpotifyPlaylistCache.putIndex(snapshot.playlists)
            }
            main.post {
                if (myGen != indexGen.get()) return@post
                callback(snapshot)
            }
        }
    }

    /**
     * Same flow as [loadPlaylists] but scoped to a single playlist's tracks.
     */
    fun loadTracks(
        context: Context,
        playlistId: String,
        callback: (SpotifyPlaylistTracksSnapshot) -> Unit
    ) {
        val appCtx = context.applicationContext
        val myGen = trackGen.incrementAndGet()

        if (!SpotifyTokenStore.isConnected(appCtx)) {
            callback(
                SpotifyPlaylistTracksSnapshot(
                    playlistId = playlistId,
                    state = SpotifyPlaylistBrowseState.NOT_LINKED
                )
            )
            return
        }

        val cached = SpotifyPlaylistCache.getTracks(playlistId)
        val needsFetch = cached == null || !cached.isFresh()

        if (cached != null && cached.value.isNotEmpty()) {
            callback(
                SpotifyPlaylistTracksSnapshot(
                    playlistId = playlistId,
                    tracks = cached.value,
                    state = SpotifyPlaylistBrowseState.OK,
                    fromCache = true
                )
            )
        } else {
            callback(
                SpotifyPlaylistTracksSnapshot(
                    playlistId = playlistId,
                    state = SpotifyPlaylistBrowseState.LOADING
                )
            )
        }

        if (!needsFetch) return

        executor.execute {
            val result = SpotifyApiClient.fetchPlaylistTracks(appCtx, playlistId)
            if (myGen != trackGen.get()) return@execute
            val snapshot = mapTracksResult(playlistId, result, cached?.value.orEmpty())
            if (snapshot.state == SpotifyPlaylistBrowseState.OK) {
                SpotifyPlaylistCache.putTracks(playlistId, snapshot.tracks)
            }
            main.post {
                if (myGen != trackGen.get()) return@post
                callback(snapshot)
            }
        }
    }

    private fun mapPlaylistsResult(
        result: SpotifyApiClient.PlaylistsResult,
        previousCache: List<SpotifyPlaylist>
    ): SpotifyPlaylistSnapshot {
        val code = result.httpCode
        return when {
            code in 200..299 -> {
                val state = if (result.playlists.isEmpty()) {
                    SpotifyPlaylistBrowseState.EMPTY
                } else {
                    SpotifyPlaylistBrowseState.OK
                }
                SpotifyPlaylistSnapshot(playlists = result.playlists, state = state)
            }
            code == 401 || code == 403 ->
                stalePlaylistsOr(SpotifyPlaylistBrowseState.NEEDS_REAUTH, previousCache)
            code == 429 ->
                stalePlaylistsOr(SpotifyPlaylistBrowseState.RATE_LIMITED, previousCache)
            else ->
                stalePlaylistsOr(SpotifyPlaylistBrowseState.API_ERROR, previousCache)
        }
    }

    private fun mapTracksResult(
        playlistId: String,
        result: SpotifyApiClient.PlaylistTracksResult,
        previousCache: List<SpotifyQueueTrack>
    ): SpotifyPlaylistTracksSnapshot {
        val code = result.httpCode
        return when {
            code in 200..299 -> {
                val state = if (result.tracks.isEmpty()) {
                    SpotifyPlaylistBrowseState.EMPTY
                } else {
                    SpotifyPlaylistBrowseState.OK
                }
                SpotifyPlaylistTracksSnapshot(playlistId, result.tracks, state)
            }
            code == 401 || code == 403 ->
                staleTracksOr(playlistId, SpotifyPlaylistBrowseState.NEEDS_REAUTH, previousCache)
            code == 429 ->
                staleTracksOr(playlistId, SpotifyPlaylistBrowseState.RATE_LIMITED, previousCache)
            else ->
                staleTracksOr(playlistId, SpotifyPlaylistBrowseState.API_ERROR, previousCache)
        }
    }

    /**
     * Stale-while-revalidate: if we have something in the cache, keep
     * showing it on transient errors. Only surface the error state when
     * there's nothing to fall back to.
     */
    private fun stalePlaylistsOr(
        errorState: SpotifyPlaylistBrowseState,
        previousCache: List<SpotifyPlaylist>
    ): SpotifyPlaylistSnapshot {
        return if (previousCache.isNotEmpty()) {
            SpotifyPlaylistSnapshot(
                playlists = previousCache,
                state = SpotifyPlaylistBrowseState.OK,
                fromCache = true
            )
        } else {
            SpotifyPlaylistSnapshot(state = errorState)
        }
    }

    private fun staleTracksOr(
        playlistId: String,
        errorState: SpotifyPlaylistBrowseState,
        previousCache: List<SpotifyQueueTrack>
    ): SpotifyPlaylistTracksSnapshot {
        return if (previousCache.isNotEmpty()) {
            SpotifyPlaylistTracksSnapshot(
                playlistId = playlistId,
                tracks = previousCache,
                state = SpotifyPlaylistBrowseState.OK,
                fromCache = true
            )
        } else {
            SpotifyPlaylistTracksSnapshot(playlistId = playlistId, state = errorState)
        }
    }
}
