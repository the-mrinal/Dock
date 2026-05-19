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
    val uri: String = "",
    /**
     * The playlist/album/artist URI this track belongs to, if known. When
     * non-blank, clicking the track plays it inside this context so playback
     * continues past it. Blank means we only have an isolated track URI and
     * must fall back to single-track play.
     */
    val contextUri: String = ""
)

data class SpotifyQueueSnapshot(
    val upNext: SpotifyQueueTrack? = null,
    val recentlyPlayed: List<SpotifyQueueTrack> = emptyList(),
    val state: SpotifyQueueState = SpotifyQueueState.NOT_LINKED,
    val recentState: SpotifyQueueState = SpotifyQueueState.NOT_LINKED,
    val activeDeviceName: String? = null,
    /**
     * The Spotify "context" (playlist/album/artist URI) the current session
     * is playing from. The queue endpoint doesn't tag individual upcoming
     * tracks with their context, so we capture it once from the player state
     * and stamp it onto [upNext] before publishing.
     */
    val activeContextUri: String? = null
)
