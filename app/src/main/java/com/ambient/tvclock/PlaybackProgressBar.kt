package com.ambient.tvclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Music NowPlaying playback bar.
 *
 * Restyled for the Dock redesign (Stream A) to match the HTML prototype:
 * a 6dp pill-shaped track, a 16dp accent thumb at the playhead with a 4dp
 * soft halo. The played portion is filled with the page accent (chartreuse
 * by default). Between metadata updates we extrapolate the playhead from
 * `lastPositionUpdateTime + playbackSpeed` so the green region glides
 * smoothly toward the end of the track instead of stepping in chunks.
 *
 * Set [accentColor] to flip the fill / thumb (used by the empty-state +
 * device picker which both inherit the page accent token).
 */
class PlaybackProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** Track thickness — 6dp per HTML spec. */
    private val trackThicknessPx = 6f * density

    /** Thumb diameter — 16dp per HTML spec, with a 4dp halo painted at 0x33 alpha. */
    private val thumbDiameterPx = 16f * density
    private val thumbRadiusPx = thumbDiameterPx / 2f
    private val thumbHaloPx = 4f * density

    private val accentDefault = ContextCompat.getColor(context, R.color.dock_accent_chartreuse)

    /** Page accent. Drives both fill + thumb. */
    var accentColor: Int = accentDefault
        set(value) {
            field = value
            paintProgress.color = value
            paintThumb.color = value
            paintThumbHalo.color = ColorUtils.setAlphaComponent(value, 0x33)
            invalidate()
        }

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF // rgba(255,255,255,0.10) — matches T.borderStrong-adjacent
        style = Paint.Style.FILL
    }
    private val paintProgress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentDefault
        style = Paint.Style.FILL
    }
    private val paintThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentDefault
        style = Paint.Style.FILL
    }
    private val paintThumbHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(accentDefault, 0x33)
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

        // We need vertical room for: 6dp track + a 16dp + 4dp halo thumb stuck
        // on top. The widget's parent gives us at least 24dp; centre everything
        // on the track's midline.
        val centerY = h / 2f
        val trackTop = centerY - trackThicknessPx / 2f
        val trackBottom = centerY + trackThicknessPx / 2f
        val trackRadius = trackThicknessPx / 2f

        // 1. Background pill — full width.
        rect.set(0f, trackTop, w, trackBottom)
        canvas.drawRoundRect(rect, trackRadius, trackRadius, paintTrack)

        if (trackDurationMs <= 0L) return

        // 2. Computed playhead. Smooth-glide via elapsedRealtime extrapolation.
        val nowMs = SystemClock.elapsedRealtime()
        val livePosition = if (isPlaying) {
            val deltaMs = (nowMs - anchorElapsedRealtimeMs).coerceAtLeast(0L)
            trackPositionMs + (deltaMs * playbackSpeed).toLong()
        } else {
            trackPositionMs
        }.coerceIn(0L, trackDurationMs)
        val fraction = livePosition.toFloat() / trackDurationMs.toFloat()
        // Inset by thumb radius so the thumb never visually wanders off the edge.
        val usableW = (w - thumbDiameterPx).coerceAtLeast(0f)
        val playedX = thumbRadiusPx + usableW * fraction

        // 3. Played pill.
        if (playedX > 0f) {
            rect.set(0f, trackTop, playedX, trackBottom)
            canvas.drawRoundRect(rect, trackRadius, trackRadius, paintProgress)
        }

        // 4. Thumb halo + thumb on the playhead.
        canvas.drawCircle(playedX, centerY, thumbRadiusPx + thumbHaloPx, paintThumbHalo)
        canvas.drawCircle(playedX, centerY, thumbRadiusPx, paintThumb)
    }
}
