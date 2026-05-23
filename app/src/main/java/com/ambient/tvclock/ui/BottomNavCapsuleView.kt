package com.ambient.tvclock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.DashboardPage
import com.ambient.tvclock.R

/**
 * The bottom navigation capsule from artboard 00.
 *
 * Hosts four focusable destination chips. Each chip has an icon + label;
 * the chip for the active page gets a tinted background and a small accent
 * dot. The capsule itself is a translucent black pill with a 0.66 alpha
 * scrim; the sibling `ArtWashView` behind it is already heavily blurred
 * so the wash shows through the scrim as the visual backdrop, matching
 * the HTML's `backdropFilter: blur(28px)` + `background: rgba(12,12,14,0.66)`.
 *
 * Public API:
 *  - [setActive] swaps the highlighted destination programmatically.
 *  - [onPageSelected] (lambda) fires when the user OKs a chip OR when D-pad
 *    LEFT/RIGHT lands on a new chip (`commitOnFocus = true`, the HTML
 *    behaviour) — the host should pipe this into `ViewPager2.setCurrentItem`.
 */
class BottomNavCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Fired with the destination the user just landed on. The host activity
     * forwards this straight to `ViewPager2.setCurrentItem`.
     */
    var onPageSelected: ((DashboardPage) -> Unit)? = null

    /**
     * When true, focus changes alone (not just OK presses) commit a page
     * change. Matches the HTML reference where the capsule pre-commits on
     * D-pad LEFT/RIGHT. Default true.
     */
    var commitOnFocus: Boolean = true

    private val capsule: LinearLayout
    private val chips: List<NavChip>
    private var active: DashboardPage = DashboardPage.HOME

    private val density: Float = resources.displayMetrics.density

    init {
        clipChildren = false
        clipToPadding = false
        // The capsule itself sits flush — the focus halo on a chip should
        // be free to extend beyond the capsule edge.
        setWillNotDraw(false)

        capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 8dp padding all sides, 6dp gap between chips (we model the gap
            // with chip side margins because LinearLayout dividers don't get
            // the precise spacing the HTML uses).
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            background = capsuleBackground()
            elevation = 20f * density
            clipChildren = false
            clipToPadding = false
            // No setRenderEffect here. Android's RenderEffect is forward-only —
            // attaching a blur to this capsule would blur its own chip labels,
            // not what's behind it. The sibling ArtWashView already renders a
            // pre-blurred radial wash, so a translucent scrim background on the
            // capsule lets that wash show through and gives the HTML's
            // `backdrop-filter: blur(28px)` look without destroying our text.
        }
        val capsuleLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        addView(capsule, capsuleLp)

        // Four destinations in the order they appear on the pager.
        chips = listOf(
            buildChip(DashboardPage.STATUS, "Connect", R.id.navDestConnect, R.color.nav_accent_connect),
            buildChip(DashboardPage.HOME, "Home", R.id.navDestHome, R.color.nav_accent_home),
            buildChip(DashboardPage.CALENDAR, "Calendar", R.id.navDestCalendar, R.color.nav_accent_calendar),
            buildChip(DashboardPage.MUSIC, "Music", R.id.navDestMusic, R.color.nav_accent_music),
        )
        chips.forEach { chip ->
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginStart = (3 * density).toInt()
            lp.marginEnd = (3 * density).toInt()
            capsule.addView(chip.view, lp)
        }

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.BottomNavCapsuleView, defStyleAttr, 0).use { ta ->
                val idx = ta.getInt(R.styleable.BottomNavCapsuleView_activeDestination, DashboardPage.HOME.index)
                active = DashboardPage.fromIndex(idx)
            }
        }
        applyActive()
    }

    /** Programmatic active swap — usually called from the host's pager listener. */
    fun setActive(page: DashboardPage) {
        if (page == active) return
        active = page
        applyActive()
    }

    /**
     * Move D-pad focus to the active chip. Used by MainActivity when the
     * user presses DOWN past the page's last focusable.
     */
    fun focusActiveChip() {
        chips.first { it.page == active }.view.requestFocus()
    }

    /** Whether *any* chip currently holds focus. */
    fun isChipFocused(): Boolean = chips.any { it.view.hasFocus() }

    private fun buildChip(page: DashboardPage, label: String, id: Int, accentRes: Int): NavChip {
        val accent = ContextCompat.getColor(context, accentRes)
        val chipView = LinearLayout(context).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            // 14 / 18 / 14 / 22 padding from HTML.
            setPadding(
                (18 * density).toInt(),
                (14 * density).toInt(),
                (22 * density).toInt(),
                (14 * density).toInt(),
            )
            background = chipBackground(accent, isActive = false)
        }
        val icon = ImageView(context).apply {
            setImageResource(iconResFor(page))
            val s = (22 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (12 * density).toInt()
            }
            setColorFilter(Color.WHITE)
        }
        chipView.addView(icon)

        val labelView = TextView(context).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_Dock_Label_Large)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColor(context, R.color.dock_text_pri))
            includeFontPadding = false
        }
        chipView.addView(
            labelView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )

        // Trailing dot — visible only when active.
        val dot = View(context).apply {
            val ds = (6 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(ds, ds).apply {
                marginStart = (10 * density).toInt()
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            background = bg
            visibility = View.GONE
        }
        chipView.addView(dot)

        chipView.setOnClickListener {
            commit(page)
        }
        chipView.setOnFocusChangeListener { _, hasFocus ->
            updateChipVisual(NavChip(page, chipView, icon, labelView, dot, accent), hasFocus)
            if (hasFocus && commitOnFocus) commit(page)
        }
        return NavChip(page, chipView, icon, labelView, dot, accent)
    }

    private fun iconResFor(page: DashboardPage): Int = when (page) {
        DashboardPage.STATUS -> R.drawable.ic_nav_connect
        DashboardPage.HOME -> R.drawable.ic_nav_home
        DashboardPage.CALENDAR -> R.drawable.ic_nav_calendar
        DashboardPage.MUSIC -> R.drawable.ic_nav_music
    }

    private fun commit(page: DashboardPage) {
        if (page == active) return
        // Don't update local state — let the host drive the active swap via
        // [setActive] after the pager finishes its animation. That avoids a
        // flicker where the chip flashes the new accent before the page arrives.
        onPageSelected?.invoke(page)
    }

    private fun applyActive() {
        chips.forEach { chip ->
            val isActive = chip.page == active
            chip.view.background = chipBackground(chip.accent, isActive)
            chip.icon.setColorFilter(if (isActive) chip.accent else Color.WHITE)
            chip.label.setTextColor(
                if (isActive) Color.WHITE
                else ContextCompat.getColor(context, R.color.dock_text_sec),
            )
            chip.dot.visibility = if (isActive) View.VISIBLE else View.GONE
        }
    }

    private fun updateChipVisual(chip: NavChip, focused: Boolean) {
        if (chip.page == active) return
        chip.label.setTextColor(
            if (focused) Color.WHITE
            else ContextCompat.getColor(context, R.color.dock_text_sec),
        )
    }

    private fun chipBackground(accent: Int, isActive: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            if (isActive) {
                setColor(ColorUtils.setAlphaComponent(accent, 0x26))
                setStroke(density.toInt(), ColorUtils.setAlphaComponent(accent, 0x55))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(density.toInt(), Color.TRANSPARENT)
            }
        }

    private fun capsuleBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999f
        // rgba(12,12,14,0.66) ≈ 0xA80C0C0E
        setColor(0xA80C0C0E.toInt())
        setStroke(density.toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 0x1A))
    }

    private data class NavChip(
        val page: DashboardPage,
        val view: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val dot: View,
        val accent: Int,
    )
}
