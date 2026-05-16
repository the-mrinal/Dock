package com.ambient.tvclock

import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper

class NowPlayingPoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pollIntervalMs = 1500L

    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            NowPlayingSessionReader.publish(appContext)
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    fun start() {
        stop()
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

        handler.post(pollRunnable)
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
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
    }
}
