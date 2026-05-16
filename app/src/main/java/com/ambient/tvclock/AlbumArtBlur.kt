package com.ambient.tvclock

import android.graphics.Bitmap
import kotlin.math.max

object AlbumArtBlur {

    /** Fast ambient blur: downscale then upscale with filtering. */
    fun blur(source: Bitmap): Bitmap {
        val w = max(1, source.width / 10)
        val h = max(1, source.height / 10)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, true)
    }
}
