package com.ambient.tvclock

import android.app.Application
import com.ambient.tvclock.receiver.ReceiverController
import com.ambient.tvclock.vpn.VpnPreferences
import com.ambient.tvclock.vpn.VpnState
import com.ambient.tvclock.vpn.WireGuardStateBus
import timber.log.Timber

class DockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // The state bus starts at NoConfig and is only ever updated by the
        // controller's start/stop. After a cold start the tunnel is down even
        // if the user had it enabled before — so seed Down whenever a config
        // is on disk so the UI doesn't claim "Not set up" until the user
        // toggles VPN.
        if (VpnPreferences.hasConfig(this)) {
            WireGuardStateBus.publish(VpnState.Down)
        }

        // The mirroring receiver is a foreground service that dies with the
        // process (and with every reinstall during dev). The Settings master
        // toggle is the only thing that ever calls start() — without this
        // line, `receiver_enabled=true` in prefs is silently meaningless and
        // phones can't discover the Fire TV until the user goes Settings →
        // toggle off → toggle on. Re-run start() on every process start so
        // discovery resumes automatically.
        if (ReceiverPreferences.isReceiverEnabled(this)) {
            ReceiverController.start(this)
        }
    }
}
