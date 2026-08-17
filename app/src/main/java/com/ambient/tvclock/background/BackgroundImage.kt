package com.ambient.tvclock.background

import com.ambient.tvclock.NowPlayingInfo

/**
 * What a [BackgroundSource] hands the surface to paint.
 *
 * Two shapes, because the two kinds of background genuinely arrive
 * differently: album art comes through MediaSession as a bitmap already in
 * memory, while everything else is an image the surface fetches for itself.
 * Modelling that honestly is what lets [BackgroundSource] cover *every*
 * background rather than only the remote ones.
 */
sealed interface BackgroundImage {

    /**
     * An image at a URI. `http(s)://` is fetched over the network;
     * `file://` is read from disk, which is how a cached wallpaper keeps
     * showing while the dock is offline.
     *
     * [key] is the dedupe key: the surface skips re-decoding when it matches
     * what is already on screen. Content-addressed sources should use their
     * hash so a same-named-but-changed image still repaints.
     */
    data class Remote(
        val uri: String,
        val key: String = uri,
        val credit: Credit? = null,
    ) : BackgroundImage

    /** The current track's artwork, blurred into a wash behind the dashboard. */
    data class AlbumArt(val info: NowPlayingInfo?) : BackgroundImage

    /** Attribution some sources are required to display (Unsplash's ToS). */
    data class Credit(
        val name: String,
        val description: String,
        val link: String,
    )
}
