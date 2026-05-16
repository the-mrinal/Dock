package com.ambient.tvclock

import android.content.Context
import android.media.session.PlaybackState

object MediaTransport {

    fun playPause(context: Context) {
        val controller = resolveController(context) ?: return
        val transport = controller.transportControls
        val state = controller.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            transport.pause()
        } else {
            transport.play()
        }
    }

    fun skipToNext(context: Context) {
        resolveController(context)?.transportControls?.skipToNext()
    }

    fun skipToPrevious(context: Context) {
        resolveController(context)?.transportControls?.skipToPrevious()
    }

    private fun resolveController(context: Context): android.media.session.MediaController? {
        return NowPlayingCenter.activeController
            ?: NowPlayingSessionReader.getBestController(context)
    }
}
