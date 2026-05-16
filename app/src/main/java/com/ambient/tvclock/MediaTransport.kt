package com.ambient.tvclock

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent

object MediaTransport {

    private const val TAG = "MediaTransport"

    fun playPause(context: Context) {
        val controller = resolveController(context)
        if (controller != null) {
            try {
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "playPause session failed: ${e.message}")
            }
        }
        if (dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) return
        if (SpotifyPlaybackControl.playPause(context)) return
        Log.w(TAG, "playPause: no transport available")
    }

    fun skipToNext(context: Context) {
        when {
            trySessionTransport(context) { it.transportControls.skipToNext() } -> Unit
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) -> Unit
            SpotifyPlaybackControl.skipToNext(context) -> Unit
            else -> Log.w(TAG, "skipToNext: no transport available")
        }
    }

    fun skipToPrevious(context: Context) {
        when {
            trySessionTransport(context) { it.transportControls.skipToPrevious() } -> Unit
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) -> Unit
            SpotifyPlaybackControl.skipToPrevious(context) -> Unit
            else -> Log.w(TAG, "skipToPrevious: no transport available")
        }
    }

    fun playTrack(context: Context, uri: String) {
        if (uri.isBlank()) return
        if (SpotifyPlaybackControl.playUri(context, uri)) return
        Log.w(TAG, "playTrack failed for $uri")
    }

    private fun trySessionTransport(
        context: Context,
        action: (MediaController) -> Unit
    ): Boolean {
        val controller = resolveController(context) ?: return false
        return try {
            action(controller)
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession transport failed: ${e.message}")
            false
        }
    }

    private fun resolveController(context: Context): MediaController? {
        val controller = NowPlayingSessionReader.getBestController(context) ?: return null
        val info = MediaSessionHelper.toNowPlaying(controller)
        NowPlayingCenter.update(info, controller)
        return controller
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
        return true
    }
}
