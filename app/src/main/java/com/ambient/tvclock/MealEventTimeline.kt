package com.ambient.tvclock

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure functions that flatten a [MealPlan] into a chronological list of events
 * for a given date and answer "what is the next thing the user has to do?".
 *
 * Kept in its own file so it can be exercised without an Android dependency —
 * the only Android-specific bits in the meal screen live in [MealScreenBinder].
 */
object MealEventTimeline {

    /** A single event surfaced on the meal screen. */
    data class TimelineEntry(
        val time: LocalTime,
        val label: String,
        val kind: EventKind,
        val detail: String?,
        /** Non-null when this entry comes from a `meals` array (carries body + protein). */
        val meal: Meal?,
        /** Day key (`mon`..`sun`) the entry was sourced from. Needed for cross-day rollover. */
        val dayKey: String
    )

    /**
     * Returns today's events sorted by time, with `daily_routine` entries that
     * declare today in their `skip_on` filtered out. Meals from the matching
     * day (if present) are merged in. Ties are broken by [EventKind] priority
     * so "Breakfast" wins over a coincident "Whey shake".
     */
    fun todayEntries(plan: MealPlan, dayKey: String): List<TimelineEntry> {
        val routine = plan.dailyRoutine
            .filter { dayKey !in it.skipOnDays }
            .map { TimelineEntry(it.time, it.label, it.kind, it.detail, null, dayKey) }
        val mealsForDay = plan.days[dayKey]?.meals.orEmpty()
            .map { TimelineEntry(it.time, it.label, EventKind.MEAL, null, it, dayKey) }
        return (routine + mealsForDay).sortedWith(
            compareBy({ it.time }, { kindPriority(it.kind) })
        )
    }

    /**
     * Next event in chronological order strictly after [now]. Rolls over to
     * tomorrow's first entry if none remain today — this is what surfaces
     * "Wake — 7:30 AM" after lights-off without a stale "next" stuck at the
     * end of yesterday.
     */
    fun nextEvent(plan: MealPlan, now: LocalDateTime): TimelineEntry? {
        val todayKey = dayKey(now.toLocalDate())
        val today = todayEntries(plan, todayKey)
        today.firstOrNull { it.time.isAfter(now.toLocalTime()) }?.let { return it }
        val tomorrowKey = dayKey(now.toLocalDate().plusDays(1))
        return todayEntries(plan, tomorrowKey).firstOrNull()
    }

    /**
     * Buy / prep events that the user should knock out before reaching
     * [nextEvent]. Used to render the "Before breakfast: pick up milk" ribbon
     * under the Up Next card. Limited to today; we don't surface tomorrow's
     * buys until tomorrow rolls over.
     */
    fun pendingBuysBefore(
        plan: MealPlan,
        now: LocalDateTime,
        nextEvent: TimelineEntry
    ): List<TimelineEntry> {
        // If the next event is itself across a day boundary, no buys precede it
        // in the current day — they'd belong to tomorrow's flow.
        val todayKey = dayKey(now.toLocalDate())
        if (nextEvent.dayKey != todayKey) return emptyList()
        return todayEntries(plan, todayKey).filter {
            it.kind == EventKind.BUY &&
                it.time.isAfter(now.toLocalTime()) &&
                !it.time.isAfter(nextEvent.time) &&
                it != nextEvent
        }
    }

    /** Three-letter lowercase key matching [MealPlan.days]. */
    fun dayKey(date: LocalDate): String {
        // DayOfWeek is a Java enum, so Kotlin treats the platform-typed result
        // as possibly-null. Branching on ordinal sidesteps the warning without
        // an `else`.
        return when (date.dayOfWeek.ordinal) {
            0 -> "mon"
            1 -> "tue"
            2 -> "wed"
            3 -> "thu"
            4 -> "fri"
            5 -> "sat"
            else -> "sun"
        }
    }

    // Lower number = higher priority when two events land on the same minute.
    private fun kindPriority(kind: EventKind): Int = when (kind) {
        EventKind.MEAL -> 0
        EventKind.SNACK -> 1
        EventKind.BUY -> 2
        EventKind.GYM -> 3
        EventKind.WAKE -> 4
        EventKind.SLEEP -> 5
        EventKind.OTHER -> 6
    }
}
