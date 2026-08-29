package com.ambient.tvclock

/**
 * Free/busy intent of an event as published by the source calendar
 * (Outlook's X-MICROSOFT-CDO-BUSYSTATUS, TRANSP, Google's transparency).
 * On a user's own published calendar, TENTATIVE reliably marks meetings the
 * user has not accepted yet. Derived by my-life, not here.
 */
enum class BusyStatus { FREE, TENTATIVE, BUSY, OOF }

/** The signed-in user's own RSVP to an event, where the source knows it (Google Calendar API). */
enum class RsvpStatus { NEEDS_ACTION, DECLINED, TENTATIVE, ACCEPTED, ORGANIZER }

/**
 * One occurrence as served by my-life's `CalendarEvent` schema — recurrences
 * already expanded, times already resolved. The Dock renders these; it never
 * parses a feed.
 */
data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    /** Exclusive; all-day events end at the next local midnight. */
    val endMillis: Long,
    val isAllDay: Boolean,
    val location: String,
    val source: CalendarSource,
    val uid: String = "",
    /** The calendar's label as my-life names it ("Work", "Family", …). */
    val calendar: String = "",
    val busyStatus: BusyStatus = BusyStatus.BUSY,
    val categories: List<String> = emptyList(),
    /** Teams/Meet/Zoom join link when the event carries one. */
    val onlineMeetingUrl: String? = null,
    /** Display name (or address) of the meeting organizer, if published. */
    val organizer: String? = null,
    /** Per-event color as #RRGGBB when the source says; UI falls back to a per-source accent. */
    val colorHex: String? = null,
    /** Null when the source can't say (ICS feeds); populated from the Google Calendar API. */
    val myResponse: RsvpStatus? = null,
    val attendeeCount: Int = 0
) {
    fun isHappeningNow(nowMillis: Long): Boolean = nowMillis in startMillis until endMillis

    fun isPast(nowMillis: Long): Boolean {
        if (isAllDay) {
            return false
        }
        return endMillis <= nowMillis
    }
}
