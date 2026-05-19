package com.ambient.tvclock.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.R

/**
 * Capsule pill matching the HTML prototype's primary/secondary buttons.
 *
 * Two variants:
 * - **Outlined** (default): translucent card background, 1dp accent-tinted
 *   border, accent-tinted label.
 * - **Filled**: solid accent background, dock-bg label.
 *
 * Either variant flips its appearance on focus (filled glows brighter,
 * outlined fills with accent) — that's handled by the [FocusableContainer]
 * the pill is typically wrapped in plus the State drawables here.
 *
 * The view is itself focusable so it works standalone (e.g. as the top-right
 * "Browse Playlists" button), and accepts `app:pillText` / `app:pillAccent`
 * / `app:pillFilled` from XML.
 */
class PillButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView: TextView

    var accentColor: Int = ContextCompat.getColor(context, R.color.dock_accent_chartreuse)
        set(value) {
            field = value
            applyBackground()
            applyLabelColor()
        }

    var filled: Boolean = false
        set(value) {
            field = value
            applyBackground()
            applyLabelColor()
        }

    var text: CharSequence?
        get() = labelView.text
        set(value) { labelView.text = value }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        val density = resources.displayMetrics.density
        // 14×22 padding in HTML — Android dp matches CSS px well enough at TV densities.
        setPadding((22 * density).toInt(), (14 * density).toInt(), (22 * density).toInt(), (14 * density).toInt())

        labelView = TextView(context).apply {
            id = R.id.pillButtonLabel
            setTextAppearance(R.style.TextAppearance_Dock_Label_Large)
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.PillButton, defStyleAttr, 0).use { ta ->
                accentColor = ta.getColor(R.styleable.PillButton_pillAccent, accentColor)
                ta.getString(R.styleable.PillButton_pillText)?.let { labelView.text = it }
                filled = ta.getBoolean(R.styleable.PillButton_pillFilled, false)
            }
        }
        applyBackground()
        applyLabelColor()
    }

    private fun applyBackground() {
        val density = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            if (filled) {
                setColor(accentColor)
                setStroke((density).toInt(), accentColor)
            } else {
                // dock_card on idle — same translucent white as cards.
                setColor(ColorUtils.setAlphaComponent(Color.WHITE, 0x0A))
                setStroke((density).toInt(), ColorUtils.setAlphaComponent(accentColor, 0x55))
            }
        }
        background = bg
    }

    private fun applyLabelColor() {
        labelView.setTextColor(
            if (filled) ContextCompat.getColor(context, R.color.dock_bg)
            else ContextCompat.getColor(context, R.color.dock_text_pri),
        )
    }
}
