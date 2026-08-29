package com.ambient.tvclock

import android.content.Context
import android.util.Log

/**
 * One refresh = one call to my-life. No feeds, no parsing on the TV:
 * my-life owns the calendar integration; the Dock renders what it returns.
 */
object CalendarRepository {

    private const val TAG = "CalendarRepository"

    fun refresh(context: Context): CalendarSnapshot {
        val now = System.currentTimeMillis()
        if (!CalendarPreferences.isEnabled(context) || !MyLifePreferences.isConfigured(context)) {
            return CalendarSnapshot(emptyList(), now)
        }

        val parsed = MyLifeCalendarClient.fetch(context)
        if (parsed == null) {
            // Keep the last good snapshot on screen with its own "Updated" time;
            // the footer reports the error only when there is nothing to show.
            val previous = CalendarCenter.current
            Log.w(TAG, "my-life unreachable; keeping ${previous.events.size} cached events")
            return CalendarSnapshot(
                events = previous.events,
                lastUpdatedMillis = previous.lastUpdatedMillis,
                errorMessage = "error",
                nextAfterToday = previous.nextAfterToday
            )
        }

        Log.i(TAG, "Today events: ${parsed.today.size}")
        return CalendarSnapshot(
            events = parsed.today,
            lastUpdatedMillis = now,
            nextAfterToday = parsed.nextAfterToday
        )
    }
}
