package com.ambient.tvclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Lightweight audio-style waveform that doubles as a playback progress bar.
 *
 * A single sine-wave path is pre-built once on size change, then drawn twice with
 * clipping so the "played" portion is bright and the rest is dim — same idea as
 * Apple Music / Pocket Casts waveform scrubbers but cheap to render at 60 fps on
 * a Fire TV.
 */
class WaveformProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val amplitudeDp = 5f
    private val wavelengthDp = 28f
    private val strokeDp = 2f

    private val wavePath = Path()

    private val paintPlayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokeDp * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintUnplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokeDp * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Progress in [0, 1]. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    fun setProgress(position: Long, duration: Long) {
        progress = if (duration > 0L) position.toFloat() / duration.toFloat() else 0f
    }

    fun setPlayedColor(color: Int) {
        paintPlayed.color = color
        invalidate()
    }

    fun setUnplayedColor(color: Int) {
        paintUnplayed.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildWavePath()
    }

    private fun rebuildWavePath() {
        wavePath.reset()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerY = h / 2f
        val amplitudePx = (amplitudeDp * density).coerceAtMost(centerY - strokeDp * density)
        val wavelengthPx = (wavelengthDp * density).coerceAtLeast(8f)
        // Step small enough that even short wavelengths look smooth on TV.
        val step = (wavelengthPx / 16f).coerceAtLeast(1.5f)

        wavePath.moveTo(0f, centerY)
        var x = 0f
        while (x <= w) {
            val y = centerY + amplitudePx * sin(2.0 * PI * x / wavelengthPx).toFloat()
            wavePath.lineTo(x, y)
            x += step
        }
        if (x - step < w) {
            val y = centerY + amplitudePx * sin(2.0 * PI * w / wavelengthPx).toFloat()
            wavePath.lineTo(w, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (wavePath.isEmpty) rebuildWavePath()

        val splitX = w * progress

        canvas.save()
        canvas.clipRect(0f, 0f, splitX, h)
        canvas.drawPath(wavePath, paintPlayed)
        canvas.restore()

        canvas.save()
        canvas.clipRect(splitX, 0f, w, h)
        canvas.drawPath(wavePath, paintUnplayed)
        canvas.restore()
    }
}
