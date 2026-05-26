package com.ambient.tvclock.receiver

import android.view.Surface
import com.ambient.tvclock.receiver.airplay.AirPlayNowPlayingMetadata
import com.ambient.tvclock.receiver.airplay.AirPlayNowPlayingState
import com.ambient.tvclock.receiver.airplay.AirPlayProgress
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

    /**
     * Current AirPlay audio "Now Playing" snapshot — metadata, artwork, and
     * progress merged into one immutable record. `null` whenever no AirPlay
     * audio session is active. Updates arrive piecemeal via the
     * `publishAirPlay*` methods below; the bus merges each partial into the
     * existing snapshot so an artwork-only `SET_PARAMETER` does not wipe a
     * previously-received title.
     */
    private val _airPlayNowPlaying = MutableStateFlow<AirPlayNowPlayingState?>(null)
    val airPlayNowPlaying: StateFlow<AirPlayNowPlayingState?> = _airPlayNowPlaying.asStateFlow()

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

    // ─── AirPlay Now Playing ─────────────────────────────────────────────

    fun publishAirPlayMetadata(meta: AirPlayNowPlayingMetadata) {
        _airPlayNowPlaying.value = (_airPlayNowPlaying.value ?: AirPlayNowPlayingState())
            .withMetadata(meta)
    }

    fun publishAirPlayArtwork(jpegBytes: ByteArray) {
        _airPlayNowPlaying.value = (_airPlayNowPlaying.value ?: AirPlayNowPlayingState())
            .copy(artworkJpeg = jpegBytes)
    }

    fun publishAirPlayProgress(progress: AirPlayProgress) {
        _airPlayNowPlaying.value = (_airPlayNowPlaying.value ?: AirPlayNowPlayingState())
            .copy(progress = progress)
    }

    /** Clears the snapshot when an AirPlay audio session ends. */
    fun clearAirPlayNowPlaying() {
        _airPlayNowPlaying.value = null
    }
}
