package com.ambient.tvclock

import java.util.concurrent.CopyOnWriteArraySet

object CalendarCenter {

    @Volatile
    var current: CalendarSnapshot = CalendarSnapshot(emptyList(), 0L)
        private set

    private val listeners = CopyOnWriteArraySet<(CalendarSnapshot) -> Unit>()

    fun update(snapshot: CalendarSnapshot) {
        current = snapshot
        listeners.forEach { it(snapshot) }
    }

    fun addListener(listener: (CalendarSnapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (CalendarSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
