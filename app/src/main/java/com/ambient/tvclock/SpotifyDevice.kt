package com.ambient.tvclock

data class SpotifyDevice(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val type: String
)

data class SpotifyPlayerState(
    val device: SpotifyDevice?,
    val isPlaying: Boolean
)
