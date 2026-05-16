package com.ambient.tvclock

import android.media.session.MediaController
import java.util.concurrent.CopyOnWriteArraySet

object NowPlayingCenter {

    @Volatile
    var current: NowPlayingInfo? = null
        private set

    @Volatile
    var activeController: MediaController? = null
        private set

    private val listeners = CopyOnWriteArraySet<(NowPlayingInfo?) -> Unit>()

    fun update(info: NowPlayingInfo?, controller: MediaController?) {
        current = info
        activeController = controller
        listeners.forEach { it(info) }
    }

    fun addListener(listener: (NowPlayingInfo?) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (NowPlayingInfo?) -> Unit) {
        listeners.remove(listener)
    }
}
