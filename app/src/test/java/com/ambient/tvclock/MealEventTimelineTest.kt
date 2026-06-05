package com.ambient.tvclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MealEventTimelineTest {

    private fun plan(): MealPlan = MealPlan(
        version = 1,
        generatedAtMs = null,
        dailyTargets = null,
        dailyRoutine = listOf(
            RoutineEvent(LocalTime.of(7, 30), "Wake", EventKind.WAKE, null, emptySet()),
            RoutineEvent(LocalTime.of(8, 0), "Gym", EventKind.GYM, null, setOf("sun")),
            RoutineEvent(LocalTime.of(8, 50), "Milk pickup", EventKind.BUY, "1 L", setOf("sun")),
            RoutineEvent(LocalTime.of(9, 0), "Whey shake", EventKind.SNACK, null, emptySet()),
            RoutineEvent(LocalTime.of(22, 30), "Lights off", EventKind.SLEEP, null, emptySet())
        ),
        days = mapOf(
            "wed" to MealDay(
                label = "Wednesday",
                kind = DayKind.NORMAL,
                kindLabel = "Standard",
                proteinSummary = "~145 g",
                meals = listOf(
                    Meal("breakfast", LocalTime.of(9, 15), "Breakfast", "Omelette + bread", "~28 g"),
                    Meal("lunch", LocalTime.of(13, 30), "Lunch", "Chicken + rajma", "~50 g"),
                    Meal("dinner", LocalTime.of(19, 0), "Dinner", "Chicken tikka + dal", "~50 g")
                ),
                note = null,
                prepForTomorrow = emptyList()
            ),
            "thu" to MealDay(
                label = "Thursday",
                kind = DayKind.NORMAL,
                kindLabel = "Standard",
                proteinSummary = "~140 g",
                meals = listOf(
                    Meal("breakfast", LocalTime.of(9, 15), "Breakfast", "Paneer bhurji", "~26 g")
                ),
                note = null,
                prepForTomorrow = emptyList()
            ),
            "sun" to MealDay(
                label = "Sunday",
                kind = DayKind.DIY,
                kindLabel = "DIY",
                proteinSummary = "~120 g",
                meals = listOf(
                    Meal("breakfast", LocalTime.of(9, 30), "Breakfast", "Oats + banana", "~30 g")
                ),
                note = null,
                prepForTomorrow = emptyList()
            )
        ),
        hydrationPrompt = HydrationPrompt(20, "Drink water")
    )

    @Test fun next_event_at_530pm_wed_is_dinner() {
        // Wed 2026-05-27 17:30 — between lunch and dinner.
        val now = LocalDateTime.of(LocalDate.of(2026, 5, 27), LocalTime.of(17, 30))
        val next = MealEventTimeline.nextEvent(plan(), now)!!
        assertEquals("Dinner", next.label)
        assertEquals(LocalTime.of(19, 0), next.time)
        assertEquals(EventKind.MEAL, next.kind)
        assertNotNull(next.meal)
    }

    @Test fun after_lights_off_rolls_to_tomorrows_wake() {
        // Wed 23:00 — past lights-off. Next should be Thursday's first event: Wake @ 07:30.
        val now = LocalDateTime.of(LocalDate.of(2026, 5, 27), LocalTime.of(23, 0))
        val next = MealEventTimeline.nextEvent(plan(), now)!!
        assertEquals("Wake", next.label)
        assertEquals("thu", next.dayKey)
    }

    @Test fun sunday_skips_gym_and_milk() {
        val today = MealEventTimeline.todayEntries(plan(), "sun")
        assertTrue(today.none { it.kind == EventKind.GYM })
        assertTrue(today.none { it.kind == EventKind.BUY })
        // Sunday 8:30 — next event should be the 9:00 whey shake (gym/milk skipped).
        val now = LocalDateTime.of(LocalDate.of(2026, 5, 31), LocalTime.of(8, 30))
        val next = MealEventTimeline.nextEvent(plan(), now)!!
        assertEquals(EventKind.SNACK, next.kind)
    }

    @Test fun pending_buys_between_now_and_next_meal() {
        val p = plan()
        // Wed 08:30 — gym done at 8:00; next event is milk pickup at 8:50 (a BUY).
        // pendingBuysBefore returns empty here because the next event IS the buy
        // and is excluded from its own pending list.
        val mid = LocalDateTime.of(LocalDate.of(2026, 5, 27), LocalTime.of(8, 30))
        val nextMid = MealEventTimeline.nextEvent(p, mid)!!
        assertEquals(EventKind.BUY, nextMid.kind)
        assertEquals(0, MealEventTimeline.pendingBuysBefore(p, mid, nextMid).size)

        // Wed 07:45 — next event is Gym (8:00). Milk pickup (8:50) is after gym,
        // so it doesn't precede the next event.
        val early = LocalDateTime.of(LocalDate.of(2026, 5, 27), LocalTime.of(7, 45))
        val nextEarly = MealEventTimeline.nextEvent(p, early)!!
        assertEquals("Gym", nextEarly.label)
        assertEquals(0, MealEventTimeline.pendingBuysBefore(p, early, nextEarly).size)
    }

    @Test fun missing_day_falls_back_to_routine_only() {
        val today = MealEventTimeline.todayEntries(plan(), "fri")
        assertTrue(today.isNotEmpty())
        assertTrue(today.none { it.kind == EventKind.MEAL })
    }

    @Test fun tie_breaker_prefers_meal_over_snack() {
        // Synthesize a coincident event by reusing breakfast time for a snack.
        val p = plan().copy(
            dailyRoutine = plan().dailyRoutine + RoutineEvent(
                LocalTime.of(9, 15), "Tea", EventKind.SNACK, null, emptySet()
            )
        )
        val entries = MealEventTimeline.todayEntries(p, "wed")
        val coincident = entries.filter { it.time == LocalTime.of(9, 15) }
        assertEquals(2, coincident.size)
        // MEAL has lower priority number → sorted first.
        assertEquals(EventKind.MEAL, coincident.first().kind)
    }
}
