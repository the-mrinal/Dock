package com.ambient.tvclock.receiver.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tvclock.util.Logger

/**
 * AirPlayVideoPlayer — Plays HLS/MP4 video URLs that iOS senders push via
 * `POST /play` (the AirPlay 1 video URL protocol).
 *
 * WHY: When a user taps the AirPlay button inside YouTube / Photos / Safari /
 * Netflix on an iPhone or iPad, iOS does not mirror the screen. Instead it
 * **closes any existing mirror connection** and sends the receiver a fresh HTTP
 * `POST /play` containing a media URL (almost always an HLS m3u8). The receiver
 * is expected to fetch and play that URL itself, and to expose `GET /playback-info`,
 * `GET /scrub`, `POST /rate`, `POST /scrub`, `POST /stop` for control.
 *
 * Without this path the iOS sender either falls back to mirroring (degraded
 * quality, awkward transition) or simply fails — see the openairplay spec:
 * "as soon as a client starts a video playback, a standard AirPlay connection
 * is made to send the video URL, and mirroring is stopped."
 *
 * HOW: Wraps an ExoPlayer instance on the main thread (ExoPlayer's API is
 * main-thread-only). All HTTP request handlers call into this class from the
 * RTSP IO thread; every mutating call hops to the main thread via [mainHandler],
 * and a [Player.Listener] mirrors the live ExoPlayer state into volatile fields
 * that `/playback-info` / `/scrub` poll without blocking.
 */
class AirPlayVideoPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> }
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ExoPlayer instance, owned and only touched on the main thread.
    @Volatile
    private var player: ExoPlayer? = null

    // ── Snapshot state polled by /playback-info and /scrub ─────────────────
    // Updated from the Player.Listener on the main thread; read from the IO
    // thread that's serving HTTP requests. Volatile keeps the cross-thread
    // read consistent — we don't need atomicity across the group of fields,
    // since iOS already tolerates the values drifting between consecutive
    // polls.
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var bufferedPositionMs: Long = 0
        private set
    @Volatile var ratePlaying: Boolean = false
        private set
    @Volatile var readyToPlay: Boolean = false
        private set
    @Volatile var buffering: Boolean = false
        private set
    @Volatile var ended: Boolean = false
        private set

    /**
     * Latest position observed on the main thread. /scrub reads this directly.
     * Updated by [positionTick] every [POSITION_POLL_MS] while a player is alive.
     */
    @Volatile var positionMs: Long = 0
        private set

    private val positionTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            positionMs = p.currentPosition.coerceAtLeast(0L)
            bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0L)
            mainHandler.postDelayed(this, POSITION_POLL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            readyToPlay = state == Player.STATE_READY || state == Player.STATE_BUFFERING
            buffering = state == Player.STATE_BUFFERING
            ended = state == Player.STATE_ENDED
            val p = player
            if (p != null) {
                val d = p.duration
                durationMs = if (d == androidx.media3.common.C.TIME_UNSET) 0 else d.coerceAtLeast(0L)
            }
            Logger.d("ExoPlayer state=$state durationMs=$durationMs")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ratePlaying = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                onVideoSize(videoSize.width, videoSize.height)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Logger.e("ExoPlayer error: ${error.errorCodeName} (${error.message})", error)
        }
    }

    /**
     * Starts playback of [url] from [startPositionSeconds].
     *
     * Creates an ExoPlayer if needed, binds it to the current Surface, loads the
     * URL as a MediaItem (ExoPlayer auto-detects HLS via the .m3u8 extension /
     * MIME type — no extra MediaSource.Factory required when media3-exoplayer-hls
     * is on the classpath), and calls play().
     *
     * Idempotent: a second call with a new URL replaces the current playback.
     */
    fun play(url: String, startPositionSeconds: Double) {
        Logger.i("AirPlayVideoPlayer.play url=$url start=$startPositionSeconds")
        mainHandler.post {
            ensurePlayer()
            val p = player ?: return@post
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            if (startPositionSeconds > 0.0) {
                p.seekTo((startPositionSeconds * 1000.0).toLong())
            }
            p.playWhenReady = true
            mainHandler.removeCallbacks(positionTick)
            mainHandler.post(positionTick)
            onConnected()
        }
    }

    /** Stops playback and releases the ExoPlayer. Notifies [onDisconnected]. */
    fun stop() {
        Logger.i("AirPlayVideoPlayer.stop")
        mainHandler.post {
            releasePlayer()
            onDisconnected()
        }
    }

    /** rate=0 → pause; rate>0 → play. iOS only ever sends 0 or 1. */
    fun setRate(rate: Float) {
        Logger.d("AirPlayVideoPlayer.setRate $rate")
        mainHandler.post {
            val p = player ?: return@post
            p.playWhenReady = rate > 0f
        }
    }

    /** Seeks to the given position (in seconds). */
    fun scrub(positionSeconds: Double) {
        Logger.d("AirPlayVideoPlayer.scrub $positionSeconds")
        mainHandler.post {
            val p = player ?: return@post
            p.seekTo((positionSeconds * 1000.0).toLong())
        }
    }

    /** Returns true if a player is currently allocated (used by /playback-info). */
    fun isActive(): Boolean = player != null

    private fun ensurePlayer() {
        if (player != null) return
        val exo = ExoPlayer.Builder(context).build().apply {
            addListener(playerListener)
            surfaceProvider()?.let { setVideoSurface(it) }
        }
        player = exo
        Logger.i("ExoPlayer created and bound to surface")
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(positionTick)
        val p = player ?: return
        try {
            p.removeListener(playerListener)
            p.stop()
            p.release()
        } catch (e: Exception) {
            Logger.e("ExoPlayer release error (non-fatal)", e)
        }
        player = null
        positionMs = 0
        durationMs = 0
        bufferedPositionMs = 0
        ratePlaying = false
        readyToPlay = false
        buffering = false
        ended = false
    }

    /**
     * Rebinds the active player to a new Surface.
     *
     * Called when [StreamingOverlay]'s SurfaceView is destroyed and recreated
     * (an aspect-ratio relayout, for example). Mirrors how [VideoDecoder]
     * handles the same event.
     */
    fun setOutputSurface(surface: Surface) {
        mainHandler.post {
            player?.setVideoSurface(surface)
        }
    }

    companion object {
        /**
         * How often the position tick refreshes [positionMs] / [bufferedPositionMs].
         *
         * iOS polls /playback-info roughly every 0.5–1.0s during playback, so a
         * 250ms tick keeps the reported position fresh without burning CPU.
         */
        private const val POSITION_POLL_MS = 250L
    }
}
