package com.ambient.tvclock.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.R
import kotlin.math.min

/**
 * Two concentric pulse rings + a centre dot, matching the HTML's `dockPulse`
 * keyframe (`scale 1 → 3.2`, `opacity 0.7 → 0`, 2.2s loop, second ring lags
 * by 1.1s). Used by Connect-active halos and the LAN listening indicator.
 *
 * The View draws nothing while [isPulsing] is false except the centre dot,
 * so it's safe to keep mounted on idle screens.
 */
class PulseHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var ringColor: Int = 0xFF5AC8FA.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** Centre dot radius (px). Defaults to 11dp (22dp diameter, matching HTML). */
    var dotRadiusPx: Float = DEFAULT_DOT_DP * resources.displayMetrics.density

    /** When true the pulse animator runs. Set to false to freeze the halo. */
    var isPulsing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimator() else stopAnimator()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var startTime: Long = 0L
    private var animator: ValueAnimator? = null

    init {
        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.PulseHaloView, defStyleAttr, 0).use { ta ->
                ringColor = ta.getColor(R.styleable.PulseHaloView_pulseColor, ringColor)
                dotRadiusPx = ta.getDimension(R.styleable.PulseHaloView_pulseDotRadius, dotRadiusPx)
                if (ta.getBoolean(R.styleable.PulseHaloView_pulseAutoStart, false)) {
                    isPulsing = true
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPulsing) startAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        startTime = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Inner ring base radius starts at dotRadius and grows to 3.2× the smaller half-extent.
        val baseRadius = dotRadiusPx
        val maxRadius = min(width, height) / 2f

        // Centre dot — always painted.
        dotPaint.color = ringColor
        canvas.drawCircle(cx, cy, dotRadiusPx, dotPaint)

        if (!isPulsing) return

        val now = SystemClock.uptimeMillis()
        val phase1 = ((now - startTime).toFloat() % PULSE_DURATION_MS) / PULSE_DURATION_MS
        // Second ring lags by half the loop (1.1s of 2.2s).
        val phase2 = ((phase1 + 0.5f) % 1f)

        drawRing(canvas, cx, cy, phase1, baseRadius, maxRadius)
        drawRing(canvas, cx, cy, phase2, baseRadius, maxRadius)
    }

    private fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        phase: Float,
        baseRadius: Float,
        maxRadius: Float,
    ) {
        // HTML keyframe: scale 1 → 3.2, opacity 0.7 → 0 linearly.
        val r = baseRadius + (maxRadius - baseRadius) * phase
        val alpha = ((1f - phase) * 0.7f).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        ringPaint.color = ColorUtils.setAlphaComponent(ringColor, (alpha * 255f).toInt())
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    companion object {
        private const val PULSE_DURATION_MS = 2_200L
        private const val DEFAULT_DOT_DP = 11f
    }
}
