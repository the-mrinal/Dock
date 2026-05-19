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

    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    /**
     * Push channel for surface-lifecycle changes. The activity's `StreamingOverlay`
     * publishes here every time its inner SurfaceView's [Surface] is created or
     * destroyed — including the destroy/recreate cycle that fires when the view's
     * measured dimensions change (which is how the aspect-ratio fix triggers a
     * resize). [AirPlayReceiver] collects this and calls `MediaCodec.setOutputSurface`
     * so the decoder rebinds onto the new surface instead of writing into a
     * destroyed one.
     */
    private val _videoSurface = MutableStateFlow<Surface?>(null)
    val videoSurface: StateFlow<Surface?> = _videoSurface.asStateFlow()

    @Volatile
    private var surfaceProvider: (() -> Surface?)? = null

    fun publishActiveConnection(connection: ActiveConnection?) {
        _activeConnection.value = connection
    }

    fun publishVideoSize(size: VideoSize?) {
        _videoSize.value = size
    }

    fun publishVideoSurface(surface: Surface?) {
        _videoSurface.value = surface
    }

    fun setSurfaceProvider(provider: (() -> Surface?)?) {
        surfaceProvider = provider
    }

    fun currentSurface(): Surface? = surfaceProvider?.invoke()
}
