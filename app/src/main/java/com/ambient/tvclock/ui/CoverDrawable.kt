package com.ambient.tvclock.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.ambient.tvclock.ui.CoverPalette.Layout
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural cover Drawable matching the HTML prototype's `<Cover>` SVG.
 *
 * Eight layouts × ten palettes × per-shape RNG yield ~80 distinct visual
 * primitives, but the seed is the only knob the caller needs to tweak. The
 * deterministic palette/layout pickers live in [CoverPalette].
 *
 * Use this as the fallback when Spotify art is null (cold start, queue
 * items without artwork). The drawable scales to any bounds and the inner
 * geometry is laid out in a normalized 100×100 space so it survives both
 * 64dp queue thumbnails and 472dp NowPlaying covers without retuning.
 */
class CoverDrawable(private val seed: String) : Drawable() {

    private val palette: IntArray = CoverPalette.coverBg(seed)
    private val layout: Layout = CoverPalette.layoutFor(seed)

    private val bg: Int get() = palette[0]
    private val fg1: Int get() = palette[1]
    private val fg2: Int get() = palette[2]
    private val ink: Int get() = palette[3]

    // Pre-computed RNG values (seed extensions matched to HTML).
    private val cx: Float = CoverPalette.rnd("${seed}cx", 30f, 70f)
    private val cy: Float = CoverPalette.rnd("${seed}cy", 30f, 70f)
    private val rot: Float = CoverPalette.rnd("${seed}rot", -30f, 30f)
    private val big: Float = CoverPalette.rnd("${seed}big", 28f, 44f)
    private val split: Float = CoverPalette.rnd("${seed}sp", 35f, 65f)
    private val arcQ: Float = CoverPalette.rnd("${seed}q", -10f, 20f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpPath = Path()
    private val tmpRect = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // Scale factor — geometry is authored in a 100×100 space.
        val sx = w / 100f
        val sy = h / 100f

        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        // 1. Solid background.
        paint.shader = null
        paint.color = bg
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. Body — branch on layout.
        when (layout) {
            Layout.CIRCLE -> drawCircle(canvas, sx, sy)
            Layout.SPLIT -> drawSplit(canvas, w, h, sx, sy)
            Layout.BARS -> drawBars(canvas, sx, sy)
            Layout.ARC -> drawArc(canvas, sx, sy)
            Layout.MESH -> drawMesh(canvas, w, h, sx, sy)
            Layout.SQUARE -> drawSquare(canvas, sx, sy)
            Layout.HORIZON -> drawHorizon(canvas, w, h, sx, sy)
            Layout.RAYS -> drawRays(canvas, sx, sy)
        }

        // 3. Soft gloss + bottom shadow overlay, matches the SVG's `linear-gradient`.
        overlayPaint.shader = RadialGradient(
            w * 0.30f, 0f,
            w * 1.20f.coerceAtLeast(0.001f),
            intArrayOf(0x0FFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f),
            Shader.TileMode.CLAMP,
        )
        overlayPaint.xfermode = null
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        overlayPaint.shader = LinearGradient(
            0f, h * 0.60f, 0f, h,
            0x00000000, 0x2E000000,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        canvas.restore()
    }

    private fun drawCircle(canvas: Canvas, sx: Float, sy: Float) {
        paint.shader = null
        paint.color = fg1
        canvas.drawCircle(cx * sx, cy * sy, big * minOf(sx, sy), paint)
        paint.color = fg2
        canvas.drawCircle((100f - cx * 0.4f) * sx, (100f - cy * 0.4f) * sy, big * 0.35f * minOf(sx, sy), paint)
    }

    private fun drawSplit(canvas: Canvas, w: Float, h: Float, sx: Float, sy: Float) {
        paint.shader = null
        paint.color = fg1
        canvas.drawRect(0f, split * sy, w, h, paint)
        paint.color = fg2
        canvas.drawCircle(70f * sx, (split - 8f) * sy, 14f * minOf(sx, sy), paint)
    }

    private fun drawBars(canvas: Canvas, sx: Float, sy: Float) {
        paint.shader = null
        for (i in 0..4) {
            paint.color = if (i % 2 == 1) fg2 else fg1
            val x = (12f + i * 16f) * sx
            val y = (20f + (i % 2) * 14f) * sy
            val width = 10f * sx
            val height = (60f - (i % 2) * 14f) * sy
            canvas.drawRect(x, y, x + width, y + height, paint)
        }
    }

    private fun drawArc(canvas: Canvas, sx: Float, sy: Float) {
        // SVG: M -10 70 Q 50 (20 + q) 110 70 — quadratic Bezier across the canvas.
        tmpPath.reset()
        tmpPath.moveTo(-10f * sx, 70f * sy)
        tmpPath.quadTo(50f * sx, (20f + arcQ) * sy, 110f * sx, 70f * sy)
        strokePaint.color = fg1
        strokePaint.strokeWidth = 14f * minOf(sx, sy)
        canvas.drawPath(tmpPath, strokePaint)
        paint.shader = null
        paint.color = fg2
        canvas.drawCircle(cx * sx, (cy + 8f) * sy, 6f * minOf(sx, sy), paint)
    }

    private fun drawMesh(canvas: Canvas, w: Float, h: Float, sx: Float, sy: Float) {
        // Radial gradient (fg1 → fg2 → transparent) + accent circle.
        paint.shader = RadialGradient(
            cx * sx, cy * sy, 70f * minOf(sx, sy),
            intArrayOf(
                (fg1 and 0x00FFFFFF) or 0xD9000000.toInt(),
                (fg2 and 0x00FFFFFF) or 0x8C000000.toInt(),
                (bg and 0x00FFFFFF) or 0x00000000,
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // 0.6 opacity disc.
        paint.color = (fg2 and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawCircle((100f - cx) * sx, (100f - cy) * sy, big * 0.5f * minOf(sx, sy), paint)
        paint.alpha = 255
    }

    private fun drawSquare(canvas: Canvas, sx: Float, sy: Float) {
        canvas.save()
        canvas.rotate(rot, 50f * sx, 50f * sy)
        paint.shader = null
        paint.color = fg1
        val outerHalf = big / 2f
        canvas.drawRect(
            (50f - outerHalf) * sx, (50f - outerHalf) * sy,
            (50f + outerHalf) * sx, (50f + outerHalf) * sy,
            paint,
        )
        paint.color = fg2
        val innerHalf = big / 4f
        canvas.drawRect(
            (50f - innerHalf) * sx, (50f - innerHalf) * sy,
            (50f + innerHalf) * sx, (50f + innerHalf) * sy,
            paint,
        )
        canvas.restore()
    }

    private fun drawHorizon(canvas: Canvas, w: Float, h: Float, sx: Float, sy: Float) {
        paint.shader = null
        // Upper half: fg2 at 0.5 alpha.
        paint.color = (fg2 and 0x00FFFFFF) or 0x80000000.toInt()
        canvas.drawRect(0f, 0f, w, split * sy, paint)
        // Sun.
        paint.alpha = 255
        paint.color = fg1
        canvas.drawCircle(cx * sx, split * sy, big * 0.55f * minOf(sx, sy), paint)
        // Lower half: bg.
        paint.color = bg
        canvas.drawRect(0f, split * sy, w, h, paint)
        // 1px horizon line with 0.25 ink overlay.
        paint.color = (ink and 0x00FFFFFF) or 0x40000000.toInt()
        canvas.drawRect(0f, (split - 0.5f) * sy, w, (split + 0.5f) * sy, paint)
        paint.alpha = 255
    }

    private fun drawRays(canvas: Canvas, sx: Float, sy: Float) {
        canvas.save()
        canvas.translate(50f * sx, 50f * sy)
        canvas.rotate(rot)
        paint.shader = null
        val rayWidth = 3f * minOf(sx, sy)
        for (i in 0..5) {
            canvas.save()
            canvas.rotate(i * 30f)
            val alphaF = 0.35f + (i % 3) * 0.2f
            paint.color = (fg1 and 0x00FFFFFF) or ((alphaF * 255f).toInt() shl 24)
            canvas.drawRect(-rayWidth / 2f, -46f * sy, rayWidth / 2f, 46f * sy, paint)
            canvas.restore()
        }
        paint.alpha = 255
        paint.color = fg2
        canvas.drawCircle(0f, 0f, big * 0.35f * minOf(sx, sy), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
