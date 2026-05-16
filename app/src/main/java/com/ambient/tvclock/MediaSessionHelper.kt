package com.ambient.tvclock

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState

object MediaSessionHelper {

    val spotifyPackages = setOf(
        "com.spotify.tv.android",
        "com.spotify.music",
        "com.spotify.lite"
    )

    fun toNowPlaying(controller: MediaController): NowPlayingInfo? {
        val metadata = controller.metadata ?: return null
        val description = metadata.description

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim()
            ?: description.title?.toString()?.trim()
            ?: ""

        if (title.isEmpty()) {
            return null
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim()
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.trim()
            ?: description.subtitle?.toString()?.trim()
            ?: ""

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()
            ?: description.description?.toString()?.trim()
            ?: ""

        val artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: description.iconBitmap

        val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.trim()
            ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
            ?: description.mediaUri?.toString()?.trim()
            ?: ""

        val playback = controller.playbackState
        val isPlaying = isActivelyPlaying(controller)
        val isPaused = playback?.state == PlaybackState.STATE_PAUSED

        return NowPlayingInfo(
            title = title,
            artist = artist,
            album = album,
            artwork = artwork,
            isPlaying = isPlaying,
            isPaused = isPaused,
            packageName = controller.packageName,
            mediaUri = mediaUri,
            canSkipNext = canPerformAction(playback, PlaybackState.ACTION_SKIP_TO_NEXT),
            canSkipPrevious = canPerformAction(playback, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canPlay = canPerformAction(playback, PlaybackState.ACTION_PLAY),
            canPause = canPerformAction(playback, PlaybackState.ACTION_PAUSE)
        )
    }

    fun isActivelyPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState ?: return false
        return when (state.state) {
            PlaybackState.STATE_PLAYING -> {
                if (isSpotify(controller.packageName)) {
                    true
                } else {
                    state.position >= 0 && state.playbackSpeed > 0f
                }
            }
            PlaybackState.STATE_BUFFERING -> true
            else -> false
        }
    }

    fun isDisplayableSession(controller: MediaController): Boolean {
        if (controller.metadata == null) {
            return false
        }
        return when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING -> true
            else -> false
        }
    }

    fun isSpotify(packageName: String): Boolean = packageName in spotifyPackages

    fun pickBestController(controllers: List<MediaController>): MediaController? {
        val candidates = controllers.filter { isDisplayableSession(it) }
        if (candidates.isEmpty()) {
            return null
        }
        return candidates.sortedWith(
            compareByDescending<MediaController> { isActivelyPlaying(it) }
                .thenByDescending { isSpotify(it.packageName) }
                .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }
        ).first()
    }

    private fun canPerformAction(state: PlaybackState?, action: Long): Boolean {
        return state != null && state.actions and action != 0L
    }
}
