package com.ambient.tvclock

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CalendarDisplayHelper {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val updatedFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun formatEventTime(event: CalendarEvent): String {
        if (event.isAllDay) {
            return "All day"
        }
        val start = timeFormat.format(Date(event.startMillis))
        val end = timeFormat.format(Date(event.endMillis))
        return "$start – $end"
    }

    fun formatUpdated(millis: Long): String =
        updatedFormat.format(Date(millis))

    fun sourceLabel(context: Context, source: CalendarSource): String =
        when (source) {
            CalendarSource.PERSONAL -> context.getString(R.string.calendar_source_personal)
            CalendarSource.WORK -> context.getString(R.string.calendar_source_work)
        }

    fun nextUpcoming(events: List<CalendarEvent>, now: Long): CalendarEvent? {
        val active = events.filter { !it.isPast(now) }
        val happening = active.firstOrNull { it.isHappeningNow(now) }
        return happening ?: active.firstOrNull()
    }

    fun remainingCount(events: List<CalendarEvent>, now: Long, excluding: CalendarEvent?): Int {
        val rest = events.filter { !it.isPast(now) && it != excluding }
        return (rest.size - 1).coerceAtLeast(0)
    }
}
