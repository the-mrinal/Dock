package com.ambient.tvclock.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ambient.tvclock.R

/**
 * One half of the Connect surface (artboards 07 / 08). Symmetric vertical
 * stack: eyebrow → 160dp `PulseHaloView` → Manrope-200 64sp state title →
 * detail line → capsule action button. State is driven externally via
 * [setState]; halo activation + colors come from the same call so the
 * binder is a 1-state-per-side state machine.
 *
 * Used twice in screen_status.xml — once per service (AirPlay, VPN).
 */
class ServiceColumnView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val eyebrow: EyebrowView
    private val halo: PulseHaloView
    private val titleView: TextView
    private val detailView: TextView
    val actionButton: PillButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_service_column, this, true)
        eyebrow = findViewById(R.id.serviceColumnEyebrow)
        halo = findViewById(R.id.serviceColumnHalo)
        titleView = findViewById(R.id.serviceColumnTitle)
        detailView = findViewById(R.id.serviceColumnDetail)
        actionButton = findViewById(R.id.serviceColumnAction)
        // Apply tracking variant in Kotlin (XML can't pick a style at runtime).
        eyebrow.findViewById<TextView>(R.id.eyebrowLabel)
            .setTextAppearance(R.style.TextAppearance_Dock_Eyebrow_Tracking)
    }

    /**
     * Apply a complete state. `active=true` flips the halo on with its two
     * pulsing rings, colors the dot + action button border to [accent], and
     * (when [filled] is true) makes the action button a solid accent fill so
     * it reads as the "destructive / committed" action (e.g. "Stop" /
     * "Disconnect").
     */
    fun setState(
        eyebrowText: CharSequence,
        title: CharSequence,
        detail: CharSequence,
        actionLabel: CharSequence,
        accent: Int,
        active: Boolean,
        actionEnabled: Boolean = true,
        filled: Boolean = false,
    ) {
        eyebrow.text = eyebrowText
        titleView.text = title
        detailView.text = detail

        halo.ringColor = accent
        halo.isPulsing = active

        // Subtle radial background behind the dot — drawn via a layered drawable
        // baked into the layout XML, but we tint it here to match the service.
        val haloBg = halo.background
        if (haloBg != null) {
            haloBg.setTint(if (active) accent else 0x14FFFFFF)
        }

        actionButton.accentColor = accent
        actionButton.filled = filled
        actionButton.text = actionLabel
        actionButton.isEnabled = actionEnabled
        actionButton.alpha = if (actionEnabled) 1f else 0.5f
    }

    fun setOnActionClickListener(l: () -> Unit) {
        actionButton.setOnClickListener { l() }
    }

    /**
     * Convenience helper used by [com.ambient.tvclock.StatusScreenBinder] when
     * the action chooses between idle/streaming/connected color sets.
     */
    @Suppress("unused")
    fun resolveColor(colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    companion object {
        @Suppress("unused")
        fun spToPx(context: Context, sp: Float): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sp,
                context.resources.displayMetrics
            ).toInt()
    }
}
