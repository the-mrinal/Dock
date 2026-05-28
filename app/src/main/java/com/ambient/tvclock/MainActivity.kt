package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ambient.tvclock.receiver.ActiveConnection
import com.ambient.tvclock.receiver.ReceiverController
import com.ambient.tvclock.receiver.ReceiverStateBus
import com.ambient.tvclock.receiver.airplay.AirPlayNowPlayingActivity
import com.ambient.tvclock.receiver.ui.StreamingOverlay
import com.ambient.tvclock.vpn.VpnPreferences
import com.ambient.tvclock.vpn.VpnState
import com.ambient.tvclock.vpn.WireGuardController
import com.ambient.tvclock.vpn.WireGuardStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var contentDisplayGroup: FrameLayout
    private lateinit var dashboardPager: ViewPager2
    private lateinit var pageIndicatorGroup: LinearLayout
    private lateinit var textPageHome: TextView
    private lateinit var textPageCalendar: TextView
    private lateinit var textPageMusic: TextView
    private lateinit var textPageStatus: TextView
    private lateinit var textPageMeal: TextView
    private lateinit var imageHomeBackground: ImageView
    private lateinit var textHomeBackgroundCredit: TextView
    private lateinit var backgroundBinder: BlurredBackgroundBinder
    private lateinit var backgroundController: BackgroundController

    private var homeBinder: HomeScreenBinder? = null
    private var calendarBinder: CalendarScreenBinder? = null
    private var musicBinder: MusicScreenBinder? = null
    private var statusBinder: StatusScreenBinder? = null
    private var mealBinder: MealScreenBinder? = null

    private val mealPlanListener: (MealPlanSnapshot) -> Unit = { snapshot ->
        mainHandler.post { mealBinder?.bind(snapshot) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val oneMinuteMs = 60 * 1000L
    private val calendarScrollStep: Int
        get() = (resources.displayMetrics.density * 140).toInt()

    private var currentPage = DashboardPage.HOME
    private var ambientMode = false
    // Receiver subsystem entry point. Started/stopped by SettingsActivity's master toggle.
    @Suppress("unused")
    private val receiverController = ReceiverController
    private lateinit var streamingOverlay: StreamingOverlay
    private lateinit var onboardingPill: LinearLayout
    private var streamingActive = false
    // Set by long-press BACK while mirroring: hides the overlay but keeps the sender's
    // session alive. Reset when activeConnection becomes null (sender disconnects).
    private var userDismissedOverlay = false
    private val streamingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamingObserverJob: Job? = null
    private var videoSizeObserverJob: Job? = null
    private var vpnObserverJob: Job? = null
    private var airPlayNowPlayingJob: Job? = null
    // True between the moment we start AirPlayNowPlayingActivity and the
    // moment ReceiverStateBus clears the snapshot. Guards against a double
    // startActivity when artwork + metadata arrive in two separate
    // SET_PARAMETER messages within ~50 ms.
    private var nowPlayingActivityLaunched: Boolean = false
    private var currentActiveConnection: ActiveConnection? = null
    private var currentVpnState: VpnState = VpnState.NoConfig
    private lateinit var nowPlayingPoller: NowPlayingPoller
    private lateinit var calendarPoller: CalendarPoller
    private lateinit var spotifyQueuePoller: SpotifyQueuePoller
    private lateinit var mealPlanPoller: MealPlanPoller

    private val nowPlayingListener: (NowPlayingInfo?) -> Unit = { info ->
        mainHandler.post { applyNowPlaying(info) }
    }

    private val calendarListener: (CalendarSnapshot) -> Unit = { snapshot ->
        mainHandler.post { applyCalendar(snapshot) }
    }

    private val queueListener: (SpotifyQueueSnapshot) -> Unit = { snapshot ->
        mainHandler.post { applyQueue(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentDisplayGroup = findViewById(R.id.contentDisplayGroup)
        dashboardPager = findViewById(R.id.dashboardPager)
        pageIndicatorGroup = findViewById(R.id.pageIndicatorGroup)
        textPageHome = findViewById(R.id.textPageHome)
        textPageCalendar = findViewById(R.id.textPageCalendar)
        textPageMusic = findViewById(R.id.textPageMusic)
        textPageStatus = findViewById(R.id.textPageStatus)
        textPageMeal = findViewById(R.id.textPageMeal)
        imageHomeBackground = findViewById(R.id.imageHomeBackground)
        textHomeBackgroundCredit = findViewById(R.id.textHomeBackgroundCredit)
        backgroundBinder = BlurredBackgroundBinder(imageHomeBackground)
        backgroundController = BackgroundController(
            context = this,
            binder = backgroundBinder,
            onUnsplashPhotoChanged = ::onUnsplashPhotoChanged,
        )
        streamingOverlay = findViewById(R.id.streamingOverlay)
        onboardingPill = findViewById(R.id.onboardingPill)
        findViewById<View>(R.id.onboardingDismiss).setOnClickListener {
            OnboardingPreferences.markDismissed(this)
            updateOnboardingVisibility()
        }
        OnboardingPreferences.ensureFirstLaunchRecorded(this)

        dashboardPager.isUserInputEnabled = false
        dashboardPager.offscreenPageLimit = 1
        dashboardPager.adapter = DashboardPagerAdapter { page, view, isNew ->
            when (page) {
                DashboardPage.HOME -> {
                    if (isNew || homeBinder == null) {
                        homeBinder = HomeScreenBinder(view)
                    }
                    homeBinder?.apply {
                        updateClock(force = true)
                        bindCalendar(CalendarCenter.current)
                        bindNowPlaying(NowPlayingCenter.current)
                        bindQueue(SpotifyQueueCenter.current)
                        setMinimalWallpaperMode(backgroundController.activePhoto() != null)
                    }
                }
                DashboardPage.CALENDAR -> {
                    if (isNew || calendarBinder == null) {
                        calendarBinder = CalendarScreenBinder(view)
                    }
                    calendarBinder?.bind(CalendarCenter.current)
                }
                DashboardPage.MUSIC -> {
                    if (isNew || musicBinder == null) {
                        musicBinder = MusicScreenBinder(view) {
                            resetInactivityWatchdog()
                            spotifyQueuePoller.publishNow()
                        }
                    }
                    musicBinder?.apply {
                        bindNowPlaying(NowPlayingCenter.current)
                        bindQueue(SpotifyQueueCenter.current)
                        if (isNew && currentPage == DashboardPage.MUSIC) {
                            requestControlFocus()
                        }
                    }
                }
                DashboardPage.STATUS -> {
                    if (isNew || statusBinder == null) {
                        statusBinder = StatusScreenBinder(
                            view,
                            onAirplayAction = { action -> handleAirplayAction(action) },
                            onVpnAction = { action -> handleVpnAction(action) }
                        )
                    }
                    statusBinder?.apply {
                        bindAirplay(currentActiveConnection)
                        bindVpn(currentVpnState)
                    }
                    if (isNew && currentPage == DashboardPage.STATUS) {
                        statusBinder?.vpnButton?.post { statusBinder?.vpnButton?.requestFocus() }
                    }
                }
                DashboardPage.MEAL -> {
                    if (isNew || mealBinder == null) {
                        mealBinder = MealScreenBinder(view)
                    }
                    mealBinder?.bind(MealPlanCenter.current)
                }
            }
        }

        dashboardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = DashboardPage.fromIndex(position)
                updatePageIndicator()
                updateOnboardingVisibility()
                when (currentPage) {
                    DashboardPage.MUSIC -> musicBinder?.requestControlFocus()
                    DashboardPage.CALENDAR -> calendarBinder?.requestScrollToCurrent()
                    DashboardPage.STATUS -> {
                        statusBinder?.apply {
                            bindAirplay(currentActiveConnection)
                            bindVpn(currentVpnState)
                        }
                        statusBinder?.vpnButton?.post { statusBinder?.vpnButton?.requestFocus() }
                    }
                    DashboardPage.MEAL -> {
                        mealPlanPoller.publishNow()
                        mealBinder?.bind(MealPlanCenter.current)
                    }
                    DashboardPage.HOME -> { /* nothing extra */ }
                }
                applyMealPageChrome(currentPage == DashboardPage.MEAL)
            }
        })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nowPlayingPoller = NowPlayingPoller(this)
        calendarPoller = CalendarPoller(this)
        spotifyQueuePoller = SpotifyQueuePoller(this)
        mealPlanPoller = MealPlanPoller(this)

        val initialIndex = savedInstanceState?.getInt(KEY_PAGE, DashboardPage.HOME.index)
            ?: DashboardPage.HOME.index
        dashboardPager.setCurrentItem(initialIndex, false)
        currentPage = DashboardPage.fromIndex(initialIndex)
        updatePageIndicator()

        startClockTicker()
        resetInactivityWatchdog()
        applyMealPageChrome(currentPage == DashboardPage.MEAL)
    }

    /**
     * On the Meal page the global Unsplash wallpaper + its credit caption must
     * not be visible — the page is intentionally its own cream-paper world.
     * Hide both when MEAL is active and restore when leaving. We toggle
     * visibility (not alpha) so we don't race the BackgroundController's
     * own alpha animations on the wallpaper image.
     */
    private fun applyMealPageChrome(onMealPage: Boolean) {
        imageHomeBackground.visibility = if (onMealPage) View.INVISIBLE else View.VISIBLE
        if (onMealPage) {
            textHomeBackgroundCredit.animate().cancel()
            textHomeBackgroundCredit.visibility = View.GONE
            textHomeBackgroundCredit.alpha = 0f
        } else if (backgroundController.activePhoto() != null) {
            textHomeBackgroundCredit.visibility = View.VISIBLE
            textHomeBackgroundCredit.alpha = BG_CREDIT_ALPHA
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, currentPage.index)
    }

    override fun onStart() {
        super.onStart()
        backgroundController.onStart()
        NowPlayingCenter.addListener(nowPlayingListener)
        CalendarCenter.addListener(calendarListener)
        SpotifyQueueCenter.addListener(queueListener)
        MealPlanCenter.addListener(mealPlanListener)
        nowPlayingPoller.start()
        calendarPoller.start()
        spotifyQueuePoller.start()
        mealPlanPoller.start()

        ReceiverStateBus.setSurfaceProvider { streamingOverlay.currentSurface() }
        streamingObserverJob = streamingScope.launch {
            ReceiverStateBus.activeConnection.collect { connection ->
                applyActiveConnection(connection)
            }
        }
        videoSizeObserverJob = streamingScope.launch {
            ReceiverStateBus.videoSize.collect { size ->
                streamingOverlay.setVideoSize(size?.width ?: 0, size?.height ?: 0)
            }
        }
        vpnObserverJob = streamingScope.launch {
            WireGuardStateBus.state.collect { state ->
                currentVpnState = state
                statusBinder?.bindVpn(state)
            }
        }
        // AirPlay audio sessions (Apple Music, Spotify, Safari audio) get a
        // dedicated full-screen "Now Playing" surface. We launch the activity
        // the first time iOS publishes metadata/artwork/progress and let it
        // finish itself when ReceiverStateBus clears the snapshot on session
        // end — `noHistory` + `singleTop` keep relaunches free of stacking.
        airPlayNowPlayingJob = streamingScope.launch {
            ReceiverStateBus.airPlayNowPlaying.collect { state ->
                if (state != null && !nowPlayingActivityLaunched) {
                    nowPlayingActivityLaunched = true
                    startActivity(Intent(this@MainActivity, AirPlayNowPlayingActivity::class.java))
                } else if (state == null) {
                    nowPlayingActivityLaunched = false
                }
            }
        }
    }

    override fun onStop() {
        streamingObserverJob?.cancel()
        streamingObserverJob = null
        videoSizeObserverJob?.cancel()
        videoSizeObserverJob = null
        vpnObserverJob?.cancel()
        vpnObserverJob = null
        airPlayNowPlayingJob?.cancel()
        airPlayNowPlayingJob = null
        ReceiverStateBus.setSurfaceProvider(null)

        nowPlayingPoller.stop()
        calendarPoller.stop()
        spotifyQueuePoller.stop()
        mealPlanPoller.stop()
        NowPlayingCenter.removeListener(nowPlayingListener)
        CalendarCenter.removeListener(calendarListener)
        SpotifyQueueCenter.removeListener(queueListener)
        MealPlanCenter.removeListener(mealPlanListener)
        mealBinder?.detach()
        backgroundController.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityWatchdog()
        NotificationAccess.requestListenerReconnect(this)
        nowPlayingPoller.publishNow()
        calendarPoller.publishNow()
        updateOnboardingVisibility()
    }

    private fun updateOnboardingVisibility() {
        if (!::onboardingPill.isInitialized) return
        val shouldShow = currentPage == DashboardPage.HOME
            && !streamingActive
            && !ambientMode
            && OnboardingPreferences.shouldShowMirroringTip(this)
        onboardingPill.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun applyNowPlaying(info: NowPlayingInfo?) {
        homeBinder?.bindNowPlaying(info)
        musicBinder?.bindNowPlaying(info)
        // BackgroundController is its own NowPlayingCenter listener and picks
        // up the change directly — no need to forward it here.
    }

    private fun onUnsplashPhotoChanged(photo: UnsplashClient.Photo?) {
        // Home layout swap: with a photo, drop the widget cards & shrink the
        // clock so the photo becomes the focal element.
        homeBinder?.setMinimalWallpaperMode(photo != null)

        if (photo == null) {
            if (textHomeBackgroundCredit.visibility != View.GONE) {
                textHomeBackgroundCredit.animate().cancel()
                textHomeBackgroundCredit.animate()
                    .alpha(0f)
                    .setDuration(BG_CREDIT_FADE_MS)
                    .withEndAction { textHomeBackgroundCredit.visibility = View.GONE }
                    .start()
            }
            return
        }
        val text = formatCredit(photo)
        textHomeBackgroundCredit.text = text
        // The Meals page is intentionally cut off from the global background
        // system — keep the Unsplash credit hidden while it's the visible page.
        if (currentPage == DashboardPage.MEAL) {
            textHomeBackgroundCredit.visibility = View.GONE
            textHomeBackgroundCredit.alpha = 0f
            return
        }
        textHomeBackgroundCredit.visibility = View.VISIBLE
        textHomeBackgroundCredit.animate().cancel()
        textHomeBackgroundCredit.animate()
            .alpha(BG_CREDIT_ALPHA)
            .setDuration(BG_CREDIT_FADE_MS)
            .start()
    }

    private fun formatCredit(photo: UnsplashClient.Photo): String {
        val photographer = photo.photographerName.ifBlank { "Unsplash" }
        val description = photo.description
        return if (description.isNotBlank()) {
            getString(R.string.background_credit_with_description, description, photographer)
        } else {
            getString(R.string.background_credit_no_description, photographer)
        }
    }

    private fun applyCalendar(snapshot: CalendarSnapshot) {
        homeBinder?.bindCalendar(snapshot)
        calendarBinder?.bind(snapshot)
    }

    private fun applyQueue(snapshot: SpotifyQueueSnapshot) {
        homeBinder?.bindQueue(snapshot)
        musicBinder?.bindQueue(snapshot)
    }

    private fun updatePageIndicator() {
        applyIndicatorStyle(textPageStatus, DashboardPage.STATUS)
        applyIndicatorStyle(textPageHome, DashboardPage.HOME)
        applyIndicatorStyle(textPageCalendar, DashboardPage.CALENDAR)
        applyIndicatorStyle(textPageMusic, DashboardPage.MUSIC)
        applyIndicatorStyle(textPageMeal, DashboardPage.MEAL)
    }

    private fun applyIndicatorStyle(label: TextView, page: DashboardPage) {
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_tertiary)
        val isActive = currentPage == page
        label.setTextColor(if (isActive) active else inactive)
        label.typeface = android.graphics.Typeface.create(
            if (isActive) "sans-serif-medium" else "sans-serif-light",
            android.graphics.Typeface.NORMAL
        )
    }

    private fun goToPage(page: DashboardPage) {
        if (currentPage == page) return
        dashboardPager.setCurrentItem(page.index, true)
    }

    private fun goNextPage() {
        if (currentPage.index < DashboardPage.LAST.index) {
            goToPage(DashboardPage.fromIndex(currentPage.index + 1))
        }
    }

    private fun goPreviousPage() {
        if (currentPage.index > 0) {
            goToPage(DashboardPage.fromIndex(currentPage.index - 1))
        }
    }

    private fun shouldNavigatePages(keyCode: Int): Boolean {
        val focused = currentFocus ?: return true

        // Lists own their own vertical scroll; horizontal still falls through
        // to page nav so RIGHT/LEFT remains a 1-click jump between pages.
        if (focused is RecyclerView &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            return false
        }

        // Connect page has two side-by-side action buttons. Inside the page,
        // honour interior focus traversal so the user can walk between the
        // AirPlay and VPN buttons. Only page-nav from the outermost edge:
        //   - AirPlay button (leftmost): LEFT page-navs, RIGHT goes interior
        //   - VPN button (rightmost): RIGHT page-navs, LEFT goes interior
        if (currentPage == DashboardPage.STATUS) {
            return when (focused.id) {
                R.id.buttonAirplayAction -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                R.id.buttonVpnAction -> keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                else -> true
            }
        }
        return true
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            homeBinder?.updateClock()
            homeBinder?.refreshAmbient()
            calendarBinder?.updateDateLine()
            // Ambient mode never shows seconds; ticking at 1Hz just burns the
            // main thread. Sleep to the next minute boundary instead.
            val delay = if (ambientMode) nextMinuteBoundaryDelayMs() else 1_000L
            mainHandler.postDelayed(this, delay)
        }
    }

    private fun nextMinuteBoundaryDelayMs(): Long {
        val ms = System.currentTimeMillis() % 60_000L
        return (60_000L - ms).coerceAtLeast(1_000L)
    }

    private fun startClockTicker() {
        mainHandler.post(clockRunnable)
    }

    private val drifterRunnable = object : Runnable {
        override fun run() {
            if (!ambientMode) return
            driftLayoutPosition()
            mainHandler.postDelayed(this, oneMinuteMs)
        }
    }

    /**
     * Nudge the clock by a small random offset using translation so corners of the
     * surrounding safe area never get clipped. Drift only runs in ambient mode,
     * when the supporting widgets have faded out and the clock is the lone bright
     * element on the screen.
     */
    private fun driftLayoutPosition() {
        val density = resources.displayMetrics.density
        val maxDriftXPx = (AMBIENT_DRIFT_X_DP * density).toInt()
        val maxDriftYPx = (AMBIENT_DRIFT_Y_DP * density).toInt()

        val targetX = Random.nextInt(-maxDriftXPx, maxDriftXPx + 1).toFloat()
        val targetY = Random.nextInt(-maxDriftYPx, maxDriftYPx + 1).toFloat()

        contentDisplayGroup.animate().cancel()
        contentDisplayGroup.animate()
            .translationX(targetX)
            .translationY(targetY)
            .setDuration(DRIFT_ANIMATION_MS)
            .start()
    }

    private fun recenterContentDisplay() {
        contentDisplayGroup.animate().cancel()
        contentDisplayGroup.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(DRIFT_RECENTER_MS)
            .start()
    }

    private val enterAmbientRunnable = Runnable { enterAmbientMode() }

    private val fadeSecondsRunnable = Runnable {
        homeBinder?.setSecondsVisible(false)
    }

    private fun enterAmbientMode() {
        if (ambientMode) return
        ambientMode = true

        // Always show the clock when entering ambient mode so it can drift safely.
        if (currentPage != DashboardPage.HOME) {
            dashboardPager.setCurrentItem(DashboardPage.HOME.index, false)
        }

        // Seconds always fade well before ambient kicks in (90s vs minutes),
        // but force-hide here so re-entering ambient after a brief tap doesn't
        // leave the second indicator stranded at full brightness.
        homeBinder?.setSecondsVisible(false)
        homeBinder?.setWidgetsAmbient(true)
        backgroundController.setAmbient(true)
        pageIndicatorGroup.animate().cancel()
        pageIndicatorGroup.animate()
            .alpha(0f)
            .setDuration(AMBIENT_PAGER_FADE_OUT_MS)
            .start()

        mainHandler.removeCallbacks(drifterRunnable)
        mainHandler.postDelayed(drifterRunnable, AMBIENT_DRIFT_KICKOFF_MS)
        updateOnboardingVisibility()
    }

    private fun exitAmbientMode() {
        if (!ambientMode) return
        ambientMode = false

        homeBinder?.setWidgetsAmbient(false)
        backgroundController.setAmbient(false)
        updateOnboardingVisibility()
        pageIndicatorGroup.animate().cancel()
        pageIndicatorGroup.animate()
            .alpha(1f)
            .setDuration(AMBIENT_PAGER_FADE_IN_MS)
            .start()

        mainHandler.removeCallbacks(drifterRunnable)
        recenterContentDisplay()

        // Force the clock to repaint immediately on exit — the pending tick
        // could be up to 60s out from the ambient cadence.
        mainHandler.removeCallbacks(clockRunnable)
        mainHandler.post(clockRunnable)
    }

    private val watchdogRunnable = Runnable {
        terminateAppAndSleep()
    }

    private fun resetInactivityWatchdog() {
        exitAmbientMode()

        homeBinder?.setSecondsVisible(true)
        mainHandler.removeCallbacks(fadeSecondsRunnable)
        mainHandler.postDelayed(fadeSecondsRunnable, SECONDS_VISIBLE_AFTER_INPUT_MS)

        mainHandler.removeCallbacks(enterAmbientRunnable)
        if (AmbientPreferences.isAmbientEnabled(this)) {
            mainHandler.postDelayed(
                enterAmbientRunnable,
                AmbientPreferences.getAmbientDelayMs(this)
            )
        }

        mainHandler.removeCallbacks(watchdogRunnable)
        if (!TimeoutPreferences.isWatchdogEnabled(this)) {
            return
        }
        mainHandler.postDelayed(
            watchdogRunnable,
            TimeoutPreferences.getInactivityTimeoutMs(this)
        )
    }

    private fun terminateAppAndSleep() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishAndRemoveTask()
    }

    private fun handleVpnAction(action: StatusScreenBinder.VpnAction) {
        val app = applicationContext
        when (action) {
            StatusScreenBinder.VpnAction.IMPORT -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            StatusScreenBinder.VpnAction.CONNECT -> {
                if (!VpnPreferences.hasConfig(app)) {
                    Toast.makeText(app, R.string.pref_vpn_status_no_config, Toast.LENGTH_LONG).show()
                    return
                }
                val consent = VpnService.prepare(app)
                if (consent != null) {
                    startActivityForResult(consent, REQUEST_VPN_CONSENT)
                } else {
                    VpnPreferences.setEnabled(app, true)
                    WireGuardController.start(app)
                }
            }
            StatusScreenBinder.VpnAction.DISCONNECT -> {
                VpnPreferences.setEnabled(app, false)
                WireGuardController.stop(app)
            }
        }
        resetInactivityWatchdog()
    }

    private fun handleAirplayAction(action: StatusScreenBinder.AirplayAction) {
        val app = applicationContext
        when (action) {
            StatusScreenBinder.AirplayAction.TURN_ON -> {
                ReceiverPreferences.setReceiverEnabled(app, true)
                ReceiverController.start(app)
            }
            StatusScreenBinder.AirplayAction.TURN_OFF -> {
                ReceiverPreferences.setReceiverEnabled(app, false)
                ReceiverController.stop(app)
            }
        }
        statusBinder?.bindAirplay(currentActiveConnection)
        resetInactivityWatchdog()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CONSENT) {
            val app = applicationContext
            if (resultCode == Activity.RESULT_OK) {
                VpnPreferences.setEnabled(app, true)
                WireGuardController.start(app)
            } else {
                Toast.makeText(app, R.string.vpn_not_authorized, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyActiveConnection(connection: ActiveConnection?) {
        currentActiveConnection = connection
        statusBinder?.bindAirplay(connection)
        if (connection != null) {
            streamingOverlay.setSenderInfo(connection.senderName, connection.protocol)
            if (!userDismissedOverlay) {
                setStreamingActive(true)
            }
        } else {
            userDismissedOverlay = false
            setStreamingActive(false)
        }
    }

    private fun setStreamingActive(active: Boolean) {
        if (streamingActive == active) return
        streamingActive = active
        if (active) {
            exitAmbientMode()
            mainHandler.removeCallbacks(enterAmbientRunnable)
            mainHandler.removeCallbacks(fadeSecondsRunnable)
            mainHandler.removeCallbacks(watchdogRunnable)
            nowPlayingPoller.stop()
            calendarPoller.stop()
            spotifyQueuePoller.stop()
            mealPlanPoller.stop()

            streamingOverlay.visibility = View.VISIBLE
            streamingOverlay.bringToFront()
            streamingOverlay.animate().cancel()
            streamingOverlay.animate().alpha(1f).setDuration(STREAMING_FADE_MS).start()
            contentDisplayGroup.animate().cancel()
            contentDisplayGroup.animate().alpha(0f).setDuration(STREAMING_FADE_MS).start()
            pageIndicatorGroup.animate().cancel()
            pageIndicatorGroup.animate().alpha(0f).setDuration(STREAMING_FADE_MS).start()

            streamingOverlay.senderPill.translationX = 0f
            streamingOverlay.senderPill.translationY = 0f
            mainHandler.removeCallbacks(pillBurnInRunnable)
            mainHandler.postDelayed(pillBurnInRunnable, PILL_DRIFT_INTERVAL_MS)
        } else {
            streamingOverlay.animate().cancel()
            streamingOverlay.animate()
                .alpha(0f)
                .setDuration(STREAMING_FADE_MS)
                .withEndAction { streamingOverlay.visibility = View.GONE }
                .start()
            contentDisplayGroup.animate().cancel()
            contentDisplayGroup.animate().alpha(1f).setDuration(STREAMING_FADE_MS).start()
            pageIndicatorGroup.animate().cancel()
            pageIndicatorGroup.animate().alpha(1f).setDuration(STREAMING_FADE_MS).start()

            mainHandler.removeCallbacks(pillBurnInRunnable)
            streamingOverlay.senderPill.animate().cancel()

            nowPlayingPoller.start()
            calendarPoller.start()
            spotifyQueuePoller.start()
            mealPlanPoller.start()
            resetInactivityWatchdog()
        }
        updateOnboardingVisibility()
    }

    private val pillBurnInRunnable = object : Runnable {
        override fun run() {
            if (!streamingActive) return
            val density = resources.displayMetrics.density
            val driftXPx = (PILL_DRIFT_X_DP * density).toInt()
            val driftYPx = (PILL_DRIFT_Y_DP * density).toInt()
            val tx = Random.nextInt(-driftXPx, driftXPx + 1).toFloat()
            val ty = Random.nextInt(-driftYPx, driftYPx + 1).toFloat()
            streamingOverlay.senderPill.animate()
                .translationX(tx)
                .translationY(ty)
                .setDuration(PILL_DRIFT_DURATION_MS)
                .start()
            mainHandler.postDelayed(this, PILL_DRIFT_INTERVAL_MS)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (streamingActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Start tracking so onKeyLongPress can fire after the long-press
                // threshold. onKeyUp handles the short release.
                event?.startTracking()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (shouldNavigatePages(keyCode)) {
                    resetInactivityWatchdog()
                    goNextPage()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (shouldNavigatePages(keyCode)) {
                    resetInactivityWatchdog()
                    goPreviousPage()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentPage == DashboardPage.CALENDAR) {
                    calendarBinder?.scrollBy(-calendarScrollStep)
                    resetInactivityWatchdog()
                    return true
                }
                if (currentPage == DashboardPage.MEAL) {
                    mealBinder?.scrollBy(-calendarScrollStep)
                    resetInactivityWatchdog()
                    return true
                }
                if (currentPage == DashboardPage.HOME &&
                    backgroundController.activePhoto() != null
                ) {
                    backgroundController.shuffleNow()
                    resetInactivityWatchdog()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentPage == DashboardPage.CALENDAR) {
                    calendarBinder?.scrollBy(calendarScrollStep)
                    resetInactivityWatchdog()
                    return true
                }
                if (currentPage == DashboardPage.MEAL) {
                    mealBinder?.scrollBy(calendarScrollStep)
                    resetInactivityWatchdog()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }

        if (currentPage == DashboardPage.MUSIC) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (musicBinder?.onBackPressed() == true) {
                    resetInactivityWatchdog()
                    return true
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val focused = currentFocus
                if (focused != null && focused.performClick()) {
                    resetInactivityWatchdog()
                    return true
                }
            }
            if (handleMediaKey(keyCode)) {
                resetInactivityWatchdog()
                return true
            }
        }

        resetInactivityWatchdog()
        return super.onKeyDown(keyCode, event)
    }

    private fun handleMediaKey(keyCode: Int): Boolean {
        if (!MediaSessionHelper.isSpotify(NowPlayingCenter.current?.packageName ?: "")) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                MediaTransport.playPause(this)
                mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                MediaTransport.skipToNext(this)
                mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                MediaTransport.skipToPrevious(this)
                mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
                true
            }
            else -> false
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (streamingActive && keyCode == KeyEvent.KEYCODE_BACK) {
            // Long-press BACK: hide the overlay but keep the sender connected.
            // Dashboard returns; user can come back to the stream by waiting
            // for the next emit, or disconnecting via Settings.
            userDismissedOverlay = true
            setStreamingActive(false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (streamingActive && keyCode == KeyEvent.KEYCODE_BACK) {
            // Only fires for a short release — isTracking() returns false once
            // onKeyLongPress consumed the event.
            if (event != null && event.isTracking && !event.isCanceled) {
                ReceiverController.stop(applicationContext)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        streamingScope.cancel()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Forward the OS's memory-pressure hint so the background controller
        // can drop the wallpaper bitmap (~14 MB at 2400 px) when the activity
        // is offscreen. SharedPreferences-cached URLs survive, so we re-decode
        // from network on the next bind without an Unsplash API call.
        if (::backgroundController.isInitialized) {
            backgroundController.onTrimMemory(level)
        }
    }

    companion object {
        private const val KEY_PAGE = "dashboard_page"
        private const val REQUEST_VPN_CONSENT = 0x5A11

        // Wait a moment after fading widgets before the first drift step so the
        // clock visibly settles into its new minimal layout before moving.
        private const val AMBIENT_DRIFT_KICKOFF_MS = 2_000L

        private const val AMBIENT_PAGER_FADE_OUT_MS = 1_200L
        private const val AMBIENT_PAGER_FADE_IN_MS = 320L

        // Drift envelope (dp). Kept tight so even with the widgets fully visible
        // (briefly during fade-out) the contentDisplayGroup never overflows the
        // safe area enough to clip card corners. Y is smaller than X because
        // the ambient cluster sits in the lower half of the slot and downward
        // drift is what risks clipping the music line against the safe area.
        private const val AMBIENT_DRIFT_X_DP = 32
        private const val AMBIENT_DRIFT_Y_DP = 14

        private const val DRIFT_ANIMATION_MS = 1_400L
        private const val DRIFT_RECENTER_MS = 360L

        // Seconds remain visible for 90s after the last input, then crossfade
        // away so the resting clock face is just h:mm AM/PM.
        private const val SECONDS_VISIBLE_AFTER_INPUT_MS = 90_000L

        // Dashboard ↔ streaming overlay crossfade. 300ms is short enough to feel
        // immediate but long enough that the SurfaceView's first frame typically
        // lands before the alpha animation completes.
        private const val STREAMING_FADE_MS = 300L

        // Burn-in protection for the sender pill: nudge by a small random offset
        // every minute. The pill sits in a fixed screen corner for the entire
        // duration of a mirroring session, so without this it would etch in.
        private const val PILL_DRIFT_INTERVAL_MS = 60_000L
        private const val PILL_DRIFT_DURATION_MS = 1_400L
        private const val PILL_DRIFT_X_DP = 8
        private const val PILL_DRIFT_Y_DP = 4

        // Unsplash attribution caption: stay subtle but legible.
        private const val BG_CREDIT_ALPHA = 0.6f
        private const val BG_CREDIT_FADE_MS = 320L
    }
}
