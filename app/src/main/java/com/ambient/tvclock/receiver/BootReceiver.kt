package com.ambient.tvclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ambient.tvclock.ReceiverPreferences
import timber.log.Timber

/**
 * Starts [ReceiverService] on device boot when both the master toggle and
 * "Start on boot" are enabled. Declared with exported="false" — only the
 * system can fire BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ReceiverPreferences.isReceiverEnabled(context)) {
            Timber.d("BootReceiver: receiver disabled — skipping")
            return
        }
        if (!ReceiverPreferences.isStartOnBootEnabled(context)) {
            Timber.d("BootReceiver: startOnBoot disabled — skipping")
            return
        }
        Timber.i("BootReceiver: starting ReceiverService")
        ReceiverController.start(context)
    }
}
