package com.ambient.tvclock

import android.content.Context
import android.util.Log
import java.util.Calendar

object CalendarRepository {

    private const val TAG = "CalendarRepository"

    /** How far past today to look for each deck's "next event" preview. */
    private const val PREVIEW_LOOKAHEAD_DAYS = 7

    fun refresh(context: Context): CalendarSnapshot {
        if (!CalendarPreferences.isEnabled(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val personalUrl = CalendarPreferences.getPersonalUrl(context)
        val workUrl = CalendarPreferences.getWorkUrl(context)
        val googleApi = GoogleCalendarClient.isConfigured
        if (personalUrl.isBlank() && workUrl.isBlank() && !googleApi) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, PREVIEW_LOOKAHEAD_DAYS)
        val previewEnd = cal.timeInMillis

        val merged = mutableListOf<CalendarEvent>()
        var fetchFailed = false

        // Personal: Google Calendar API when provisioned (real colors, RSVP,
        // attendees, server-side recurrence expansion), else the ICS feed.
        val apiEvents = if (googleApi) {
            GoogleCalendarClient.fetchEvents(startOfDay, previewEnd)
        } else {
            null
        }
        when {
            apiEvents != null -> merged.addAll(apiEvents)
            googleApi && personalUrl.isBlank() -> fetchFailed = true
            personalUrl.isNotBlank() ->
                mergeFeed(personalUrl, CalendarSource.PERSONAL, merged).also { if (!it) fetchFailed = true }
        }

        if (workUrl.isNotBlank()) {
            mergeFeed(workUrl, CalendarSource.WORK, merged).also { if (!it) fetchFailed = true }
        }

        // Expand across the whole preview window in one pass; today's list
        // and the per-source "next after today" previews both come out of it.
        val expanded = try {
            RruleExpander.expand(merged.sortedBy { it.startMillis }, startOfDay, previewEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Expand failed: ${e.message}", e)
            merged.sortedBy { it.startMillis }
        }

        val today = filterToday(expanded, startOfDay, endOfDay)
        val nextAfterToday = CalendarSource.entries.mapNotNull { source ->
            expanded
                .filter {
                    it.rrule == null && it.source == source &&
                        it.startMillis >= endOfDay && it.startMillis < previewEnd
                }
                .minByOrNull { it.startMillis }
                ?.let { source to it }
        }.toMap()

        Log.i(TAG, "Today events: ${today.size} (fetchFailed=$fetchFailed)")

        return CalendarSnapshot(
            events = today,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorMessage = if (fetchFailed && today.isEmpty()) "error" else null,
            nextAfterToday = nextAfterToday
        )
    }

    private fun mergeFeed(url: String, source: CalendarSource, merged: MutableList<CalendarEvent>): Boolean {
        val body = IcalFetcher.fetch(url) ?: return false
        return try {
            merged.addAll(IcalParser.parse(body, source))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed for $source: ${e.message}", e)
            merged.isNotEmpty()
        }
    }

    private fun filterToday(
        events: List<CalendarEvent>,
        startOfDay: Long,
        endOfDay: Long
    ): List<CalendarEvent> {
        return events.filter { event ->
            event.rrule == null &&
                event.startMillis < endOfDay &&
                event.endMillis > startOfDay
        }
    }
}
