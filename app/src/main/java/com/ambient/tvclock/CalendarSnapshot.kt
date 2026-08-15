package com.ambient.tvclock

data class CalendarSnapshot(
    val events: List<CalendarEvent>,
    val lastUpdatedMillis: Long,
    val errorMessage: String? = null,
    /**
     * First event starting after today per source, looking ahead up to a
     * week. Feeds the "NEXT · MON 9:30 AM · …" preview an empty deck shows.
     */
    val nextAfterToday: Map<CalendarSource, CalendarEvent> = emptyMap()
)
