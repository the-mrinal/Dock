package com.ambient.tvclock

import java.util.concurrent.CopyOnWriteArraySet

object MealPlanCenter {

    @Volatile
    var current: MealPlanSnapshot = MealPlanSnapshot(
        plan = null,
        lastUpdatedMillis = 0L,
        error = MealPlanError.NONE,
        isFromCache = false
    )
        private set

    private val listeners = CopyOnWriteArraySet<(MealPlanSnapshot) -> Unit>()

    fun update(snapshot: MealPlanSnapshot) {
        current = snapshot
        listeners.forEach { it(snapshot) }
    }

    fun addListener(listener: (MealPlanSnapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (MealPlanSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
