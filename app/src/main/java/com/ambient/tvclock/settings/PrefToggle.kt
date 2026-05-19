package com.ambient.tvclock.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.R

/**
 * Rounded-rect switch styled to the HTML `Toggle` spec.
 *
 * Off:
 * - 52×30dp pill, background `rgba(255,255,255,0.18)`
 * - 24dp white knob on the left
 *
 * On:
 * - solid accent (default `#FFFFFF`) pill
 * - 24dp dark knob on the right
 *
 * Knob slide animates 150ms with cubic-bezier(.2,.7,.3,1) — same easing as the
 * focus animation, so toggles feel like they're part of the focus model.
 */
class PrefToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    /** Accent color of the "on" track. Defaults to white (HTML default). */
    var accentColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private var animated: Float = 0f // 0f = off, 1f = on
    private var slideAnimator: ValueAnimator? = null

    var isOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            slideTo(if (value) 1f else 0f)
        }

    /** Set without animating — used during initial XML inflate. */
    fun setOnImmediate(value: Boolean) {
        isOn = value
        slideAnimator?.cancel()
        animated = if (value) 1f else 0f
        invalidate()
    }

    init {
        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.PrefToggle, defStyleAttr, 0).use { ta ->
                accentColor = ta.getColor(R.styleable.PrefToggle_toggleAccent, accentColor)
                setOnImmediate(ta.getBoolean(R.styleable.PrefToggle_toggleOn, false))
            }
        }
    }

    private fun slideTo(target: Float) {
        slideAnimator?.cancel()
        slideAnimator = ValueAnimator.ofFloat(animated, target).apply {
            duration = ANIM_MS
            interpolator = EASE
            addUpdateListener {
                animated = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((52 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((30 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        // Track. Off = rgba(255,255,255,0.18); On = accent.
        val offColor = ColorUtils.setAlphaComponent(Color.WHITE, 0x2E) // ~0.18
        trackPaint.color = ColorUtils.blendARGB(offColor, accentColor, animated)
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Knob. 24dp diameter, 3dp inset, slides from x=3 → x=w-27.
        val knobDp = 24f * density
        val inset = 3f * density
        val knobX = inset + (w - knobDp - inset * 2f) * animated
        val knobY = inset
        knobPaint.color = if (animated > 0.5f) {
            // Dark knob on the "on" track.
            0xFF0A0A0B.toInt()
        } else {
            Color.WHITE
        }
        canvas.drawCircle(
            knobX + knobDp / 2f,
            knobY + knobDp / 2f,
            knobDp / 2f,
            knobPaint,
        )
    }

    companion object {
        private const val ANIM_MS = 150L
        private val EASE = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
    }
}
