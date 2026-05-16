package com.ambient.tvclock

data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val location: String,
    val source: CalendarSource,
    val timeZoneId: String? = null,
    val rrule: String? = null
) {
    fun isHappeningNow(nowMillis: Long): Boolean {
        if (isAllDay) {
            return nowMillis in startMillis..endMillis
        }
        return nowMillis in startMillis until endMillis
    }

    fun isPast(nowMillis: Long): Boolean {
        if (isAllDay) {
            return false
        }
        return endMillis <= nowMillis
    }
}
