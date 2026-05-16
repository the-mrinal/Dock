package com.ambient.tvclock

import java.util.concurrent.CopyOnWriteArraySet

object SpotifyQueueCenter {

    @Volatile
    var current: SpotifyQueueSnapshot = SpotifyQueueSnapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(SpotifyQueueSnapshot) -> Unit>()

    fun update(snapshot: SpotifyQueueSnapshot) {
        current = snapshot
        listeners.forEach { it(snapshot) }
    }

    fun addListener(listener: (SpotifyQueueSnapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (SpotifyQueueSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
