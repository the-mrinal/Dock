package com.ambient.tvclock.receiver.cast

import android.content.Context
import com.ambient.tvclock.receiver.ProtocolState
import com.ambient.tvclock.util.Logger

/**
 * CastReceiver — Google Cast receiver stub.
 *
 * WHY: Google Cast (Chromecast protocol) allows Chrome browser, Android, and iOS
 * devices to cast their screen or content to PhairPlay. It complements AirPlay
 * (macOS only) and Miracast (Windows/Android without setup) for a universal receiver.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1 (this stub): Architecture defined, availability check, graceful fallback
 * - Phase 2 (M7): Full Cast SDK integration
 *
 * IMPORTANT PRE-CONDITIONS for Cast support:
 * 1. Register PhairPlay as a Cast application at:
 *    https://cast.google.com/publish → "ADD NEW APPLICATION" → "Custom Receiver"
 * 2. Note the Application ID assigned by Google
 * 3. Add the App ID to `res/values/cast_options.xml` (see below)
 * 4. Add `play-services-cast-framework` dependency to build.gradle.kts
 *
 * FIRE TV LIMITATION:
 * Amazon Fire TV does NOT include Google Play Services, which is required by the
 * Cast SDK. The `firetv` flavor automatically disables Cast. The [isAvailable]
 * check handles this gracefully.
 *
 * Cast protocol stack (simplified):
 *   mDNS advertisement (Cast app ID) → TLS connection (port 8009) →
 *   Cast protocol (CASTV2) → Media receiver session → Content display
 *
 * Example (future usage):
 *   val receiver = CastReceiver(context) { state -> updateUI(state) }
 *   if (CastReceiver.isAvailable(context)) {
 *       receiver.start()
 *   }
 */
class CastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {

    /**
     * Starts the Cast receiver.
     *
     * First checks if Cast is available on this device (requires Google Play Services).
     * On Fire TV and other GMS-less devices, emits DISABLED state and returns.
     *
     * TODO Phase 7: Initialize CastReceiverContext from Cast SDK.
     */
    fun start() {
        if (!isAvailable(context)) {
            Logger.w("Google Cast not available on this device (missing Google Play Services)")
            onStateChanged(ProtocolState.DISABLED)
            return
        }

        Logger.i("CastReceiver starting")
        // TODO Phase 7: CastReceiverContext.getInstance().start()
        // TODO Phase 7: Register CastReceiverContext.SessionManagerListener
        onStateChanged(ProtocolState.ADVERTISING)
    }

    /**
     * Stops the Cast receiver and releases SDK resources.
     *
     * TODO Phase 7: CastReceiverContext.getInstance().stop()
     */
    fun stop() {
        Logger.i("CastReceiver stopping")
        // TODO Phase 7: CastReceiverContext.getInstance().stop()
        onStateChanged(ProtocolState.DISABLED)
    }

    companion object {

        /**
         * Returns true if Google Cast is available on this device.
         *
         * Cast requires Google Play Services (GMS). On Amazon Fire TV and
         * other AOSP-based devices without GMS, Cast is not available.
         *
         * WHY check here rather than in the firetv flavor: even on Google TV,
         * devices without GMS exist (some Android TV boxes). This runtime check
         * is more robust than a build-time check.
         *
         * @param context Any Android context.
         * @return true if GMS is available and Cast can be initialized.
         */
        fun isAvailable(context: Context): Boolean {
            return try {
                // Check if Google Play Services is available
                // We use the package manager rather than importing GoogleApiAvailability
                // to avoid a compile-time dependency on GMS in the firetv flavor
                context.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * The Cast Application ID registered on the Google Cast Developer Console.
         *
         * TODO Phase 7: Replace this placeholder with the real registered App ID.
         * Register at: https://cast.google.com/publish
         *
         * The App ID tells senders (Chrome, Android) which Cast receiver app to use.
         * Without a valid App ID, Cast connections will be rejected.
         */
        const val CAST_APP_ID = "TODO_REGISTER_YOUR_CAST_APP_ID"
    }
}
