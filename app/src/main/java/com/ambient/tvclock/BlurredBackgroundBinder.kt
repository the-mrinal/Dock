package com.ambient.tvclock

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Binds the full-bleed home/ambient background to the current track's album
 * artwork. The bitmap is downsampled and bilinearly upscaled by
 * [AlbumArtBlur.blurForBackground] off the main thread, then crossfaded into
 * place via a [TransitionDrawable]. The view's alpha is held low enough that
 * foreground text (clock, widgets, ambient labels) stays comfortably readable.
 *
 * No music / no artwork → fades the background fully out so we land back on
 * the plain `bg_screen` color.
 */
class BlurredBackgroundBinder(private val imageView: ImageView) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null
    // Tracks the last drawable that was fully phased in. We keep it around so
    // we can hand it to the *next* TransitionDrawable as the "from" layer
    // without nesting TransitionDrawables (which renders incorrectly on song
    // changes — the inner transition is left in an indeterminate state).
    private var currentDrawable: Drawable? = null
    private var ambient: Boolean = false

    init {
        // On API 31+ ask the GPU for an extra Gaussian smoothing pass on top of
        // our software pyramid blur. This eats the last visible pixel grid that
        // can sneak through after centerCrop-ing the bitmap to the TV's native
        // resolution, with negligible cost (the bitmap we feed in is small).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imageView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    RENDER_EFFECT_RADIUS,
                    RENDER_EFFECT_RADIUS,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    private val targetAlpha: Float
        get() = if (ambient) AMBIENT_ALPHA else ACTIVE_ALPHA

    fun bind(info: NowPlayingInfo?) {
        // In ambient/screensaver mode we want a pure black background so the
        // clock is the lone bright element. Skip applying any artwork; if a
        // previous bitmap was visible, fade it out.
        if (ambient) {
            if (lastKey != null) {
                lastKey = null
                fadeOut()
            }
            return
        }

        val art = info?.artwork
        val show = info?.hasActiveSession == true && art != null

        if (!show) {
            if (lastKey != null) {
                lastKey = null
                fadeOut()
            }
            return
        }

        val track = info!!
        // Belt-and-braces key: include every metadata field. Some sources reuse
        // mediaUri across tracks (or leave it blank) so we mix in title/artist
        // /album to make sure a real track change always invalidates the key.
        val key = buildString {
            append(track.mediaUri)
            append('|')
            append(track.title)
            append('|')
            append(track.artist)
            append('|')
            append(track.album)
        }
        if (key == lastKey) return
        lastKey = key

        executor.execute {
            val blurred = try {
                AlbumArtBlur.blurForBackground(art!!)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (lastKey != key) return@post
                if (blurred != null) applyBitmap(blurred)
            }
        }
    }

    /**
     * Slightly dim the background while ambient/screensaver is engaged so the
     * lone clock still reads as the primary element on the screen.
     */
    fun setAmbient(ambient: Boolean) {
        if (this.ambient == ambient) return
        this.ambient = ambient
        if (ambient) {
            // Screensaver: drop the artwork wash entirely so the wall behind
            // the clock is solid black.
            if (lastKey != null) {
                lastKey = null
                fadeOut()
            }
        } else {
            // Coming back from ambient: let the next bind() repopulate the
            // background from the current now-playing info.
            NowPlayingCenter.current?.let { bind(it) }
        }
    }

    private fun applyBitmap(bitmap: Bitmap) {
        val from: Drawable = currentDrawable ?: ColorDrawable(Color.TRANSPARENT)
        val to = BitmapDrawable(imageView.resources, bitmap)
        val transition = TransitionDrawable(arrayOf(from, to))
        transition.isCrossFadeEnabled = true
        imageView.setImageDrawable(transition)
        transition.startTransition(CROSSFADE_MS.toInt())
        // The end state of this transition is `to`; stash it so the next
        // bind starts its fade from this bitmap rather than from a stale
        // TransitionDrawable.
        currentDrawable = to

        imageView.animate().cancel()
        if (imageView.alpha < targetAlpha - 0.01f) {
            imageView.animate()
                .alpha(targetAlpha)
                .setDuration(FADE_IN_MS)
                .start()
        } else {
            imageView.alpha = targetAlpha
        }
    }

    private fun fadeOut() {
        imageView.animate().cancel()
        imageView.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                if (lastKey == null) {
                    imageView.setImageDrawable(null)
                    currentDrawable = null
                }
            }
            .start()
    }

    companion object {
        // Active dashboard: visible enough to feel like an ambient backdrop, dim
        // enough that white text and widget cards still read clearly on top.
        private const val ACTIVE_ALPHA = 0.32f
        // Screensaver / ambient: artwork is hidden entirely (see setAmbient).
        // Kept as a documentation anchor; targetAlpha branch is unused now.
        private const val AMBIENT_ALPHA = 0.0f

        // Light extra GPU blur layered on the already-blurred bitmap. Small
        // because the input is already smooth; this is just an anti-grid pass.
        private const val RENDER_EFFECT_RADIUS = 8f

        private const val FADE_IN_MS = 700L
        private const val FADE_OUT_MS = 700L
        private const val CROSSFADE_MS = 900L
        private const val ACTIVE_FADE_MS = 600L
        private const val AMBIENT_FADE_MS = 1200L

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-bg-blur").apply { isDaemon = true }
        }
    }
}
