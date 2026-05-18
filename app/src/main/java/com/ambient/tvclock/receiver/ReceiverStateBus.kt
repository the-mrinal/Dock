package com.ambient.tvclock.receiver

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between [ReceiverService] (background) and [com.ambient.tvclock.MainActivity]
 * (foreground). Avoids the binder dance — service publishes state, activity subscribes.
 *
 * Mirrors the pattern used by NowPlayingCenter / CalendarCenter / SpotifyQueueCenter.
 */
object ReceiverStateBus {

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    @Volatile
    private var surfaceProvider: (() -> Surface?)? = null

    fun publishActiveConnection(connection: ActiveConnection?) {
        _activeConnection.value = connection
    }

    fun setSurfaceProvider(provider: (() -> Surface?)?) {
        surfaceProvider = provider
    }

    fun currentSurface(): Surface? = surfaceProvider?.invoke()
}
