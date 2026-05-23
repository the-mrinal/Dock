package com.ambient.tvclock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ambient.tvclock.R

/**
 * A single card inside a calendar overlap band (artboard 05).
 *
 * Renders the per-event tag + title + meta line. State is purely
 * time-relative: PAST events dim to 62% opacity; LIVE events get the
 * tag-colored 3dp left-edge stripe. We don't infer RSVP (iCal feeds carry
 * no RSVP signal), so the only state chip the card emits is "Starts HH:MM"
 * for events that begin partway into a partial-overlap band.
 *
 * The card itself isn't focusable — its [FocusableContainer] parent is.
 */
class BandCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class State { LIVE, PAST, UPCOMING }
    enum class Tag { PERSONAL, WORK }

    private val tagChip: TextView
    private val timeChip: TextView
    private val stateChip: TextView
    private val title: TextView
    private val meta: TextView

    /** Color of the 3dp left-edge stripe. Transparent for declined/tentative. */
    private var leftStripeColor: Int = Color.TRANSPARENT

    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stripeRect = RectF()
    private val density = resources.displayMetrics.density
    private val stripeWidthPx = 3f * density
    private val cardCornerPx = 10f * density

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        LayoutInflater.from(context).inflate(R.layout.view_band_card_content, this, true)
        background = makeCardBackground()
        val pad = (14 * density).toInt()
        setPadding(pad, (12 * density).toInt(), pad, (12 * density).toInt())

        tagChip = findViewById(R.id.bandCardTag)
        timeChip = findViewById(R.id.bandCardTime)
        stateChip = findViewById(R.id.bandCardState)
        title = findViewById(R.id.bandCardTitle)
        meta = findViewById(R.id.bandCardMeta)
    }

    fun bind(
        tag: Tag,
        state: State,
        titleText: String,
        metaText: String,
        timeText: String? = null,
        startsAt: String? = null,
        showTime: Boolean = false,
    ) {
        val isPersonal = tag == Tag.PERSONAL
        val tagDotColor = ContextCompat.getColor(
            context,
            if (isPersonal) R.color.cal_personal_dot else R.color.cal_work_dot
        )
        val tagTextColor = ContextCompat.getColor(
            context,
            if (isPersonal) R.color.cal_personal_text else R.color.cal_work_text
        )
        val tagBgRes = if (isPersonal) R.color.cal_personal_tag else R.color.cal_work_tag

        // Tag chip: filled when live/upcoming, outlined-and-dim when in the past.
        when (state) {
            State.PAST -> {
                tagChip.setBackgroundResource(
                    if (isPersonal) R.drawable.bg_chip_personal_outline
                    else R.drawable.bg_chip_work_outline
                )
                tagChip.setTextColor(tagTextColor)
                alpha = 0.62f
            }
            else -> {
                tagChip.setBackgroundColor(ContextCompat.getColor(context, tagBgRes))
                tagChip.setTextColor(tagTextColor)
                alpha = 1f
            }
        }
        tagChip.text =
            if (isPersonal) context.getString(R.string.calendar_legend_personal)
            else context.getString(R.string.calendar_legend_work)

        // Left edge stripe — coral / blue when live, transparent otherwise.
        leftStripeColor =
            if (state == State.LIVE) tagDotColor else Color.TRANSPARENT
        invalidate()

        // The only chip we surface on the eyebrow row is "Starts 15:00" for
        // events that begin partway into a partial-overlap band. iCal carries
        // no RSVP data so we don't pretend to know attending/declined.
        if (startsAt != null) {
            stateChip.visibility = VISIBLE
            stateChip.text = context.getString(R.string.calendar_starts_at, startsAt)
            stateChip.setTextColor(ContextCompat.getColor(context, R.color.c_amber))
        } else {
            stateChip.visibility = GONE
        }

        if (showTime && !timeText.isNullOrBlank()) {
            timeChip.visibility = VISIBLE
            timeChip.text = timeText
            timeChip.setTextColor(
                if (startsAt != null) ContextCompat.getColor(context, R.color.c_amber)
                else 0x8CFFFFFF.toInt()
            )
        } else {
            timeChip.visibility = GONE
        }

        title.text = titleText
        meta.text = metaText
        meta.visibility = if (metaText.isBlank()) GONE else VISIBLE
    }

    private fun makeCardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cardCornerPx
        setColor(ColorUtils.setAlphaComponent(Color.WHITE, 0x0B))
        setStroke(density.toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 0x1A))
    }

    /**
     * Paint the 3dp left-edge stripe directly on the View canvas (after the
     * GradientDrawable background renders). Drawing here rather than fighting
     * with LayerDrawable insets means the stripe always tracks the card's
     * actual height and stays inside the rounded corner clip.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (leftStripeColor == Color.TRANSPARENT) return
        stripePaint.color = leftStripeColor
        stripeRect.set(0f, 0f, stripeWidthPx, height.toFloat())
        // The corner radius applies to all 4 corners on the parent rect; the
        // stripe's right edge sits well clear of any rounded corner so we can
        // draw it as a simple rectangle and rely on the parent's rounded
        // background to hide the left-edge curve.
        canvas.drawRect(stripeRect, stripePaint)
    }
}
