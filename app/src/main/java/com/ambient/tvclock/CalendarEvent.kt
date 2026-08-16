package com.ambient.tvclock

/**
 * Free/busy intent of an event as published by the source calendar.
 * Sourced from Outlook's X-MICROSOFT-CDO-BUSYSTATUS (or TRANSP as a
 * fallback). On a user's own published calendar, TENTATIVE reliably marks
 * meetings the user has not accepted yet.
 */
enum class BusyStatus { FREE, TENTATIVE, BUSY, OOF }

/** The signed-in user's own RSVP to an event, where the source knows it. */
enum class RsvpStatus { NEEDS_ACTION, DECLINED, TENTATIVE, ACCEPTED, ORGANIZER }

data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val location: String,
    val source: CalendarSource,
    val timeZoneId: String? = null,
    val rrule: String? = null,
    val busyStatus: BusyStatus = BusyStatus.BUSY,
    val categories: List<String> = emptyList(),
    /** Teams/Meet/Zoom join link when the feed carries one. */
    val onlineMeetingUrl: String? = null,
    /** Display name (or address) of the meeting organizer, if published. */
    val organizer: String? = null,
    /**
     * Per-event color as #RRGGBB. ICS feeds rarely carry this (Google's
     * secret iCal never does) — stays null until a Google Calendar API
     * integration fills it. UI falls back to a per-source accent.
     */
    val colorHex: String? = null,
    /** Null when the feed can't say (ICS); populated by API integrations. */
    val myResponse: RsvpStatus? = null,
    val attendeeCount: Int = 0
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
