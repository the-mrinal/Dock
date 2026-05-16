package com.ambient.tvclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * A plain horizontal playback progress bar.
 *
 * The played portion is Spotify green, the rest is a dim track. Between
 * metadata updates we extrapolate the playhead from
 * `lastPositionUpdateTime + playbackSpeed`, so the green region glides
 * smoothly toward the end of the track instead of stepping in chunks.
 */
class PlaybackProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackThicknessDp = 3f

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
    }
    private val paintProgress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1DB954.toInt()
        style = Paint.Style.FILL
    }

    private var trackPositionMs: Long = 0L
    private var trackDurationMs: Long = 0L
    private var playbackSpeed: Float = 1f
    private var anchorElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    private var isPlaying: Boolean = false

    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        invalidate()
        scheduleNextFrameIfNeeded()
    }

    private val rect = RectF()

    fun setPlayback(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        speed: Float
    ) {
        trackPositionMs = positionMs.coerceAtLeast(0L)
        trackDurationMs = durationMs.coerceAtLeast(0L)
        playbackSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        anchorElapsedRealtimeMs = SystemClock.elapsedRealtime()
        isPlaying = playing && trackDurationMs > 0L
        scheduleNextFrameIfNeeded()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNextFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    private fun scheduleNextFrameIfNeeded() {
        // Only animate while playing — paused tracks render once and stop.
        if (!isAttachedToWindow || frameScheduled || !isPlaying) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val thicknessPx = (trackThicknessDp * density).coerceAtMost(h)
        val top = (h - thicknessPx) / 2f
        val bottom = top + thicknessPx
        val radius = thicknessPx / 2f

        rect.set(0f, top, w, bottom)
        canvas.drawRoundRect(rect, radius, radius, paintTrack)

        if (trackDurationMs <= 0L) return
        val nowMs = SystemClock.elapsedRealtime()
        val livePosition = if (isPlaying) {
            val deltaMs = (nowMs - anchorElapsedRealtimeMs).coerceAtLeast(0L)
            trackPositionMs + (deltaMs * playbackSpeed).toLong()
        } else {
            trackPositionMs
        }.coerceIn(0L, trackDurationMs)
        val playedX = w * (livePosition.toFloat() / trackDurationMs.toFloat())
        if (playedX > 0f) {
            rect.set(0f, top, playedX, bottom)
            canvas.drawRoundRect(rect, radius, radius, paintProgress)
        }
    }
}
