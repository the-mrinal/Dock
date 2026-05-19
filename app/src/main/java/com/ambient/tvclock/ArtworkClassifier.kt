package com.ambient.tvclock

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Distinguishes real album art from the placeholder bitmaps Spotify pushes
 * during track transitions. Spotify drips metadata in two stages: a small
 * (~160×160) icon-shaped bitmap first, then the real 640×640 album art a
 * moment later. If the placeholder is allowed through, every downstream
 * binder ends up either flashing the Spotify icon or — worse — recording
 * the new track as "already blurred" and never accepting the real art that
 * follows.
 *
 * The rules below are intentionally conservative: anything below the size
 * threshold, anything whose corners are uniformly transparent, and anything
 * that's a flat fill (corners + centre all the same colour) is rejected.
 * Real album art varies across the frame and is always at least 300×300 on
 * Spotify.
 */
object ArtworkClassifier {

    private const val MIN_PX = 192
    private const val COLOR_TOLERANCE = 8

    fun looksLikeAlbumArt(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        if (bitmap.width < MIN_PX || bitmap.height < MIN_PX) return false
        val w = bitmap.width
        val h = bitmap.height
        val tl = bitmap.getPixel(2, 2)
        val tr = bitmap.getPixel(w - 3, 2)
        val bl = bitmap.getPixel(2, h - 3)
        val br = bitmap.getPixel(w - 3, h - 3)
        val center = bitmap.getPixel(w / 2, h / 2)
        // All-transparent corners ⇒ icon on a clear canvas.
        val cornerAlphas = intArrayOf(
            (tl ushr 24) and 0xff,
            (tr ushr 24) and 0xff,
            (bl ushr 24) and 0xff,
            (br ushr 24) and 0xff
        )
        if (cornerAlphas.all { it < 16 }) return false
        // All 4 corners + centre identical ⇒ flat fill, not artwork.
        val flat = areClose(tl, tr) && areClose(tl, bl) &&
            areClose(tl, br) && areClose(tl, center)
        if (flat) return false
        return true
    }

    private fun areClose(a: Int, b: Int): Boolean {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return abs(dr) <= COLOR_TOLERANCE &&
            abs(dg) <= COLOR_TOLERANCE &&
            abs(db) <= COLOR_TOLERANCE
    }
}
