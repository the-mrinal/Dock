package com.ambient.tvclock

import android.graphics.Bitmap

data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val packageName: String,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean
) {
    val hasActiveSession: Boolean = isPlaying || isPaused
}
