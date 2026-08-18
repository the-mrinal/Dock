package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.ambient.tvclock.background.AmbientBackgroundPolicy
import com.ambient.tvclock.background.BackgroundImage
import com.ambient.tvclock.background.BackgroundSource
import com.ambient.tvclock.background.BackgroundSourceRegistry
import com.ambient.tvclock.background.BackgroundSurface
import com.ambient.tvclock.grainstorm.GrainstormBackgroundSource

/**
 * Decides what the background should be at any moment and drives a
 * [BackgroundSurface] accordingly.
 *
 * Two preferences pick the source — one for when a song is playing, one for
 * when nothing is — and a third decides what the screensaver shows once the
 * dock goes idle. Sources come from a [BackgroundSourceRegistry], so adding a
 * background is a registration rather than an edit here.
 *
 * Two ids are handled directly and always will be: `album_art` arrives as a
 * bitmap through MediaSession rather than as a fetchable image, and `black` is
 * the absence of one. Everything else is a registered source.
 *
 * Lifecycle: created in [MainActivity.onCreate], started in `onStart`, stopped
 * in `onStop`. Outlives individual page binders.
 */
class BackgroundController(
    context: Context,
    private val surface: BackgroundSurface,
    /** Called when the active background changes, so the host can swap the
     *  home layout and show any required attribution. Null means nothing is
     *  painted. */
    private val onBackgroundChanged: (BackgroundImage.Remote?) -> Unit,
    registry: BackgroundSourceRegistry? = null,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    private val sources: BackgroundSourceRegistry = registry ?: defaultRegistry(appContext)

    private var lastNowPlaying: NowPlayingInfo? = null
    private var lastPainted: BackgroundImage.Remote? = null
    private var ambient: Boolean = false
    private var started: Boolean = false

    private val nowPlayingListener: (NowPlayingInfo?) -> Unit = { info ->
        mainHandler.post {
            lastNowPlaying = info
            evaluate()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            BackgroundPreferences.KEY_BLUR -> {
                surface.setBlurEnabled(BackgroundPreferences.blurEnabled(appContext))
                // The GPU path picks the new state up immediately, but the CPU
                // (<API 31) prep result is baked into the current frame. Force
                // a re-render so the change is visible right away.
                reapplyCurrent()
            }
            BackgroundPreferences.KEY_WHEN_IDLE,
            BackgroundPreferences.KEY_WHEN_PLAYING,
            BackgroundPreferences.KEY_WHEN_AMBIENT -> evaluate()
            BackgroundPreferences.KEY_SHUFFLE_SIGNAL -> shuffleNow()
            else -> key?.let { changed ->
                // Anything else is a source's own setting. Only sources that
                // have actually been built can care.
                sources.instantiated().forEach { it.onSettingChanged(changed) }
                evaluate()
            }
        }
    }

    /** Every source emits through here; only the active one is painted. */
    private fun listenerFor(sourceId: String): (BackgroundImage) -> Unit = { image ->
        mainHandler.post {
            if (activeSourceId() == sourceId) paint(image)
        }
    }

    fun onStart() {
        if (started) return
        started = true
        surface.setBlurEnabled(BackgroundPreferences.blurEnabled(appContext))
        NowPlayingCenter.addListener(nowPlayingListener)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Start only the source that is actually wanted; the rest stay unbuilt
        // and cost nothing.
        activeSource()?.let { it.start(listenerFor(it.id)) }
        evaluate()
    }

    fun onStop() {
        if (!started) return
        started = false
        NowPlayingCenter.removeListener(nowPlayingListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sources.instantiated().forEach { it.stop() }
        // Drop the ~14 MB wallpaper bitmap so it is not sitting in the heap
        // while the user is in Settings or the dock is otherwise offscreen.
        // Sources keep their own caches, so onStart repaints without refetching.
        surface.releaseBitmap()
        if (lastPainted != null) {
            lastPainted = null
            onBackgroundChanged(null)
        }
    }

    /**
     * Enter or leave the screensaver.
     *
     * This used to fade the background out unconditionally, which is why the
     * screensaver was a black screen whatever you had selected. Now the
     * background for the idle state is its own decision.
     */
    fun setAmbient(ambient: Boolean) {
        if (this.ambient == ambient) return
        this.ambient = ambient
        surface.setAmbient(ambient)
        evaluate()
    }

    /** The image currently painted, or null when the background is black. */
    fun activePhoto(): BackgroundImage.Remote? = lastPainted

    /**
     * Move the active source on — the Settings shuffle button and the D-pad UP
     * shortcut on Home. A no-op for sources with only one image to give.
     */
    fun shuffleNow() {
        activeSource()?.shuffleNow()
    }

    /** Drop the in-memory bitmap on a memory-pressure trim signal. The source
     *  caches survive, so the next evaluate repaints without a fetch. */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            surface.releaseBitmap()
            if (lastPainted != null) {
                lastPainted = null
                onBackgroundChanged(null)
            }
        }
    }

    // ---- deciding ----

    /**
     * The single place that answers "what should be on screen right now" —
     * previously duplicated across four methods that could disagree.
     */
    private fun activeSourceId(): String {
        val awake = if (isSongPlaying(lastNowPlaying)) {
            BackgroundPreferences.whenPlayingSource(appContext)
        } else {
            BackgroundPreferences.whenIdleSource(appContext)
        }
        if (!ambient) return awake
        val policy = AmbientBackgroundPolicy.fromPreference(
            BackgroundPreferences.whenAmbientSource(appContext)
        )
        return policy.ambientSourceId(awake) ?: BackgroundPreferences.SOURCE_BLACK
    }

    private fun activeSource(): BackgroundSource? = sources.get(activeSourceId())

    private fun evaluate() {
        val id = activeSourceId()

        // Anything that is not wanted right now stops emitting and stops
        // spending battery.
        sources.instantiated().forEach { if (it.id != id) it.pause() }

        when (id) {
            BackgroundPreferences.SOURCE_ALBUM_ART -> paint(BackgroundImage.AlbumArt(lastNowPlaying))
            BackgroundPreferences.SOURCE_BLACK -> paint(null)
            else -> {
                val source = sources.get(id)
                if (source == null) {
                    // A preference naming a source this build does not have.
                    paint(null)
                    return
                }
                if (started) source.start(listenerFor(id))
                source.resume()
                // Paint what it already has, if anything; otherwise leave the
                // previous frame up until its first emit, so switching sources
                // never flashes black.
                source.current()?.let(::paint)
            }
        }
    }

    private fun paint(image: BackgroundImage?) {
        when (image) {
            is BackgroundImage.Remote -> {
                surface.show(image)
                if (lastPainted != image) {
                    lastPainted = image
                    onBackgroundChanged(image)
                }
            }
            else -> {
                surface.show(image)
                if (lastPainted != null) {
                    lastPainted = null
                    onBackgroundChanged(null)
                }
            }
        }
    }

    private fun reapplyCurrent() {
        val id = activeSourceId()
        surface.show(null)
        when (id) {
            BackgroundPreferences.SOURCE_ALBUM_ART -> surface.show(BackgroundImage.AlbumArt(lastNowPlaying))
            BackgroundPreferences.SOURCE_BLACK -> Unit
            else -> sources.get(id)?.current()?.let(surface::show)
        }
    }

    private fun isSongPlaying(info: NowPlayingInfo?): Boolean =
        info != null && info.hasActiveSession && info.artwork != null

    companion object {
        /**
         * Every background this build knows how to paint. A new one is a line
         * here — nothing in the controller, the preferences enum, or the
         * surface changes.
         */
        fun defaultRegistry(context: Context): BackgroundSourceRegistry =
            BackgroundSourceRegistry.builder()
                .register(BackgroundPreferences.SOURCE_UNSPLASH) { UnsplashBackgroundSource(context) }
                .register(GrainstormBackgroundSource.ID) { GrainstormBackgroundSource(context) }
                .build()
    }
}
