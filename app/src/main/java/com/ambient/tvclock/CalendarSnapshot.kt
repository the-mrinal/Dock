package com.ambient.tvclock

data class CalendarSnapshot(
    val events: List<CalendarEvent>,
    val lastUpdatedMillis: Long,
    val errorMessage: String? = null
)
