package com.ambient.tvclock

import android.graphics.Bitmap
import android.widget.ImageView

/**
 * Keeps album art in sync when Spotify updates metadata or artwork after a track change.
 */
object NowPlayingArtwork {

    class State {
        var lastMediaUri: String? = null
        var lastBitmap: Bitmap? = null
    }

    fun bind(
        imageView: ImageView,
        placeholder: ImageView,
        info: NowPlayingInfo,
        state: State
    ) {
        val mediaUri = info.mediaUri.ifBlank { "${info.title}|${info.artist}|${info.album}" }
        val trackChanged = mediaUri != state.lastMediaUri
        val bitmapChanged = info.artwork != null && info.artwork !== state.lastBitmap

        if (info.artwork != null && (trackChanged || bitmapChanged)) {
            state.lastMediaUri = mediaUri
            state.lastBitmap = info.artwork
            imageView.setImageBitmap(info.artwork)
            imageView.visibility = android.view.View.VISIBLE
            placeholder.visibility = android.view.View.GONE
        } else if (trackChanged && info.artwork == null) {
            state.lastMediaUri = mediaUri
            state.lastBitmap = null
            imageView.setImageDrawable(null)
            imageView.visibility = android.view.View.GONE
            placeholder.visibility = android.view.View.VISIBLE
        }
    }

    fun reset(state: State) {
        state.lastMediaUri = null
        state.lastBitmap = null
    }
}
