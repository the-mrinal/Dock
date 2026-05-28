package com.ambient.tvclock

import java.time.LocalTime

enum class DayKind { NORMAL, VEG, DIY }

enum class EventKind { WAKE, GYM, SNACK, MEAL, BUY, SLEEP, OTHER }

data class DailyTargets(
    val proteinGrams: Int?,
    val calories: String?
)

data class HydrationPrompt(
    val minutesBeforeMeal: Int,
    val text: String
)

data class RoutineEvent(
    val time: LocalTime,
    val label: String,
    val kind: EventKind,
    val detail: String?,
    val skipOnDays: Set<String>
)

data class Meal(
    val id: String,
    val time: LocalTime,
    val label: String,
    val body: String,
    val protein: String?
)

data class MealDay(
    val label: String,
    val kind: DayKind,
    val kindLabel: String?,
    val proteinSummary: String?,
    val meals: List<Meal>,
    val note: String?,
    val prepForTomorrow: List<String>
)

data class MealPlan(
    val version: Int,
    val generatedAtMs: Long?,
    val dailyTargets: DailyTargets?,
    val dailyRoutine: List<RoutineEvent>,
    val days: Map<String, MealDay>,
    val hydrationPrompt: HydrationPrompt?
)
