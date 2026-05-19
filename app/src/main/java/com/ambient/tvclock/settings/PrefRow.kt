package com.ambient.tvclock.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import com.ambient.tvclock.R
import com.ambient.tvclock.ui.FocusableContainer

/**
 * The Settings page workhorse row.
 *
 * Mirrors the HTML `PrefRow` spec:
 * - label (Manrope 500 19sp, white) + optional hint (Manrope 400 15sp, 50% white)
 * - right side: a "value" — text, an affordance glyph (↵ / chevron), a Toggle,
 *   or any custom child view added with [setValueView].
 * - card background flips translucency on focus and the inner border lights up;
 *   the outer focus border + halo are handled by an embedded
 *   [FocusableContainer], so this row just renders the inner card.
 *
 * The class is `open` so [PrefDangerRow] can specialise it without rewriting
 * the layout binding.
 */
open class PrefRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface PrefToggleCallback {
        fun onToggled(newValue: Boolean)
    }

    private val density = resources.displayMetrics.density

    private val focusContainer: FocusableContainer
    private val surface: FrameLayout
    private val labelView: TextView
    private val hintView: TextView
    private val valueView: TextView
    private val valueSlot: FrameLayout

    private val cardBg: GradientDrawable

    private var toggle: PrefToggle? = null
    private var toggleCallback: PrefToggleCallback? = null

    init {
        // Container clips children would erase the outside focus halo.
        clipChildren = false
        clipToPadding = false

        val inflater = LayoutInflater.from(context)
        val rootView = inflater.inflate(R.layout.view_pref_row_content, this, true)
        focusContainer = rootView.findViewById(R.id.prefRowFocus)
        surface = rootView.findViewById(R.id.prefRowSurface)
        labelView = rootView.findViewById(R.id.prefRowLabel)
        hintView = rootView.findViewById(R.id.prefRowHint)
        valueView = rootView.findViewById(R.id.prefRowValue)
        valueSlot = rootView.findViewById(R.id.prefRowValueSlot)

        focusContainer.focusScale = 1.02f
        focusContainer.cornerRadiusPx = 12f * density

        cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.TRANSPARENT)
            setStroke((1f * density).toInt(), ContextCompat.getColor(context, R.color.dock_border))
        }
        surface.background = cardBg

        surface.setOnFocusChangeListener { _, focused -> applyFocusState(focused) }

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.PrefRow, defStyleAttr, 0).use { ta ->
                ta.getString(R.styleable.PrefRow_prefLabel)?.let { labelView.text = it }
                val value = ta.getString(R.styleable.PrefRow_prefValue)
                val hint = ta.getString(R.styleable.PrefRow_prefHint)
                val danger = ta.getBoolean(R.styleable.PrefRow_prefDanger, false)
                value?.let { setValue(it) }
                hint?.let { setHint(it) }
                if (danger) applyDangerStyle()
                when (ta.getInt(R.styleable.PrefRow_prefAffordance, 0)) {
                    1 -> setValue("›")
                    2 -> setValue("→")
                    3 -> setValue("↵")
                }
            }
        }
        applyFocusState(false)
    }

    private fun applyFocusState(focused: Boolean) {
        if (focused) {
            cardBg.setColor(ContextCompat.getColor(context, R.color.dock_card_focus))
            cardBg.setStroke(
                (1f * density).toInt(),
                ContextCompat.getColor(context, R.color.dock_border_strong),
            )
        } else {
            cardBg.setColor(Color.TRANSPARENT)
            cardBg.setStroke(
                (1f * density).toInt(),
                ContextCompat.getColor(context, R.color.dock_border),
            )
        }
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        surface.setOnClickListener(listener)
    }

    override fun setFocusable(focusable: Boolean) {
        surface.isFocusable = focusable
        surface.isFocusableInTouchMode = focusable
    }

    var label: CharSequence?
        get() = labelView.text
        set(value) { labelView.text = value }

    fun setHint(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            hintView.visibility = View.GONE
            hintView.text = null
        } else {
            hintView.visibility = View.VISIBLE
            hintView.text = text
        }
    }

    fun setValue(text: CharSequence?) {
        if (valueSlot.childCount != 1 || valueSlot.getChildAt(0) !== valueView) {
            valueSlot.removeAllViews()
            valueSlot.addView(valueView)
        }
        valueView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        valueView.text = text
    }

    /** Install a custom view (e.g. a [PrefToggle]) as the right-side affordance. */
    fun setValueView(view: View?) {
        valueSlot.removeAllViews()
        if (view != null) {
            valueSlot.addView(view)
        } else {
            valueSlot.addView(valueView)
        }
    }

    /**
     * Sets up a switch on the right side. The row's click forwards to flipping
     * the toggle; [callback] is invoked with the new value after each flip.
     */
    fun setToggle(initial: Boolean, callback: PrefToggleCallback) {
        val t = (toggle ?: PrefToggle(context)).also {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END,
            )
            it.setOnImmediate(initial)
        }
        toggle = t
        toggleCallback = callback
        setValueView(t)
        surface.setOnClickListener {
            t.isOn = !t.isOn
            toggleCallback?.onToggled(t.isOn)
        }
    }

    /** Update an existing toggle's state without triggering the callback. */
    fun setToggleState(value: Boolean) {
        toggle?.isOn = value
    }

    /** Recolour the row for destructive actions. Used by [PrefDangerRow]. */
    fun applyDangerStyle() {
        focusContainer.accentColor = ContextCompat.getColor(context, R.color.cal_work_text)
        labelView.setTextColor(ContextCompat.getColor(context, R.color.cal_work_text))
    }
}
