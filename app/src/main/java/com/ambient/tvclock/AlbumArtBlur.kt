package com.ambient.tvclock

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AlbumArtBlur {

    // Music page panel: heavy color-wash look (small panel area, more blur reads
    // better than detail).
    private const val PANEL_MIN_DIMENSION = 24
    private const val PANEL_MAX_OUTPUT = 480
    private const val PANEL_BLUR_RADIUS = 28

    // Home full-screen background: lighter, recognisable wash. We pyramid the
    // bitmap down to a small mip, run a real box blur, then pyramid back up
    // with bilinear filtering at every step so the upscale never reveals
    // pixel-grid edges. Output is large enough to survive centerCrop to 1080p
    // / 4K without obvious blockiness.
    private const val BACKGROUND_MIN_DIMENSION = 96
    private const val BACKGROUND_MAX_OUTPUT = 1280
    private const val BACKGROUND_BLUR_RADIUS = 12

    /**
     * Heavy "color wash" blur used by the Music page now-playing panel.
     */
    fun blur(source: Bitmap): Bitmap =
        pyramidBlur(source, PANEL_MIN_DIMENSION, PANEL_MAX_OUTPUT, PANEL_BLUR_RADIUS)

    /**
     * Smooth, recognisable wash for the home full-screen ambient background.
     * Three repeated box-blur passes approximate a Gaussian, so colours blend
     * across album-art edges and the bitmap survives the centerCrop upscale to
     * the TV's native resolution without showing pixelation.
     */
    fun blurForBackground(source: Bitmap): Bitmap =
        pyramidBlur(source, BACKGROUND_MIN_DIMENSION, BACKGROUND_MAX_OUTPUT, BACKGROUND_BLUR_RADIUS)

    /**
     * Pipeline:
     *   1. Halve the bitmap repeatedly with bilinear filtering until its
     *      shortest side is ~minDimension. Halving (rather than one big jump)
     *      keeps the colour averaging smooth and avoids the aliasing that a
     *      single 1/N downscale produces.
     *   2. Run a 3-pass box blur on the small bitmap. Three boxes of radius r
     *      closely approximate a Gaussian of sigma ~= r * sqrt(3/12 * 3).
     *   3. Bilinearly upscale back to the target size in halving steps. Each
     *      doubling smears any remaining pixel edges, so the final bitmap
     *      reads as a continuous wash rather than a blocky grid.
     */
    private fun pyramidBlur(
        source: Bitmap,
        minDimension: Int,
        maxOutput: Int,
        radius: Int
    ): Bitmap {
        val small = pyramidDown(source, minDimension)
        val blurred = if (radius > 0) boxBlur3(small, radius) else small
        if (blurred !== small && !small.isRecycled) small.recycle()

        val output = pyramidUp(blurred, source.width, source.height, maxOutput)
        if (output !== blurred && !blurred.isRecycled) blurred.recycle()
        return output
    }

    private fun pyramidDown(source: Bitmap, minDimension: Int): Bitmap {
        var current = source
        var w = source.width
        var h = source.height
        var first = true
        while (min(w, h) > minDimension * 2) {
            val nextW = max(1, w / 2)
            val nextH = max(1, h / 2)
            val next = Bitmap.createScaledBitmap(current, nextW, nextH, true)
            if (!first && current !== source && !current.isRecycled) {
                current.recycle()
            }
            current = next
            w = nextW
            h = nextH
            first = false
        }

        if (min(w, h) > minDimension) {
            val ratio = w.toFloat() / h.toFloat()
            val targetW: Int
            val targetH: Int
            if (w <= h) {
                targetW = minDimension
                targetH = max(1, (minDimension / ratio).toInt())
            } else {
                targetH = minDimension
                targetW = max(1, (minDimension * ratio).toInt())
            }
            val next = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (current !== source && !current.isRecycled) {
                current.recycle()
            }
            current = next
        }

        if (current === source) {
            // Source was already small enough; copy so callers can freely recycle.
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) ?: source
        }
        return current
    }

    private fun pyramidUp(
        source: Bitmap,
        originalW: Int,
        originalH: Int,
        maxOutput: Int
    ): Bitmap {
        val ratio = originalW.toFloat() / originalH.toFloat()
        val targetW: Int
        val targetH: Int
        if (originalW >= originalH) {
            targetW = min(originalW, maxOutput)
            targetH = max(1, (targetW / ratio).toInt())
        } else {
            targetH = min(originalH, maxOutput)
            targetW = max(1, (targetH * ratio).toInt())
        }

        var current = source
        var w = source.width
        var h = source.height
        while (w * 2 < targetW || h * 2 < targetH) {
            val nextW = min(targetW, w * 2)
            val nextH = min(targetH, h * 2)
            val next = Bitmap.createScaledBitmap(current, nextW, nextH, true)
            if (current !== source && !current.isRecycled) current.recycle()
            current = next
            w = nextW
            h = nextH
            if (w >= targetW && h >= targetH) break
        }

        if (w != targetW || h != targetH) {
            val next = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (current !== source && !current.isRecycled) current.recycle()
            current = next
        }

        if (current === source) {
            // Already at target; produce a mutable copy so the caller owns it.
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) ?: source
        }
        return current
    }

    /**
     * Three-pass box blur. Three boxes ≈ Gaussian (per Wells, 1986); cheap and
     * artefact-free on small bitmaps. Operates per-channel in row then column.
     */
    private fun boxBlur3(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = IntArray(pixels.size)

        // Distribute the equivalent gaussian sigma across three box passes.
        val sigma = radius.toFloat()
        val boxes = boxesForGauss(sigma, 3)
        for (b in boxes) {
            val r = ((b - 1) / 2).coerceAtLeast(1)
            boxBlurHorizontal(pixels, temp, width, height, r)
            boxBlurVertical(temp, pixels, width, height, r)
        }

        val out = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt((12.0 * sigma * sigma / n) + 1.0)
        var wl = wIdeal.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal =
            (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = Math.round(mIdeal).toInt()
        return IntArray(n) { i -> if (i < m) wl else wu }
    }

    private fun boxBlurHorizontal(
        src: IntArray, dst: IntArray,
        w: Int, h: Int, r: Int
    ) {
        val inv = 1f / (r + r + 1)
        for (y in 0 until h) {
            var rowStart = y * w
            var ti = rowStart
            var li = rowStart
            var ri = rowStart + r
            val firstPx = src[rowStart]
            val fa = (firstPx ushr 24) and 0xff
            val fr = (firstPx ushr 16) and 0xff
            val fg = (firstPx ushr 8) and 0xff
            val fb = firstPx and 0xff
            val lastPx = src[rowStart + w - 1]
            val la = (lastPx ushr 24) and 0xff
            val lr = (lastPx ushr 16) and 0xff
            val lg = (lastPx ushr 8) and 0xff
            val lb = lastPx and 0xff
            var aSum = (r + 1) * fa
            var rSum = (r + 1) * fr
            var gSum = (r + 1) * fg
            var bSum = (r + 1) * fb
            for (i in 0 until r) {
                val p = src[rowStart + i]
                aSum += (p ushr 24) and 0xff
                rSum += (p ushr 16) and 0xff
                gSum += (p ushr 8) and 0xff
                bSum += p and 0xff
            }
            for (i in 0..r) {
                val p = src[ri++]
                aSum += ((p ushr 24) and 0xff) - fa
                rSum += ((p ushr 16) and 0xff) - fr
                gSum += ((p ushr 8) and 0xff) - fg
                bSum += (p and 0xff) - fb
                dst[ti++] = pack(aSum, rSum, gSum, bSum, inv)
            }
            for (i in r + 1 until w - r) {
                val pn = src[ri++]
                val po = src[li++]
                aSum += ((pn ushr 24) and 0xff) - ((po ushr 24) and 0xff)
                rSum += ((pn ushr 16) and 0xff) - ((po ushr 16) and 0xff)
                gSum += ((pn ushr 8) and 0xff) - ((po ushr 8) and 0xff)
                bSum += (pn and 0xff) - (po and 0xff)
                dst[ti++] = pack(aSum, rSum, gSum, bSum, inv)
            }
            for (i in w - r until w) {
                val po = src[li++]
                aSum += la - ((po ushr 24) and 0xff)
                rSum += lr - ((po ushr 16) and 0xff)
                gSum += lg - ((po ushr 8) and 0xff)
                bSum += lb - (po and 0xff)
                dst[ti++] = pack(aSum, rSum, gSum, bSum, inv)
            }
        }
    }

    private fun boxBlurVertical(
        src: IntArray, dst: IntArray,
        w: Int, h: Int, r: Int
    ) {
        val inv = 1f / (r + r + 1)
        for (x in 0 until w) {
            var ti = x
            var li = x
            var ri = x + r * w
            val firstPx = src[x]
            val fa = (firstPx ushr 24) and 0xff
            val fr = (firstPx ushr 16) and 0xff
            val fg = (firstPx ushr 8) and 0xff
            val fb = firstPx and 0xff
            val lastPx = src[x + (h - 1) * w]
            val la = (lastPx ushr 24) and 0xff
            val lr = (lastPx ushr 16) and 0xff
            val lg = (lastPx ushr 8) and 0xff
            val lb = lastPx and 0xff
            var aSum = (r + 1) * fa
            var rSum = (r + 1) * fr
            var gSum = (r + 1) * fg
            var bSum = (r + 1) * fb
            for (i in 0 until r) {
                val p = src[x + i * w]
                aSum += (p ushr 24) and 0xff
                rSum += (p ushr 16) and 0xff
                gSum += (p ushr 8) and 0xff
                bSum += p and 0xff
            }
            for (i in 0..r) {
                val p = src[ri]; ri += w
                aSum += ((p ushr 24) and 0xff) - fa
                rSum += ((p ushr 16) and 0xff) - fr
                gSum += ((p ushr 8) and 0xff) - fg
                bSum += (p and 0xff) - fb
                dst[ti] = pack(aSum, rSum, gSum, bSum, inv)
                ti += w
            }
            for (i in r + 1 until h - r) {
                val pn = src[ri]; ri += w
                val po = src[li]; li += w
                aSum += ((pn ushr 24) and 0xff) - ((po ushr 24) and 0xff)
                rSum += ((pn ushr 16) and 0xff) - ((po ushr 16) and 0xff)
                gSum += ((pn ushr 8) and 0xff) - ((po ushr 8) and 0xff)
                bSum += (pn and 0xff) - (po and 0xff)
                dst[ti] = pack(aSum, rSum, gSum, bSum, inv)
                ti += w
            }
            for (i in h - r until h) {
                val po = src[li]; li += w
                aSum += la - ((po ushr 24) and 0xff)
                rSum += lr - ((po ushr 16) and 0xff)
                gSum += lg - ((po ushr 8) and 0xff)
                bSum += lb - (po and 0xff)
                dst[ti] = pack(aSum, rSum, gSum, bSum, inv)
                ti += w
            }
        }
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int, inv: Float): Int {
        val ai = (a * inv).toInt().coerceIn(0, 255)
        val ri = (r * inv).toInt().coerceIn(0, 255)
        val gi = (g * inv).toInt().coerceIn(0, 255)
        val bi = (b * inv).toInt().coerceIn(0, 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
