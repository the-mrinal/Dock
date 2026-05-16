package com.ambient.tvclock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

object NotificationAccess {

    fun isListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(
            context,
            MediaNotificationListener::class.java
        ).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun openListenerSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    /** Fire TV often needs this in addition to settings put secure. */
    fun adbGrantCommands(context: Context): String {
        val component = ComponentName(
            context,
            MediaNotificationListener::class.java
        ).flattenToString()
        return """
            adb shell settings put secure enabled_notification_listeners $component
            adb shell cmd notification allow_listener $component
        """.trimIndent()
    }

    fun requestListenerReconnect(context: Context) {
        if (!isListenerEnabled(context)) {
            return
        }
        MediaNotificationListener.requestRefresh(context)
        toggleListenerComponent(context)
    }

    private fun toggleListenerComponent(context: Context) {
        val component = ComponentName(context, MediaNotificationListener::class.java)
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
