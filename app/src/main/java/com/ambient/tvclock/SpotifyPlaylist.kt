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
    val ownerName: String = ""
) {
    val uri: String get() = "spotify:playlist:$id"
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
