package com.ambient.tvclock

import android.content.Context
import android.util.Log
import java.util.Calendar

object CalendarRepository {

    private const val TAG = "CalendarRepository"

    fun refresh(context: Context): CalendarSnapshot {
        if (!CalendarPreferences.isEnabled(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val personalUrl = CalendarPreferences.getPersonalUrl(context)
        val workUrl = CalendarPreferences.getWorkUrl(context)
        if (personalUrl.isBlank() && workUrl.isBlank()) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val merged = mutableListOf<CalendarEvent>()
        var fetchFailed = false

        if (personalUrl.isNotBlank()) {
            mergeFeed(personalUrl, CalendarSource.PERSONAL, merged).also { if (!it) fetchFailed = true }
        }

        if (workUrl.isNotBlank()) {
            mergeFeed(workUrl, CalendarSource.WORK, merged).also { if (!it) fetchFailed = true }
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = cal.timeInMillis

        val today = try {
            val expanded = RruleExpander.expand(merged.sortedBy { it.startMillis }, startOfDay, endOfDay)
            filterToday(expanded, startOfDay, endOfDay)
        } catch (e: Exception) {
            Log.e(TAG, "Expand failed: ${e.message}", e)
            filterToday(merged, startOfDay, endOfDay)
        }

        Log.i(TAG, "Today events: ${today.size} (fetchFailed=$fetchFailed)")

        return CalendarSnapshot(
            events = today,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorMessage = if (fetchFailed && today.isEmpty()) "error" else null
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
