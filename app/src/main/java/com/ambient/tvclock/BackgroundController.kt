package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager

/**
 * Decides what the home background should be at any moment and drives the
 * [BlurredBackgroundBinder] accordingly. There are three sources:
 *
 *  - **Album art** — the existing behaviour. Track changes update the wash.
 *  - **Unsplash** — a rotating landscape photo from [UnsplashBackgroundSource].
 *    The shuffle cadence comes from BackgroundPreferences.
 *  - **Black** — explicit fade-out; nothing painted behind the foreground.
 *
 * The mode is chosen per-state: one preference picks the source when a song
 * is playing, another picks it when nothing is. Settings changes feed back
 * through a [SharedPreferences.OnSharedPreferenceChangeListener] and trigger
 * an immediate re-evaluation. The controller never *requires* a music event
 * to wake up — it can paint a background while idle.
 *
 * Lifecycle: created in [MainActivity.onCreate], started in [onStart], and
 * stopped in [onStop]. Outlives individual page binders.
 */
class BackgroundController(
    context: Context,
    private val binder: BlurredBackgroundBinder,
    /** Called whenever a new Unsplash photo becomes the active background, or
     *  null when the active source is anything else. Used to drive the
     *  attribution caption AND the minimal-wallpaper home layout on the home
     *  screen. */
    private val onUnsplashPhotoChanged: (UnsplashClient.Photo?) -> Unit,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)
    private val unsplashSource = UnsplashBackgroundSource(appContext)

    private var lastNowPlaying: NowPlayingInfo? = null
    private var lastActivePhoto: UnsplashClient.Photo? = null
    private var ambient: Boolean = false
    private var started: Boolean = false

    private val nowPlayingListener: (NowPlayingInfo?) -> Unit = { info ->
        mainHandler.post {
            lastNowPlaying = info
            // Always let the binder know about the now-playing change so it
            // can roll over the album-art key even if we then switch sources.
            evaluate()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            BackgroundPreferences.KEY_BLUR -> {
                binder.setBlurEnabled(BackgroundPreferences.blurEnabled(appContext))
                // The GPU path picks up the new state immediately, but the CPU
                // (<API 31) prep result is baked into the current frame. Force
                // a re-render so the change is visible right away.
                reapplyCurrent()
            }
            BackgroundPreferences.KEY_WHEN_IDLE,
            BackgroundPreferences.KEY_WHEN_PLAYING -> evaluate()
            BackgroundPreferences.KEY_SHUFFLE_INTERVAL_MS -> unsplashSource.onIntervalChanged()
            BackgroundPreferences.KEY_KEYWORD_PRESETS,
            BackgroundPreferences.KEY_CUSTOM_KEYWORDS -> {
                // Caller of the settings preference is responsible for hitting
                // BackgroundPreferences.markKeywordsChanged() through the 2h
                // lock; here we just react to the resulting state shift.
                unsplashSource.onKeywordsChanged()
                evaluate()
            }
            BackgroundPreferences.KEY_SHUFFLE_SIGNAL -> shuffleNow()
        }
    }

    private val unsplashTickListener: (UnsplashClient.Photo) -> Unit = { photo ->
        mainHandler.post {
            if (!isUnsplashCurrentlySelected()) return@post
            lastActivePhoto = photo
            binder.applyPhotoUrl(photo.imageUrl)
            onUnsplashPhotoChanged(photo)
        }
    }

    fun onStart() {
        if (started) return
        started = true
        binder.setBlurEnabled(BackgroundPreferences.blurEnabled(appContext))
        NowPlayingCenter.addListener(nowPlayingListener)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        unsplashSource.start(unsplashTickListener)
        evaluate()
    }

    fun onStop() {
        if (!started) return
        started = false
        NowPlayingCenter.removeListener(nowPlayingListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        unsplashSource.stop()
        // Drop the 14 MB wallpaper bitmap so it isn't sitting in the heap
        // while the user is in Settings or the dock is otherwise offscreen.
        // The Unsplash URL cache survives (SharedPreferences-persisted), so
        // onStart re-decodes immediately without a fresh API call.
        binder.releaseBitmap()
        if (lastActivePhoto != null) {
            lastActivePhoto = null
            onUnsplashPhotoChanged(null)
        }
    }

    fun setAmbient(ambient: Boolean) {
        if (this.ambient == ambient) return
        this.ambient = ambient
        binder.setAmbient(ambient)
        if (ambient) {
            unsplashSource.pause()
            onUnsplashPhotoChanged(null)
        } else {
            if (isUnsplashCurrentlySelected()) unsplashSource.resume()
            evaluate()
        }
    }

    /** Currently shown Unsplash photo, or null if the source is album-art / black. */
    fun activePhoto(): UnsplashClient.Photo? = lastActivePhoto

    /**
     * Cycle to the next cached Unsplash photo — invoked by the user via the
     * Settings shuffle button or the D-pad UP shortcut on Home. No-op when
     * the active source isn't Unsplash (nothing to shuffle).
     */
    fun shuffleNow() {
        if (!isUnsplashCurrentlySelected()) return
        unsplashSource.shuffleNow()
    }

    /**
     * Drop the in-memory background bitmap on a memory-pressure trim signal.
     * The Unsplash URL cache survives (it's persisted), so the next evaluate
     * can re-decode without hitting the network beyond the JPEG fetch.
     */
    fun onTrimMemory(level: Int) {
        // TRIM_MEMORY_UI_HIDDEN (20) and above mean the activity is no longer
        // visible. Lighter signals (RUNNING_*) leave the bitmap in place so a
        // brief Settings detour doesn't flash black on return.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            binder.releaseBitmap()
            if (lastActivePhoto != null) {
                lastActivePhoto = null
                onUnsplashPhotoChanged(null)
            }
        }
    }

    private fun evaluate() {
        if (ambient) return // binder.setAmbient already faded out.
        val playing = isSongPlaying(lastNowPlaying)
        val source = if (playing) {
            BackgroundPreferences.whenPlayingSource(appContext)
        } else {
            BackgroundPreferences.whenIdleSource(appContext)
        }
        when (source) {
            BackgroundPreferences.Source.ALBUM_ART -> renderAlbumArt()
            BackgroundPreferences.Source.UNSPLASH -> renderUnsplash()
            BackgroundPreferences.Source.BLACK -> renderBlack()
        }
    }

    private fun renderAlbumArt() {
        unsplashSource.pause()
        if (lastActivePhoto != null) {
            lastActivePhoto = null
            onUnsplashPhotoChanged(null)
        }
        binder.bind(lastNowPlaying)
    }

    private fun renderBlack() {
        unsplashSource.pause()
        if (lastActivePhoto != null) {
            lastActivePhoto = null
            onUnsplashPhotoChanged(null)
        }
        binder.fadeBackgroundOut()
    }

    private fun renderUnsplash() {
        // Hand off to the source. It will either:
        //   - emit the cached current photo back through the tick listener
        //     immediately (no API call), or
        //   - fetch a page in the background and emit when ready.
        unsplashSource.resume()
        val current = unsplashSource.currentPhoto()
        if (current != null) {
            lastActivePhoto = current
            binder.applyPhotoUrl(current.imageUrl)
            onUnsplashPhotoChanged(current)
        }
        // If `current` is null we leave the previous frame on screen until
        // the source's first fetch completes — avoids a flash of black.
    }

    private fun reapplyCurrent() {
        if (ambient) return
        val playing = isSongPlaying(lastNowPlaying)
        val source = if (playing) {
            BackgroundPreferences.whenPlayingSource(appContext)
        } else {
            BackgroundPreferences.whenIdleSource(appContext)
        }
        when (source) {
            BackgroundPreferences.Source.ALBUM_ART -> {
                // bind() short-circuits on identical key; force a fresh apply.
                binder.fadeBackgroundOut()
                binder.bind(lastNowPlaying)
            }
            BackgroundPreferences.Source.UNSPLASH -> {
                val photo = unsplashSource.currentPhoto() ?: return
                binder.fadeBackgroundOut()
                binder.applyPhotoUrl(photo.imageUrl)
            }
            BackgroundPreferences.Source.BLACK -> binder.fadeBackgroundOut()
        }
    }

    private fun isUnsplashCurrentlySelected(): Boolean {
        if (ambient) return false
        val playing = isSongPlaying(lastNowPlaying)
        val source = if (playing) {
            BackgroundPreferences.whenPlayingSource(appContext)
        } else {
            BackgroundPreferences.whenIdleSource(appContext)
        }
        return source == BackgroundPreferences.Source.UNSPLASH
    }

    private fun isSongPlaying(info: NowPlayingInfo?): Boolean =
        info != null && info.hasActiveSession && info.artwork != null
}
