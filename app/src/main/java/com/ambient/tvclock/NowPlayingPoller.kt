package com.ambient.tvclock

import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper

/**
 * Listens for active media session changes (Spotify etc.) and republishes a
 * NowPlayingInfo snapshot whenever the system reports a change.
 *
 * We rely primarily on:
 *   1. MediaNotificationListener -> per-controller metadata / playback-state callbacks
 *   2. MediaSessionManager.OnActiveSessionsChangedListener (registered here)
 *
 * A low-cadence safety refresh fires occasionally to cover the rare cases where
 * Spotify Connect or other apps update state without triggering a callback.
 */
class NowPlayingPoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val safetyRefreshIntervalMs = 15_000L

    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var started = false

    private val safetyRefreshRunnable = object : Runnable {
        override fun run() {
            NowPlayingSessionReader.publish(appContext)
            handler.postDelayed(this, safetyRefreshIntervalMs)
        }
    }

    fun start() {
        if (started) {
            return
        }
        NotificationAccess.requestListenerReconnect(appContext)
        NowPlayingSessionReader.publish(appContext)

        if (!NotificationAccess.isListenerEnabled(appContext)) {
            return
        }

        val sessionManager =
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val best = MediaSessionHelper.pickBestController(controllers.orEmpty())
            val info = best?.let { MediaSessionHelper.toNowPlaying(it) }
            NowPlayingCenter.update(info, best)
        }
        sessionsListener = listener
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                listener,
                NowPlayingSessionReader.listenerComponent(appContext),
                handler
            )
        } catch (_: SecurityException) {
            sessionsListener = null
        }

        handler.removeCallbacks(safetyRefreshRunnable)
        handler.postDelayed(safetyRefreshRunnable, safetyRefreshIntervalMs)
        started = true
    }

    fun stop() {
        handler.removeCallbacks(safetyRefreshRunnable)
        sessionsListener?.let { listener ->
            try {
                val sessionManager =
                    appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                sessionManager.removeOnActiveSessionsChangedListener(listener)
            } catch (_: Exception) {
                // Ignored.
            }
        }
        sessionsListener = null
        started = false
    }

    fun publishNow() {
        NowPlayingSessionReader.publish(appContext)
    }
}
