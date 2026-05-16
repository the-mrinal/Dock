package com.ambient.tvclock

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarScreenBinder(private val root: View) {

    private val textTitle: TextView = root.findViewById(R.id.textCalendarScreenTitle)
    private val textDate: TextView = root.findViewById(R.id.textCalendarScreenDate)
    private val textFooter: TextView = root.findViewById(R.id.textCalendarFooter)
    private val recycler: RecyclerView = root.findViewById(R.id.recyclerCalendarEvents)
    private val adapter = CalendarEventAdapter(root.context)

    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    // True on first bind and whenever the user navigates back to the Calendar
    // page; consumed after the next data bind so we don't fight the user's
    // manual scrolling when polling refreshes the list mid-read.
    private var pendingScrollToCurrent: Boolean = true
    private var latestEvents: List<CalendarEvent> = emptyList()

    init {
        recycler.layoutManager = LinearLayoutManager(root.context)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        textTitle.text = root.context.getString(R.string.calendar_screen_title)
    }

    fun scrollBy(dy: Int) {
        recycler.smoothScrollBy(0, dy)
    }

    fun canScrollVertically(): Boolean =
        recycler.canScrollVertically(1) || recycler.canScrollVertically(-1)

    fun updateDateLine() {
        textDate.text = dateFormatter.format(Calendar.getInstance().time)
    }

    /**
     * Ask the binder to land on the "now / next" event the next time data is
     * bound (or immediately if data is already available). Called by the
     * dashboard whenever the user navigates onto the Calendar page so they
     * never have to scroll past past-events to see what's current.
     */
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
            adapter.submit(emptyList(), now)
            textFooter.text = ""
            return
        }

        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            latestEvents = emptyList()
            adapter.submit(emptyList(), now)
            textFooter.text = context.getString(R.string.calendar_add_in_settings)
            return
        }

        latestEvents = snapshot.events
        adapter.submit(snapshot.events, now)
        textFooter.text = when {
            snapshot.errorMessage != null && snapshot.events.isEmpty() ->
                context.getString(R.string.calendar_fetch_error)
            snapshot.lastUpdatedMillis > 0 ->
                context.getString(
                    R.string.calendar_last_updated,
                    CalendarDisplayHelper.formatUpdated(snapshot.lastUpdatedMillis)
                )
            else -> ""
        }

        scrollToCurrentIfPending()
    }

    private fun scrollToCurrentIfPending() {
        if (!pendingScrollToCurrent) return
        val events = latestEvents
        if (events.isEmpty()) return
        val targetIndex = currentFocusIndex(events, System.currentTimeMillis())
        if (targetIndex < 0) return
        pendingScrollToCurrent = false

        // Small top inset so a sliver of the previous (past) event is still
        // visible — gives the user spatial context without taking over the row.
        val offsetPx = (root.resources.displayMetrics.density * SCROLL_TOP_INSET_DP).toInt()
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(targetIndex, offsetPx)
        }
    }

    /**
     * Pick the row the user most likely wants to see first:
     *   1. The event happening *right now*.
     *   2. Otherwise the next upcoming (first non-past) event.
     *   3. Otherwise the last past event so the day's last item is on screen.
     */
    private fun currentFocusIndex(events: List<CalendarEvent>, now: Long): Int {
        val happening = events.indexOfFirst { it.isHappeningNow(now) }
        if (happening >= 0) return happening
        val upcoming = events.indexOfFirst { !it.isPast(now) }
        if (upcoming >= 0) return upcoming
        return events.lastIndex
    }

    companion object {
        private const val SCROLL_TOP_INSET_DP = 12
    }
}
