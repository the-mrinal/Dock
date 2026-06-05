package com.ambient.tvclock

enum class MealPlanError { NONE, URL_EMPTY, FETCH_FAILED, PARSE_FAILED }

data class MealPlanSnapshot(
    val plan: MealPlan?,
    val lastUpdatedMillis: Long,
    val error: MealPlanError,
    val isFromCache: Boolean
)
