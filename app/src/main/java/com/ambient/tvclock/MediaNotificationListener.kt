package com.ambient.tvclock

import android.content.ComponentName
import android.media.session.MediaController
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListener : NotificationListenerService() {

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            NowPlayingSessionReader.publish(this@MediaNotificationListener)
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            NowPlayingSessionReader.publish(this@MediaNotificationListener)
        }
    }

    private val controllerCallbacks = LinkedHashMap<MediaController, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshControllers()
    }

    override fun onListenerDisconnected() {
        detachAllControllers()
        NowPlayingCenter.update(null, null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshControllers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshControllers()
    }

    private fun refreshControllers() {
        val controllers = NowPlayingSessionReader.getActiveControllers(this)
        syncControllers(controllers)
        NowPlayingSessionReader.publish(this)
    }

    private fun syncControllers(controllers: List<MediaController>) {
        val activeSet = controllers.toSet()
        controllerCallbacks.keys.filter { it !in activeSet }.forEach { detachController(it) }
        controllers.forEach { attachController(it) }
    }

    private fun attachController(controller: MediaController) {
        if (controllerCallbacks.containsKey(controller)) {
            return
        }
        controller.registerCallback(sessionCallback)
        controllerCallbacks[controller] = sessionCallback
    }

    private fun detachController(controller: MediaController) {
        controllerCallbacks.remove(controller)?.let { callback ->
            controller.unregisterCallback(callback)
        }
    }

    private fun detachAllControllers() {
        controllerCallbacks.keys.toList().forEach { detachController(it) }
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            if (!NotificationAccess.isListenerEnabled(context)) {
                return
            }
            try {
                requestRebind(
                    ComponentName(context, MediaNotificationListener::class.java)
                )
            } catch (_: Exception) {
                // Ignored when rebind is unavailable.
            }
        }
    }
}
