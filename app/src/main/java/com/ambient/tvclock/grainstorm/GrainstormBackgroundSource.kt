package com.ambient.tvclock.grainstorm

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ambient.tvclock.background.BackgroundImage
import com.ambient.tvclock.background.BackgroundSource
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * The wallpaper you set in grainstorm, shown on this screen.
 *
 * Polls the library server for what this device should display, caches the
 * image on disk, and emits it. The cache is the point: a dock that boots with
 * no network — or with the server down — still comes up showing yesterday's
 * wallpaper rather than a black rectangle.
 *
 * Cheap by construction. The server ETags the answer by the image hash, so the
 * steady state is one conditional request per interval and no bytes moved. The
 * Mac's rotation only changes the wallpaper once a day.
 */
class GrainstormBackgroundSource(
    context: Context,
    private val repository: WallpaperRepository = WallpaperRepository(context),
) : BackgroundSource {

    override val id: String = ID

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recreated on demand: stop() shuts the executor down to abandon an
    // in-flight sync, and the same instance is started again when the dock
    // returns to the foreground.
    private var io = newExecutor()
    private var listener: ((BackgroundImage) -> Unit)? = null
    private var paused = true
    private var syncing = false
    private var latest: BackgroundImage.Remote? = null

    private val tick = Runnable { syncNow(force = false) }

    override fun start(listener: (BackgroundImage) -> Unit) {
        ensureExecutor()
        this.listener = listener
        paused = false
        // Show the cached wallpaper immediately — before any network work, so
        // a cold start paints in milliseconds rather than after a round trip.
        emitCached()
        scheduleNext()
        syncNow(force = false)
    }

    override fun stop() {
        listener = null
        paused = true
        mainHandler.removeCallbacks(tick)
        io.shutdownNow()
    }

    override fun pause() {
        if (paused) return
        paused = true
        mainHandler.removeCallbacks(tick)
    }

    override fun resume() {
        if (!paused) return
        ensureExecutor()
        paused = false
        emitCached()
        scheduleNext()
        syncNow(force = false)
    }

    override fun current(): BackgroundImage? = latest ?: cachedImage()

    /** There is one wallpaper per screen, so "shuffle" means "check now". */
    override fun shuffleNow() {
        syncNow(force = true)
    }

    override fun onSettingChanged(key: String) {
        when {
            GrainstormPreferences.isIdentityKey(key) -> {
                // Pointed at a different server or renamed: the cached image
                // belongs to the old identity and must not be trusted.
                GrainstormPreferences.forgetCache(appContext)
                latest = null
                syncNow(force = true)
            }
            key == GrainstormPreferences.KEY_POLL_INTERVAL_MS -> scheduleNext()
        }
    }

    private fun emitCached() {
        val image = cachedImage() ?: return
        latest = image
        listener?.invoke(image)
    }

    private fun cachedImage(): BackgroundImage.Remote? {
        val file = repository.cachedFile() ?: return null
        val sha = GrainstormPreferences.cachedSha(appContext).orEmpty()
        return BackgroundImage.Remote(
            uri = file.toURI().toString(),
            // Key on content, not path: a re-download to the same name still
            // repaints, and an unchanged image never re-decodes.
            key = "grainstorm|" + sha.ifBlank { file.lastModified().toString() },
        )
    }

    private fun scheduleNext() {
        mainHandler.removeCallbacks(tick)
        if (paused || listener == null) return
        mainHandler.postDelayed(tick, GrainstormPreferences.pollIntervalMs(appContext))
    }

    private fun syncNow(force: Boolean) {
        if (!force && paused) return
        if (!repository.isConfigured()) return
        if (syncing) return
        syncing = true
        ensureExecutor()
        try {
            io.execute {
                val outcome = try {
                    repository.sync()
                } catch (e: Exception) {
                    Timber.w(e, "grainstorm: sync failed")
                    null
                }
                mainHandler.post {
                    syncing = false
                    when (outcome) {
                        is WallpaperRepository.Sync.Updated -> emitCached()
                        is WallpaperRepository.Sync.Unchanged -> if (latest == null) emitCached()
                        // Offline: keep showing what we have. The next tick retries.
                        is WallpaperRepository.Sync.Failed ->
                            Timber.d("grainstorm: sync unavailable (%s)", outcome.failure)
                        null -> Unit
                    }
                    scheduleNext()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // stop() raced us; nothing to do.
            syncing = false
        }
    }

    private fun ensureExecutor() {
        if (io.isShutdown) io = newExecutor()
    }

    private fun newExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "grainstorm-sync").apply { isDaemon = true }
    }

    companion object {
        const val ID = "grainstorm"
    }
}
