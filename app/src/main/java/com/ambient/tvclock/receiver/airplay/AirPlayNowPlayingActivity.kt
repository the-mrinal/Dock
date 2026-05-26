package com.ambient.tvclock.receiver.airplay

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.ambient.tvclock.AlbumArtBlur
import com.ambient.tvclock.R
import com.ambient.tvclock.receiver.ReceiverStateBus
import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen "Now Playing" surface for AirPlay audio sessions.
 *
 * Activates when [ReceiverStateBus.airPlayNowPlaying] goes non-null
 * (track metadata or artwork arrived) and finishes itself when the
 * flow goes back to null (sender disconnected or session moved off
 * audio onto video).
 *
 * Three things to render:
 *  - **Background**: the JPEG iOS sent via `image/jpeg` SET_PARAMETER,
 *    downscaled + blurred. On API 31+ the GPU does the Gaussian; below
 *    that we run [AlbumArtBlur.blurForBackground] on an IO thread.
 *  - **Foreground card**: artwork tile + title/artist/album text.
 *  - **Progress bar**: extrapolated from the last `progress:`
 *    SET_PARAMETER and a [SystemClock.elapsedRealtime] delta so the
 *    bar advances smoothly between iOS's ~1s updates.
 *
 * The activity also holds `FLAG_KEEP_SCREEN_ON` while a session is
 * active — Fire TV's screensaver would otherwise blank the panel
 * after a few minutes of audio-only playback.
 */
class AirPlayNowPlayingActivity : Activity() {

    private lateinit var backgroundView: ImageView
    private lateinit var artworkView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView

    // Coroutine scope is on Main because we update Views; bitmap work
    // explicitly withContext(Dispatchers.IO).
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observerJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgressUiForLatest()
            mainHandler.postDelayed(this, PROGRESS_TICK_INTERVAL_MS)
        }
    }

    // Cached so we don't re-decode the same JPEG every state emission;
    // iOS resends the artwork metadata on a per-track basis but the bytes
    // for one track don't change.
    private var lastArtworkBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        // Keep the panel on while AirPlay audio is active; otherwise the
        // Fire TV screensaver kicks in after a few minutes of audio-only
        // playback and blanks the display the user is staring at.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        backgroundView = findViewById(R.id.blurredBackground)
        artworkView    = findViewById(R.id.artwork)
        titleView      = findViewById(R.id.title)
        artistView     = findViewById(R.id.artist)
        albumView      = findViewById(R.id.album)
        progressBar    = findViewById(R.id.progress)
        elapsedView    = findViewById(R.id.elapsed)
        remainingView  = findViewById(R.id.remaining)

        // API 31+ has a hardware Gaussian we can layer over a tiny
        // downscaled bitmap — much cheaper than the CPU box-blur pyramid.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backgroundView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    BG_RENDER_EFFECT_RADIUS,
                    BG_RENDER_EFFECT_RADIUS,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        observerJob = uiScope.launch {
            ReceiverStateBus.airPlayNowPlaying.collect { state ->
                if (state == null) {
                    // Sender disconnected or session ended — close.
                    finish()
                } else {
                    render(state)
                }
            }
        }
        mainHandler.postDelayed(progressTicker, PROGRESS_TICK_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        observerJob?.cancel()
        observerJob = null
        mainHandler.removeCallbacks(progressTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun render(state: AirPlayNowPlayingState) {
        // Text fields: only set when iOS has sent them. The XML's defaults
        // ("Connected to AirPlay" / "Waiting for track info…") stay until
        // the first DAAP metadata blob arrives.
        state.title?.let { titleView.text = it }
        state.artist?.let { artistView.text = it }
        albumView.text = state.album.orEmpty()

        // Artwork — decode once per track, share bitmap between foreground
        // and background views.
        val jpegBytes = state.artworkJpeg
        if (jpegBytes != null && jpegBytes !== lastArtworkBytes) {
            lastArtworkBytes = jpegBytes
            applyArtwork(jpegBytes)
        } else if (jpegBytes == null) {
            artworkView.setImageDrawable(null)
            backgroundView.setImageDrawable(null)
            lastArtworkBytes = null
        }

        // Progress UI ticks on its own timer; just refresh once on each
        // state update so the bar moves the moment a `progress:` line
        // arrives, not on the next tick.
        updateProgressUi(state.progress)
    }

    private fun applyArtwork(jpegBytes: ByteArray) {
        uiScope.launch {
            val pair = withContext(Dispatchers.IO) {
                try {
                    val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        ?: return@withContext null
                    val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // GPU blur — just hand it a small downscaled source.
                        AlbumArtBlur.downscaleForBackground(src)
                    } else {
                        // CPU pyramid + box blur on older devices.
                        AlbumArtBlur.blurForBackground(src)
                    }
                    src to bg
                } catch (e: Throwable) {
                    Logger.e("Now Playing artwork decode failed (${jpegBytes.size} bytes)", e)
                    null
                }
            } ?: return@launch
            artworkView.setImageBitmap(pair.first)
            backgroundView.setImageBitmap(pair.second)
        }
    }

    private fun updateProgressUiForLatest() {
        val state = ReceiverStateBus.airPlayNowPlaying.value ?: return
        updateProgressUi(state.progress)
    }

    private fun updateProgressUi(progress: AirPlayProgress?) {
        if (progress == null) {
            progressBar.progress = 0
            elapsedView.text = getString(R.string.airplay_now_playing_time_zero)
            remainingView.text = getString(R.string.airplay_now_playing_time_zero)
            return
        }
        val total = progress.totalSeconds()
        val now = progress.elapsedSeconds(SystemClock.elapsedRealtime())
        val fraction = if (total > 0) (now / total).coerceIn(0.0, 1.0) else 0.0
        progressBar.progress = (fraction * progressBar.max).roundToInt()
        elapsedView.text = formatHms(now)
        // Remaining as a negative count so the user reads it as
        // "time left", matching iOS's lock-screen formatting.
        remainingView.text = "-" + formatHms((total - now).coerceAtLeast(0.0))
    }

    private fun formatHms(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }

    companion object {
        // GPU blur radius — pixels, not dp. 60 is a heavy wash that survives
        // the upscale to a full-bleed 1080p / 4K background without showing
        // the source's edges.
        private const val BG_RENDER_EFFECT_RADIUS = 60f

        // Cadence for the smooth-progress timer. 200 ms is fast enough that
        // the bar looks continuous and slow enough that we don't churn the
        // CPU between iOS's 1 Hz `progress:` updates.
        private const val PROGRESS_TICK_INTERVAL_MS = 200L
    }
}
