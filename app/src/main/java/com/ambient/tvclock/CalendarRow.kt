package com.ambient.tvclock

/**
 * One renderable row in the Calendar day view. Either a single event or a
 * band of 2+ overlapping events that share at least one minute of wall
 * time. Grouping is done at the binder layer by [CalendarBandGrouper].
 */
sealed class CalendarRow {

    abstract val sortKey: Long

    /** One event, rendered with `item_calendar_event.xml`. */
    data class Single(
        val event: CalendarEvent,
        val isHappeningNow: Boolean,
        val isPast: Boolean,
    ) : CalendarRow() {
        override val sortKey: Long get() = event.startMillis
    }

    /**
     * 2+ events that overlap in time. The band's outer time window is
     * `(min start..max end)` across its members. `partial` is true when at
     * least one member only partially overlaps the band's full window —
     * i.e. some events start later or end earlier than others. Drives the
     * amber "PARTIAL OVERLAP" tag in the time column.
     */
    data class Band(
        val events: List<CalendarEvent>,
        val bandStart: Long,
        val bandEnd: Long,
        val isHappeningNow: Boolean,
        val isPast: Boolean,
        val partial: Boolean,
    ) : CalendarRow() {
        override val sortKey: Long get() = bandStart
    }
}

/**
 * Groups a sorted list of events into a stream of `CalendarRow` items: any
 * two events whose time windows touch (`startA < endB && startB < endA`)
 * collapse into a `Band`; the rest stay as `Single`.
 *
 * All-day events bypass grouping — they don't visually conflict with timed
 * meetings in the HTML reference.
 */
object CalendarBandGrouper {

    fun group(events: List<CalendarEvent>, nowMillis: Long): List<CalendarRow> {
        if (events.isEmpty()) return emptyList()
        val timed = events.filter { !it.isAllDay }.sortedBy { it.startMillis }
        val allDay = events.filter { it.isAllDay }
        val rows = mutableListOf<CalendarRow>()

        var i = 0
        while (i < timed.size) {
            val cluster = mutableListOf(timed[i])
            var clusterEnd = timed[i].endMillis
            var j = i + 1
            while (j < timed.size && timed[j].startMillis < clusterEnd) {
                cluster.add(timed[j])
                if (timed[j].endMillis > clusterEnd) clusterEnd = timed[j].endMillis
                j++
            }
            if (cluster.size == 1) {
                val e = cluster[0]
                rows.add(
                    CalendarRow.Single(
                        event = e,
                        isHappeningNow = e.isHappeningNow(nowMillis),
                        isPast = e.isPast(nowMillis),
                    )
                )
            } else {
                val bandStart = cluster.minOf { it.startMillis }
                val bandEnd = cluster.maxOf { it.endMillis }
                val partial = cluster.any {
                    it.startMillis > bandStart || it.endMillis < bandEnd
                }
                val happening = nowMillis in bandStart until bandEnd
                val past = bandEnd <= nowMillis
                rows.add(
                    CalendarRow.Band(
                        events = cluster.toList(),
                        bandStart = bandStart,
                        bandEnd = bandEnd,
                        isHappeningNow = happening,
                        isPast = past,
                        partial = partial,
                    )
                )
            }
            i = j
        }

        // All-day events go to the top of the day.
        val allDayRows = allDay.map {
            CalendarRow.Single(
                event = it,
                isHappeningNow = it.isHappeningNow(nowMillis),
                isPast = false,
            )
        }
        return allDayRows + rows
    }
}
