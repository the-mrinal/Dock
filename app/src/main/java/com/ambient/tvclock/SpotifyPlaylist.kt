package com.ambient.tvclock

enum class SpotifyPlaylistBrowseState {
    LOADING,
    OK,
    EMPTY,
    NOT_LINKED,
    NEEDS_REAUTH,
    RATE_LIMITED,
    API_ERROR
}

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val imageUrl: String = "",
    val trackCount: Int = 0,
    val ownerName: String = "",
    val ownerId: String = ""
) {
    val uri: String get() = "spotify:playlist:$id"

    val isLikedSongs: Boolean get() = id == LIKED_SONGS_ID

    companion object {
        /**
         * Synthetic playlist id for the user's Liked Songs ("Saved Tracks").
         * Spotify doesn't expose Liked Songs as a real playlist via the
         * /v1/me/playlists endpoint, so we surface it as a virtual entry and
         * branch at fetch/playback time on this id.
         */
        const val LIKED_SONGS_ID = "__liked_songs__"

        /** Hosted by Spotify since at least 2019 — same image used in the
         *  official mobile apps for Liked Songs. */
        private const val LIKED_SONGS_ART =
            "https://misc.scdn.co/liked-songs/liked-songs-300.png"

        fun likedSongs(trackCount: Int): SpotifyPlaylist = SpotifyPlaylist(
            id = LIKED_SONGS_ID,
            name = "Liked Songs",
            imageUrl = LIKED_SONGS_ART,
            trackCount = trackCount,
            ownerName = "",
            ownerId = ""
        )
    }
}

data class SpotifyPlaylistSnapshot(
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val state: SpotifyPlaylistBrowseState = SpotifyPlaylistBrowseState.LOADING,
    val fromCache: Boolean = false
)

data class SpotifyPlaylistTracksSnapshot(
    val playlistId: String,
    val tracks: List<SpotifyQueueTrack> = emptyList(),
    val state: SpotifyPlaylistBrowseState = SpotifyPlaylistBrowseState.LOADING,
    val fromCache: Boolean = false
)
