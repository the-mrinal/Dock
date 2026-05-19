package com.ambient.tvclock.receiver

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ambient.tvclock.util.Logger

/**
 * ReceiverController — Provides a clean API to start, stop, and restart the ReceiverService.
 *
 * WHY: Sending Intent actions to a service is verbose and error-prone if done directly
 * from the UI. This class centralizes all service control commands so the UI just calls
 * `ReceiverController.start(context)` — no Intent construction in Fragment code.
 *
 * HOW: All methods are on the companion object (static-like). No instance needed.
 * Call from any Fragment or ViewModel that has a Context.
 *
 * Example:
 *   // From a Fragment or Activity:
 *   ReceiverController.start(requireContext())
 *   ReceiverController.stop(requireContext())
 *   ReceiverController.restart(requireContext())
 */
object ReceiverController {

    // Exposed (internal) for unit tests: `Build.VERSION.SDK_INT` reads as 0 in the
    // AGP unit-test stub jar regardless of running platform, which would always force
    // the pre-26 fallback path. Tests override this with the platform they want to simulate.
    @Suppress("MemberVisibilityCanBePrivate")
    internal var sdkInt: Int = Build.VERSION.SDK_INT

    /**
     * Starts the ReceiverService if it is not already running.
     *
     * Uses [ContextCompat.startForegroundService] which works correctly on all
     * Android versions (API 26+ requires the foreground service to be started
     * this way to avoid background start restrictions).
     *
     * @param context Any valid Android context.
     */
    fun start(context: Context) {
        Logger.i("ReceiverController: start()")
        val intent = buildIntent(context, ReceiverService.ACTION_START)
        startForegroundServiceCompat(context, intent)
    }

    /**
     * Stops the ReceiverService.
     *
     * All receivers are stopped, all network ports are released, and the
     * persistent notification is removed.
     *
     * @param context Any valid Android context.
     */
    fun stop(context: Context) {
        Logger.i("ReceiverController: stop()")
        val intent = buildIntent(context, ReceiverService.ACTION_STOP)
        context.startService(intent)
    }

    /**
     * Restarts the ReceiverService.
     *
     * Sends a restart command that stops all receivers and starts them again
     * with the latest settings. Useful after changing Settings or recovering
     * from an error state.
     *
     * The service itself keeps running during restart (no stopSelf() is called).
     * The visible interruption is brief (<500ms for port release + re-advertise).
     *
     * @param context Any valid Android context.
     */
    fun restart(context: Context) {
        Logger.i("ReceiverController: restart()")
        val intent = buildIntent(context, ReceiverService.ACTION_RESTART)
        startForegroundServiceCompat(context, intent)
    }

    /**
     * Starts a foreground service using [Context.startForegroundService] on API 26+,
     * falling back to [Context.startService] on older versions.
     *
     * This replaces the AndroidX [androidx.core.content.ContextCompat.startForegroundService]
     * helper so that this class has no AndroidX dependency.
     */
    // ObsoleteSdkInt: googletv minSdk is 29 but firetv minSdk is 25, so the guard is real.
    // NewApi: the `sdkInt` indirection (for test override) hides the version check from
    // lint's analyzer, but the runtime check is correct.
    @android.annotation.SuppressLint("ObsoleteSdkInt", "NewApi")
    private fun startForegroundServiceCompat(context: Context, intent: Intent) {
        if (sdkInt >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Constructs a service control [Intent] with the given action.
     *
     * @param context The calling context.
     * @param action  One of [ReceiverService.ACTION_START], ACTION_STOP, ACTION_RESTART.
     */
    private fun buildIntent(context: Context, action: String): Intent =
        Intent(context, ReceiverService::class.java).apply { this.action = action }
}
