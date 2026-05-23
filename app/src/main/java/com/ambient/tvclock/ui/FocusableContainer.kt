package com.ambient.tvclock.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.R

/**
 * Frame layout that owns the Dock focus visual.
 *
 * On focus gain it scales the contained children by [focusScale] and paints a
 * 2dp accent border outside the child bounds plus a soft halo. Replaces the
 * hand-rolled `state_focused` selectors scattered across the legacy layouts.
 * Drop a `FocusableContainer` around any focusable child and set
 * `app:accentColor` / `app:focusScale`.
 *
 * The container itself is **not** focusable. Set focusability on its child
 * (or pass `app:proxyFocus="true"` to opt this view in). That way nested
 * RecyclerView rows keep their own focus semantics, and the container is
 * solely a decorator.
 */
class FocusableContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var accentColor: Int = 0xFFC8F25C.toInt()
        set(value) {
            field = value
            borderPaint.color = value
            invalidate()
        }

    /** Maximum scale factor applied on focus. HTML default = 1.04. */
    var focusScale: Float = DEFAULT_SCALE

    /** Corner radius of the painted border. Matches the child's own radius. */
    var cornerRadiusPx: Float = DEFAULT_RADIUS_DP * resources.displayMetrics.density

    /** Inset of the border from the child bounds, in px. Negative = outside. */
    var borderInsetPx: Float = -DEFAULT_INSET_DP * resources.displayMetrics.density

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = accentColor
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
    }
    private val tmpRect = RectF()

    /** Animator handle so we can cancel mid-transition. */
    private var scaleAnimator: AnimatorSet? = null

    init {
        // Without this the parent clips the focus halo / border to our own bounds.
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.FocusableContainer, defStyleAttr, 0).use { ta ->
                accentColor = ta.getColor(R.styleable.FocusableContainer_accentColor, accentColor)
                focusScale = ta.getFloat(R.styleable.FocusableContainer_focusScale, focusScale)
                cornerRadiusPx = ta.getDimension(R.styleable.FocusableContainer_focusRadius, cornerRadiusPx)
                val proxyFocus = ta.getBoolean(R.styleable.FocusableContainer_proxyFocus, false)
                if (proxyFocus) {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                }
            }
        }
    }

    /**
     * Children outside the bounds aren't drawn unless we extend our clip. The
     * focus halo is ~12dp outside the child rect, so add some slack.
     */
    override fun getOutlineProvider() = null

    /**
     * We watch focus on the entire subtree, not just self, because the
     * container is typically a wrapper around an inner focusable (button,
     * list row, etc.). The chain `addFocusables` -> child focus listeners
     * surfaces a single boolean we can drive animations off.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        scaleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val newlyFocused = newFocus != null && containsFocusedDescendant(newFocus)
        setFocused(newlyFocused)
    }

    private fun containsFocusedDescendant(v: View?): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === this) return true
            val parent = cur.parent
            cur = parent as? View
        }
        return false
    }

    private var isFocusedState = false

    private fun setFocused(focused: Boolean) {
        if (focused == isFocusedState) return
        isFocusedState = focused
        scaleAnimator?.cancel()
        val target = if (focused) focusScale else 1f
        scaleAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@FocusableContainer, "scaleX", target),
                ObjectAnimator.ofFloat(this@FocusableContainer, "scaleY", target),
            )
            duration = FOCUS_ANIM_MS
            interpolator = EASE
            start()
        }
        // Make sure the halo redraws even if the scale animation alone
        // doesn't invalidate (the border color stays constant otherwise).
        invalidate()
    }

    /**
     * Draw the border + halo *after* children, so it sits visually on top.
     * The decoration takes its rect from our own bounds rather than the
     * first child — callers are expected to size the container to wrap its
     * focused child precisely.
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isFocusedState) return

        tmpRect.set(
            borderInsetPx,
            borderInsetPx,
            width - borderInsetPx,
            height - borderInsetPx,
        )

        // Halo. Two-step: outer wide soft glow + inner sharp 2dp ring.
        haloPaint.color = ColorUtils.setAlphaComponent(accentColor, HALO_ALPHA)
        canvas.drawRoundRect(tmpRect, cornerRadiusPx, cornerRadiusPx, haloPaint)

        borderPaint.color = accentColor
        canvas.drawRoundRect(tmpRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }

    companion object {
        private const val DEFAULT_SCALE = 1.04f
        private const val DEFAULT_RADIUS_DP = 14f
        private const val DEFAULT_INSET_DP = 4f
        private const val FOCUS_ANIM_MS = 150L
        private const val HALO_ALPHA = 49 // ~0x1f / 0x33 — matches HTML

        // cubic-bezier(.2, .7, .3, 1) — taken verbatim from the HTML prototype.
        private val EASE: Interpolator = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
    }
}
