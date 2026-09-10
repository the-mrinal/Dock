package com.ambient.tvclock

import java.util.concurrent.CopyOnWriteArraySet

object NoticeCenter {

    @Volatile
    var current: NoticeSnapshot = NoticeSnapshot.EMPTY
        private set

    private val listeners = CopyOnWriteArraySet<(NoticeSnapshot) -> Unit>()

    fun update(snapshot: NoticeSnapshot) {
        current = snapshot
        listeners.forEach { it(snapshot) }
    }

    fun addListener(listener: (NoticeSnapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (NoticeSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
