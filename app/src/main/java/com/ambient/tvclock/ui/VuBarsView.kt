package com.ambient.tvclock.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.use
import com.ambient.tvclock.R
import kotlin.math.PI
import kotlin.math.cos

/**
 * Four animated VU-meter bars matching the HTML's `Ic.Bars` SVG.
 *
 * Bar parameters (height range, base Y, duration) are picked to mirror the
 * SVG's `<animate>` keyframes:
 *   bar 0:  9 → 3 (px),  duration 1.10s
 *   bar 1: 13 → 5,        duration 0.90s
 *   bar 2: 11 → 4,        duration 1.30s
 *   bar 3: 14 → 6,        duration 1.00s
 *
 * Authored in the SVG's 24×24 viewBox; we scale to whatever bounds we're
 * given so the same `VuBarsView` works in NowPlaying (large) and inline
 * eyebrows (16dp).
 */
class VuBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var barColor: Int = 0xFFC8F25C.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val rectBuf = RectF()
    private var animator: ValueAnimator? = null

    private data class Bar(
        val xVb: Float,     // x in 24-vb units
        val widthVb: Float, // width in 24-vb units
        val baseYVb: Float, // top y when at FULL extent
        val maxHeightVb: Float,
        val minHeightVb: Float,
        val durationMs: Long,
    )

    private val bars = listOf(
        Bar(xVb = 4f,  widthVb = 2.5f, baseYVb = 11f, maxHeightVb = 9f,  minHeightVb = 3f, durationMs = 1100L),
        Bar(xVb = 9f,  widthVb = 2.5f, baseYVb = 7f,  maxHeightVb = 13f, minHeightVb = 5f, durationMs = 900L),
        Bar(xVb = 14f, widthVb = 2.5f, baseYVb = 9f,  maxHeightVb = 11f, minHeightVb = 4f, durationMs = 1300L),
        Bar(xVb = 19f, widthVb = 2.5f, baseYVb = 6f,  maxHeightVb = 14f, minHeightVb = 6f, durationMs = 1000L),
    )

    private var startTime: Long = 0L

    init {
        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.VuBarsView, defStyleAttr, 0).use { ta ->
                barColor = ta.getColor(R.styleable.VuBarsView_barColor, barColor)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimator()
    }

    /** Pause animation (e.g. when the row scrolls offscreen). */
    fun stop() {
        stopAnimator()
    }

    /** Resume animation. */
    fun start() {
        startAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        startTime = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sx = w / 24f
        val sy = h / 24f
        val now = SystemClock.uptimeMillis()
        val corner = 1f * sx

        for (b in bars) {
            // SVG keyframe goes h_max → h_min → h_max linearly over the duration.
            // A cosine triangle wave matches that shape with smoother corners.
            val t = (((now - startTime).toFloat() % b.durationMs) / b.durationMs).coerceIn(0f, 1f)
            val ease = 0.5f - 0.5f * cos(t * 2f * PI.toFloat())
            val height = b.maxHeightVb + (b.minHeightVb - b.maxHeightVb) * ease
            // Top Y in vb space follows the same triangle wave so the bottom stays put.
            val topYVb = b.baseYVb + (b.maxHeightVb - height)

            val left = b.xVb * sx
            val right = (b.xVb + b.widthVb) * sx
            val top = topYVb * sy
            val bottom = (topYVb + height) * sy
            rectBuf.set(left, top, right, bottom)
            canvas.drawRoundRect(rectBuf, corner, corner, paint)
        }
    }
}
