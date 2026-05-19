package com.ambient.tvclock

/**
 * In-memory cache for the user's Spotify playlists. Two slots:
 *  - The playlist index (the user's list of playlists)
 *  - An LRU map of per-playlist track lists
 *
 * Both slots are stale-while-revalidate friendly: callers always see what's
 * cached (even expired) so the UI is instant, and the repository decides
 * whether to refresh based on [isFresh]. The cache itself never throws away
 * stale entries — eviction is purely LRU + cap.
 */
object SpotifyPlaylistCache {

    private const val INDEX_TTL_MS = 12L * 60L * 60L * 1000L // 12 hours
    private const val TRACKS_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
    private const val TRACKS_LRU_CAP = 8

    data class Entry<T>(val value: T, val storedAtMs: Long, val ttlMs: Long) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
            now - storedAtMs < ttlMs
    }

    private val lock = Any()
    private var indexEntry: Entry<List<SpotifyPlaylist>>? = null
    private val trackEntries: LinkedHashMap<String, Entry<List<SpotifyQueueTrack>>> =
        object : LinkedHashMap<String, Entry<List<SpotifyQueueTrack>>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry<List<SpotifyQueueTrack>>>
            ): Boolean = size > TRACKS_LRU_CAP
        }

    fun getIndex(): Entry<List<SpotifyPlaylist>>? = synchronized(lock) { indexEntry }

    fun putIndex(playlists: List<SpotifyPlaylist>) {
        synchronized(lock) {
            indexEntry = Entry(playlists, System.currentTimeMillis(), INDEX_TTL_MS)
        }
    }

    fun getTracks(playlistId: String): Entry<List<SpotifyQueueTrack>>? =
        synchronized(lock) { trackEntries[playlistId] }

    fun putTracks(playlistId: String, tracks: List<SpotifyQueueTrack>) {
        synchronized(lock) {
            trackEntries[playlistId] =
                Entry(tracks, System.currentTimeMillis(), TRACKS_TTL_MS)
        }
    }

    fun clear() {
        synchronized(lock) {
            indexEntry = null
            trackEntries.clear()
        }
    }
}
