package com.ambient.tvclock

import android.view.View
import android.view.ViewStub
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Drives the redesigned Calendar surface (artboards 04 / 05 / 06).
 *
 * The day-view stack and the empty-state layout are sibling children of the
 * page root; we toggle visibility based on whether the feed has events for
 * the current day. The empty-state layout is inflated lazily via ViewStub
 * so cold-start cost stays on the day view.
 */
class CalendarScreenBinder(private val root: View) {

    // Day view widgets.
    private val dayLayout: View = root.findViewById(R.id.calendarDayLayout)
    private val textDate: TextView = root.findViewById(R.id.textCalendarScreenDate)
    private val textMeta: TextView = root.findViewById(R.id.textCalendarMeta)
    private val textFooter: TextView = root.findViewById(R.id.textCalendarFooter)
    private val recycler: RecyclerView = root.findViewById(R.id.recyclerCalendarEvents)
    private val emptyStub: ViewStub? = root.findViewById(R.id.calendarEmptyStub)

    private val adapter = CalendarEventAdapter(root.context)
    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    private var emptyRoot: View? = null
    private var textEmptyDate: TextView? = null
    private var textEmptyDay: TextView? = null

    // True on first bind and whenever the user navigates back to the Calendar
    // page; consumed after the next data bind so we don't fight the user's
    // manual scrolling when polling refreshes the list mid-read.
    private var pendingScrollToCurrent: Boolean = true
    private var latestEvents: List<CalendarEvent> = emptyList()

    init {
        recycler.layoutManager = LinearLayoutManager(root.context)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.setHasFixedSize(false)
    }

    fun scrollBy(dy: Int) {
        recycler.smoothScrollBy(0, dy)
    }

    fun canScrollVertically(): Boolean =
        recycler.canScrollVertically(1) || recycler.canScrollVertically(-1)

    fun updateDateLine() {
        val now = Calendar.getInstance().time
        val text = dateFormatter.format(now)
        textDate.text = text
        textEmptyDate?.text = text
        textEmptyDay?.text = SimpleDateFormat("dd", Locale.getDefault()).format(now)
    }

    fun requestScrollToCurrent() {
        pendingScrollToCurrent = true
        scrollToCurrentIfPending()
    }

    fun bind(snapshot: CalendarSnapshot) {
        updateDateLine()
        val context = root.context
        val now = System.currentTimeMillis()

        if (!CalendarPreferences.isEnabled(context)) {
            latestEvents = emptyList()
            showEmpty()
            return
        }
        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            latestEvents = emptyList()
            showEmpty()
            return
        }

        latestEvents = snapshot.events
        if (snapshot.events.isEmpty()) {
            showEmpty()
            return
        }
        showDayView()

        adapter.submit(snapshot.events, now)
        textMeta.text = buildMetaLine(snapshot.events, now)

        val updatedText = when {
            snapshot.errorMessage != null && snapshot.events.isEmpty() ->
                context.getString(R.string.calendar_fetch_error)
            snapshot.lastUpdatedMillis > 0 ->
                context.getString(
                    R.string.calendar_hint_left,
                    CalendarDisplayHelper.formatUpdated(snapshot.lastUpdatedMillis)
                )
            else -> context.getString(R.string.calendar_hint_left_simple)
        }
        textFooter.text = updatedText

        scrollToCurrentIfPending()
    }

    /** "5 events · 3 with conflicts · 4h 15m booked". */
    @Suppress("UNUSED_PARAMETER")
    private fun buildMetaLine(events: List<CalendarEvent>, now: Long): String {
        val context = root.context
        val timed = events.filter { !it.isAllDay }
        val countStr = if (events.size == 1)
            context.getString(R.string.calendar_events_count_one, events.size)
        else
            context.getString(R.string.calendar_events_count_other, events.size)

        // Conflicts: any event that overlaps at least one other event in time.
        var conflicts = 0
        for (i in timed.indices) {
            val a = timed[i]
            val hasOverlap = timed.indices.any { j ->
                j != i && timed[j].startMillis < a.endMillis && a.startMillis < timed[j].endMillis
            }
            if (hasOverlap) conflicts++
        }

        // Booked time = sum of all timed durations (no dedup — matches the HTML
        // text "4h 15m booked" which sums per-meeting time).
        val totalMin = timed.sumOf { ((it.endMillis - it.startMillis) / 60_000L).coerceAtLeast(0L) }
        val hours = (totalMin / 60L).toInt()
        val mins = (totalMin % 60L).toInt()
        val bookedStr = if (hours > 0)
            context.getString(R.string.calendar_booked_hours, hours, mins)
        else
            context.getString(R.string.calendar_booked_minutes, mins)

        return if (conflicts > 0) {
            "$countStr · ${context.getString(R.string.calendar_with_conflicts, conflicts)} · $bookedStr"
        } else {
            "$countStr · $bookedStr"
        }
    }

    private fun showEmpty() {
        ensureEmptyInflated()
        dayLayout.visibility = View.GONE
        emptyRoot?.visibility = View.VISIBLE
        updateDateLine()
    }

    private fun showDayView() {
        dayLayout.visibility = View.VISIBLE
        emptyRoot?.visibility = View.GONE
    }

    private fun ensureEmptyInflated() {
        if (emptyRoot != null) return
        emptyRoot = emptyStub?.inflate()
        textEmptyDate = emptyRoot?.findViewById(R.id.textCalendarEmptyDate)
        textEmptyDay = emptyRoot?.findViewById(R.id.textCalendarEmptyDay)
    }

    private fun scrollToCurrentIfPending() {
        if (!pendingScrollToCurrent) return
        val events = latestEvents
        if (events.isEmpty()) return
        val rows = CalendarBandGrouper.group(events, System.currentTimeMillis())
        val targetIndex = currentFocusIndex(rows, System.currentTimeMillis())
        if (targetIndex < 0) return
        pendingScrollToCurrent = false

        val offsetPx = (root.resources.displayMetrics.density * SCROLL_TOP_INSET_DP).toInt()
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(targetIndex, offsetPx)
        }
    }

    /** Prefer happening-now rows, then next-upcoming, then last past row. */
    @Suppress("UNUSED_PARAMETER")
    private fun currentFocusIndex(rows: List<CalendarRow>, now: Long): Int {
        val happening = rows.indexOfFirst {
            when (it) {
                is CalendarRow.Single -> it.isHappeningNow
                is CalendarRow.Band -> it.isHappeningNow
            }
        }
        if (happening >= 0) return happening
        val upcoming = rows.indexOfFirst {
            when (it) {
                is CalendarRow.Single -> !it.isPast
                is CalendarRow.Band -> !it.isPast
            }
        }
        if (upcoming >= 0) return upcoming
        return rows.lastIndex
    }

    companion object {
        private const val SCROLL_TOP_INSET_DP = 12
    }
}
