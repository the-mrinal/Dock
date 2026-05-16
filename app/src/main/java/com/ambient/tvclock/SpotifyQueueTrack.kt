package com.ambient.tvclock

enum class SpotifyQueueState {
    NOT_LINKED,
    NOT_PLAYING,
    NO_QUEUE,
    OK,
    API_ERROR,
    RATE_LIMITED
}

data class SpotifyQueueTrack(
    val title: String,
    val artist: String,
    val imageUrl: String = "",
    val uri: String = ""
)

data class SpotifyQueueSnapshot(
    val upNext: SpotifyQueueTrack? = null,
    val recentlyPlayed: List<SpotifyQueueTrack> = emptyList(),
    val state: SpotifyQueueState = SpotifyQueueState.NOT_LINKED,
    val recentState: SpotifyQueueState = SpotifyQueueState.NOT_LINKED,
    val activeDeviceName: String? = null
)
