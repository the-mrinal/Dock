package com.ambient.tvclock

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager

object NowPlayingSessionReader {

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, MediaNotificationListener::class.java)

    fun getActiveControllers(context: Context): List<MediaController> {
        if (!NotificationAccess.isListenerEnabled(context)) {
            return emptyList()
        }
        val sessionManager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return try {
            sessionManager.getActiveSessions(listenerComponent(context))
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun getBestController(context: Context): MediaController? {
        return MediaSessionHelper.pickBestController(getActiveControllers(context))
    }

    fun readNowPlaying(context: Context): NowPlayingInfo? {
        if (!NowPlayingPreferences.isEnabled(context)) {
            return null
        }
        val best = getBestController(context)
        return best?.let { MediaSessionHelper.toNowPlaying(it) }
    }

    fun publish(context: Context) {
        val controller = getBestController(context)
        val info = controller?.let { MediaSessionHelper.toNowPlaying(it) }
        NowPlayingCenter.update(info, controller)
    }
}
