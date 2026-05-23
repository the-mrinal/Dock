package com.ambient.tvclock

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.ui.BandCard
import com.ambient.tvclock.ui.FocusableContainer
import com.ambient.tvclock.ui.VuBarsView

/**
 * Adapter for the redesigned calendar (artboards 04 / 05).
 *
 * Renders two view types — single rows (one event) and band rows (a
 * stretched cell containing 2+ side-by-side `BandCard`s for overlapping
 * events). Grouping is done by [CalendarBandGrouper]; this adapter only
 * paints the rows.
 */
class CalendarEventAdapter(
    private val context: Context,
) : ListAdapter<CalendarRow, RecyclerView.ViewHolder>(DIFF) {

    /** Submit a new event list, grouping into rows at [nowMillis]. */
    fun submit(events: List<CalendarEvent>, nowMillis: Long) {
        submitList(CalendarBandGrouper.group(events, nowMillis))
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is CalendarRow.Single -> TYPE_SINGLE
        is CalendarRow.Band -> TYPE_BAND
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SINGLE -> SingleHolder(inflater.inflate(R.layout.item_calendar_event, parent, false))
            TYPE_BAND -> BandHolder(inflater.inflate(R.layout.item_calendar_band, parent, false))
            else -> throw IllegalStateException("unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is CalendarRow.Single -> (holder as SingleHolder).bind(row)
            is CalendarRow.Band -> (holder as BandHolder).bind(row)
        }
    }

    // ------------------------------------------------------------------
    // Single-row view holder
    // ------------------------------------------------------------------

    inner class SingleHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val focus: FocusableContainer = view.findViewById(R.id.eventFocus)
        private val row: View = view.findViewById(R.id.eventRow)
        private val timeStart: TextView = view.findViewById(R.id.textEventTimeStart)
        private val durEnds: TextView = view.findViewById(R.id.textEventDurEnds)
        private val rail: View = view.findViewById(R.id.eventSourceRail)
        private val source: TextView = view.findViewById(R.id.textEventSource)
        private val nowBadge: TextView = view.findViewById(R.id.textEventNowBadge)
        private val title: TextView = view.findViewById(R.id.textEventTitle)
        private val location: TextView = view.findViewById(R.id.textEventLocation)
        private val nowMeta: View = view.findViewById(R.id.eventNowMeta)
        private val timeLeft: TextView = view.findViewById(R.id.textEventTimeLeft)

        fun bind(row: CalendarRow.Single) {
            val event = row.event
            val isPersonal = event.source == CalendarSource.PERSONAL
            val accent = ContextCompat.getColor(
                context,
                if (isPersonal) R.color.cal_personal_dot else R.color.cal_work_dot
            )
            val tagBg = ContextCompat.getColor(
                context,
                if (isPersonal) R.color.cal_personal_tag else R.color.cal_work_tag
            )
            val tagText = ContextCompat.getColor(
                context,
                if (isPersonal) R.color.cal_personal_text else R.color.cal_work_text
            )

            focus.accentColor = if (row.isHappeningNow) Color.WHITE else accent

            // Time block.
            timeStart.text =
                if (event.isAllDay) context.getString(R.string.calendar_all_day)
                else CalendarDisplayHelper.formatTime(event.startMillis)
            durEnds.visibility = if (event.isAllDay) View.GONE else View.VISIBLE
            if (!event.isAllDay) {
                val durStr = formatDuration(event.startMillis, event.endMillis)
                durEnds.text = context.getString(
                    R.string.calendar_dur_ends,
                    durStr,
                    CalendarDisplayHelper.formatTime(event.endMillis)
                )
            }

            // Vertical colored rail (6dp).
            rail.setBackgroundColor(accent)

            // Tag chip + NOW pill.
            source.setBackgroundColor(tagBg)
            source.setTextColor(tagText)
            source.text = if (isPersonal)
                context.getString(R.string.calendar_legend_personal)
            else
                context.getString(R.string.calendar_legend_work)

            nowBadge.visibility = if (row.isHappeningNow) View.VISIBLE else View.GONE

            title.text = event.title

            if (event.location.isNotBlank()) {
                location.visibility = View.VISIBLE
                location.text = event.location
            } else {
                location.visibility = View.GONE
            }

            // NOW state: brighter background + "Nm left" meta on the right.
            this.row.background = ContextCompat.getDrawable(
                context,
                if (row.isHappeningNow) R.drawable.bg_event_row_now_v2
                else R.drawable.bg_event_row
            )
            if (row.isHappeningNow) {
                nowMeta.visibility = View.VISIBLE
                val remainingMin = ((event.endMillis - System.currentTimeMillis()) / 60_000L)
                    .toInt().coerceAtLeast(0)
                timeLeft.text = context.getString(R.string.calendar_minutes_left, remainingMin)
            } else {
                nowMeta.visibility = View.GONE
            }

            // Past rows dim themselves so the user's eye lands on the next-up.
            val targetAlpha = if (row.isPast) 0.45f else 1f
            title.alpha = targetAlpha
            timeStart.alpha = if (row.isPast) 0.45f else 0.9f
            durEnds.alpha = if (row.isPast) 0.45f else 0.62f
            source.alpha = if (row.isPast) 0.55f else 1f
        }
    }

    // ------------------------------------------------------------------
    // Band view holder
    // ------------------------------------------------------------------

    inner class BandHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row: View = view.findViewById(R.id.bandRow)
        private val timeStart: TextView = view.findViewById(R.id.textBandTimeStart)
        private val durEnds: TextView = view.findViewById(R.id.textBandDurEnds)
        private val partial: TextView = view.findViewById(R.id.textBandPartial)
        private val card0Focus: FocusableContainer = view.findViewById(R.id.bandCard0Focus)
        private val card1Focus: FocusableContainer = view.findViewById(R.id.bandCard1Focus)
        private val card0: BandCard = view.findViewById(R.id.bandCard0)
        private val card1: BandCard = view.findViewById(R.id.bandCard1)
        private val nowPill: TextView = view.findViewById(R.id.textBandNowPill)

        fun bind(band: CalendarRow.Band) {
            // Time column. Range start..end + duration.
            timeStart.text = CalendarDisplayHelper.formatTime(band.bandStart)
            val durStr = formatDuration(band.bandStart, band.bandEnd)
            durEnds.text = context.getString(
                R.string.calendar_dur_ends,
                durStr,
                CalendarDisplayHelper.formatTime(band.bandEnd)
            )
            partial.visibility = if (band.partial) View.VISIBLE else View.GONE

            // Outer container background flips brighter while NOW.
            row.background = ContextCompat.getDrawable(
                context,
                if (band.isHappeningNow) R.drawable.bg_band_outer_now
                else R.drawable.bg_band_outer
            )

            // NOW pill notched on top-right.
            if (band.isHappeningNow) {
                nowPill.visibility = View.VISIBLE
                nowPill.text = context.getString(R.string.calendar_now_band, band.events.size)
            } else {
                nowPill.visibility = View.GONE
            }

            // Bind up to two cards visually; the HTML caps at 2 cards in a
            // visible band slot and shows "+N more" if there are more.
            val first = band.events.getOrNull(0)
            val second = band.events.getOrNull(1)

            if (first != null) {
                card0Focus.visibility = View.VISIBLE
                bindCard(card0, card0Focus, first, band)
            } else {
                card0Focus.visibility = View.GONE
            }
            if (second != null) {
                card1Focus.visibility = View.VISIBLE
                bindCard(card1, card1Focus, second, band)
            } else {
                card1Focus.visibility = View.GONE
            }
        }

        private fun bindCard(
            card: BandCard,
            focus: FocusableContainer,
            event: CalendarEvent,
            band: CalendarRow.Band,
        ) {
            val isPersonal = event.source == CalendarSource.PERSONAL
            val tag = if (isPersonal) BandCard.Tag.PERSONAL else BandCard.Tag.WORK
            val accent = ContextCompat.getColor(
                context,
                if (isPersonal) R.color.cal_personal_dot else R.color.cal_work_dot
            )
            focus.accentColor = accent

            // Time-relative state: PAST dims the card, LIVE gets the tag-colored
            // left stripe, UPCOMING is the default. iCal feeds carry no RSVP
            // signal so we don't infer attending/declined.
            val now = System.currentTimeMillis()
            val state = when {
                event.endMillis <= now -> BandCard.State.PAST
                event.startMillis <= now && event.endMillis > now -> BandCard.State.LIVE
                else -> BandCard.State.UPCOMING
            }
            val startsAt =
                if (event.startMillis > band.bandStart)
                    CalendarDisplayHelper.formatTime(event.startMillis)
                else null
            val timeText = if (event.startMillis != band.bandStart)
                CalendarDisplayHelper.formatTime(event.startMillis)
            else null
            card.bind(
                tag = tag,
                state = state,
                titleText = event.title,
                metaText = event.location,
                timeText = timeText,
                startsAt = startsAt,
                showTime = band.partial,
            )
        }
    }

    /**
     * Render a millisecond span as e.g. "30m" / "1h 30m" — the HTML uses the
     * same compact form for time-column durations.
     */
    private fun formatDuration(startMillis: Long, endMillis: Long): String {
        val totalMin = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(0)
        val hours = totalMin / 60
        val mins = totalMin % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }

    companion object {
        private const val TYPE_SINGLE = 0
        private const val TYPE_BAND = 1

        private val DIFF = object : DiffUtil.ItemCallback<CalendarRow>() {
            override fun areItemsTheSame(oldItem: CalendarRow, newItem: CalendarRow): Boolean =
                oldItem.sortKey == newItem.sortKey &&
                    oldItem::class == newItem::class

            override fun areContentsTheSame(oldItem: CalendarRow, newItem: CalendarRow): Boolean =
                oldItem == newItem
        }
    }
}
