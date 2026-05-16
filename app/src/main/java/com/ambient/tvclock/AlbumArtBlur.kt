package com.ambient.tvclock

import android.graphics.Bitmap
import kotlin.math.max

object AlbumArtBlur {

    private const val DOWNSCALE = 12
    private const val MAX_OUTPUT = 480

    /**
     * Fast ambient blur: downscale then upscale with bilinear filtering.
     *
     * We bound the output size so we never allocate a huge bitmap just because the
     * source artwork was 1024×1024. The intermediate small bitmap is recycled.
     */
    fun blur(source: Bitmap): Bitmap {
        val downW = max(1, source.width / DOWNSCALE)
        val downH = max(1, source.height / DOWNSCALE)
        val small = Bitmap.createScaledBitmap(source, downW, downH, true)

        val ratio = source.width.toFloat() / source.height.toFloat()
        val outW: Int
        val outH: Int
        if (source.width >= source.height) {
            outW = source.width.coerceAtMost(MAX_OUTPUT)
            outH = (outW / ratio).toInt().coerceAtLeast(1)
        } else {
            outH = source.height.coerceAtMost(MAX_OUTPUT)
            outW = (outH * ratio).toInt().coerceAtLeast(1)
        }

        val out = Bitmap.createScaledBitmap(small, outW, outH, true)
        if (small !== out && !small.isRecycled) {
            small.recycle()
        }
        return out
    }
}
