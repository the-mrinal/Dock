package com.ambient.tvclock.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ambient.tvclock.MainActivity
import com.ambient.tvclock.R
import com.ambient.tvclock.ReceiverPreferences
import android.view.Surface
import com.ambient.tvclock.receiver.airplay.AirPlayReceiver
import com.ambient.tvclock.receiver.cast.CastReceiver
import com.ambient.tvclock.receiver.miracast.MiracastReceiver
import com.ambient.tvclock.receiver.settings.AppSettings
import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ReceiverService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that PhairPlay is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ReceiverController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ReceiverController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class ReceiverService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ReceiverState>(ReceiverState.Stopped)
    val serviceState: StateFlow<ReceiverState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()

    private val _castState = MutableStateFlow(ProtocolState.DISABLED)
    val castState: StateFlow<ProtocolState> = _castState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private fun setActiveConnection(connection: ActiveConnection?) {
        _activeConnection.value = connection
        ReceiverStateBus.publishActiveConnection(connection)
    }

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("ReceiverService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Supplied by tests; production code reads from [ReceiverStateBus]. Kept on the
     * public surface so ReceiverServiceTest's setVideoSurfaceProvider mock can still
     * exercise the AirPlayReceiver wiring without touching the bus singleton.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        ReceiverStateBus.setSurfaceProvider(provider)
    }

    override fun onDestroy() {
        Logger.i("ReceiverService destroying")
        stopAllReceiversInternal()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = ReceiverPreferences.read(applicationContext)
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Miracast=${settings.miracastEnabled}, Cast=${settings.castEnabled}")

        _serviceState.value = ReceiverState.Running
        updateNotification(isRunning = true)

        if (settings.airPlayEnabled)   startAirPlay(settings)
        if (settings.miracastEnabled)  startMiracast()
        if (settings.castEnabled)      startCast()
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ReceiverState.Stopped
        setActiveConnection(null)
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ReceiverState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            // Surface comes from the activity-side overlay via ReceiverStateBus.
            videoSurfaceProvider = { ReceiverStateBus.currentSurface() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        setActiveConnection(ActiveConnection(pendingSenderName, Protocol.AIRPLAY))
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        setActiveConnection(null)
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    private fun startMiracast() {
        _miracastState.value = ProtocolState.ADVERTISING
        miracastReceiver = MiracastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _miracastState.value = state }
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    private fun startCast() {
        _castState.value = ProtocolState.ADVERTISING
        castReceiver = CastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _castState.value = state }
        ).also { it.start() }
        Logger.d("Cast receiver started")
    }

    private fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { castReceiver?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        airPlayReceiver = null
        miracastReceiver = null
        castReceiver = null
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
    }

    // ─── Notification ────────────────────────────────────────────────────────

    // Suppressed because the firetv flavor (minSdk 25) still needs the API-26 guard for NotificationChannel;
    // lint analyzing the googletv flavor (minSdk 29) flags the check as obsolete.
    @android.annotation.SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ReceiverService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ReceiverService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [ReceiverService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): ReceiverService = this@ReceiverService
    }

    companion object {
        const val CHANNEL_ID      = "phairplay_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.ambient.tvclock.receiver.action.START"
        const val ACTION_STOP     = "com.ambient.tvclock.receiver.action.STOP"
        const val ACTION_RESTART  = "com.ambient.tvclock.receiver.action.RESTART"
    }
}
