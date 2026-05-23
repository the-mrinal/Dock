package com.ambient.tvclock.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import com.ambient.tvclock.R

/**
 * Small composite: optional leading icon slot + UPPERCASE Eyebrow text.
 *
 * The HTML prototype's "PLAYING FROM PLAYLIST" / "AIRPLAY" / "DOCK · SETTINGS"
 * labels all share the same JetBrains-Mono-500 13sp +0.16em formula; this
 * view exposes it as a single, easy-to-place primitive so redesign work
 * doesn't reproduce the typography in a dozen XML files.
 *
 * The icon child slot is the first non-TextView child. If a caller needs an
 * icon they add it as the first child in XML (an [android.widget.ImageView],
 * a [VuBarsView] etc.) and this view will lay it out before the label.
 */
class EyebrowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView: TextView

    var text: CharSequence?
        get() = labelView.text
        set(value) { labelView.text = value }

    var textColor: Int
        get() = labelView.currentTextColor
        set(value) { labelView.setTextColor(value) }

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL

        labelView = TextView(context).apply {
            id = R.id.eyebrowLabel
            // The TextAppearance carries font, size, allCaps, letterSpacing.
            setTextAppearance(R.style.TextAppearance_Dock_Eyebrow)
            includeFontPadding = false
        }
        labelView.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = 0
        }
        addView(labelView)

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.EyebrowView, defStyleAttr, 0).use { ta ->
                ta.getString(R.styleable.EyebrowView_eyebrowText)?.let { labelView.text = it }
                val color = ta.getColor(
                    R.styleable.EyebrowView_eyebrowColor,
                    ContextCompat.getColor(context, R.color.dock_text_ter),
                )
                labelView.setTextColor(color)
                if (ta.getBoolean(R.styleable.EyebrowView_eyebrowTracking, false)) {
                    labelView.setTextAppearance(R.style.TextAppearance_Dock_Eyebrow_Tracking)
                    labelView.setTextColor(color)
                }
            }
        }
    }

    /**
     * Insert (or replace) the optional icon slot. Passing `null` removes any
     * previously installed icon. The icon is added at position 0 and gets an
     * 8dp end margin so it never collides with the label.
     */
    fun setIcon(icon: View?) {
        // Drop any existing icon — anything *other than* the label.
        for (i in childCount - 1 downTo 0) {
            if (getChildAt(i) !== labelView) removeViewAt(i)
        }
        if (icon == null) return
        val density = resources.displayMetrics.density
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (8 * density).toInt()
        }
        addView(icon, 0, lp)
    }
}
