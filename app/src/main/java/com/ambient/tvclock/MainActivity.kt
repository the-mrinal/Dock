package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var contentDisplayGroup: FrameLayout
    private lateinit var dashboardPager: ViewPager2
    private lateinit var pageIndicatorGroup: LinearLayout
    private lateinit var textPageHome: TextView
    private lateinit var textPageCalendar: TextView
    private lateinit var textPageMusic: TextView
    private lateinit var imageHomeBackground: ImageView
    private lateinit var backgroundBinder: BlurredBackgroundBinder

    private var homeBinder: HomeScreenBinder? = null
    private var calendarBinder: CalendarScreenBinder? = null
    private var musicBinder: MusicScreenBinder? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val oneMinuteMs = 60 * 1000L
    private val calendarScrollStep: Int
        get() = (resources.displayMetrics.density * 140).toInt()

    private var currentPage = DashboardPage.HOME
    private var ambientMode = false
    private lateinit var nowPlayingPoller: NowPlayingPoller
    private lateinit var calendarPoller: CalendarPoller
    private lateinit var spotifyQueuePoller: SpotifyQueuePoller

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
        imageHomeBackground = findViewById(R.id.imageHomeBackground)
        backgroundBinder = BlurredBackgroundBinder(imageHomeBackground)

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
            }
        }

        dashboardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = DashboardPage.fromIndex(position)
                updatePageIndicator()
                when (currentPage) {
                    DashboardPage.MUSIC -> musicBinder?.requestControlFocus()
                    DashboardPage.CALENDAR -> calendarBinder?.requestScrollToCurrent()
                    DashboardPage.HOME -> { /* nothing extra */ }
                }
            }
        })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nowPlayingPoller = NowPlayingPoller(this)
        calendarPoller = CalendarPoller(this)
        spotifyQueuePoller = SpotifyQueuePoller(this)

        if (savedInstanceState != null) {
            val pageIndex = savedInstanceState.getInt(KEY_PAGE, 0)
            dashboardPager.setCurrentItem(pageIndex, false)
            currentPage = DashboardPage.fromIndex(pageIndex)
        }
        updatePageIndicator()

        startClockTicker()
        resetInactivityWatchdog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, currentPage.index)
    }

    override fun onStart() {
        super.onStart()
        NowPlayingCenter.addListener(nowPlayingListener)
        CalendarCenter.addListener(calendarListener)
        SpotifyQueueCenter.addListener(queueListener)
        nowPlayingPoller.start()
        calendarPoller.start()
        spotifyQueuePoller.start()
    }

    override fun onStop() {
        nowPlayingPoller.stop()
        calendarPoller.stop()
        spotifyQueuePoller.stop()
        NowPlayingCenter.removeListener(nowPlayingListener)
        CalendarCenter.removeListener(calendarListener)
        SpotifyQueueCenter.removeListener(queueListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityWatchdog()
        NotificationAccess.requestListenerReconnect(this)
        nowPlayingPoller.publishNow()
        calendarPoller.publishNow()
    }

    private fun applyNowPlaying(info: NowPlayingInfo?) {
        homeBinder?.bindNowPlaying(info)
        musicBinder?.bindNowPlaying(info)
        backgroundBinder.bind(info)
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
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_tertiary)
        textPageHome.setTextColor(if (currentPage == DashboardPage.HOME) active else inactive)
        textPageCalendar.setTextColor(if (currentPage == DashboardPage.CALENDAR) active else inactive)
        textPageMusic.setTextColor(if (currentPage == DashboardPage.MUSIC) active else inactive)
        textPageHome.typeface = if (currentPage == DashboardPage.HOME) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else {
            android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        textPageCalendar.typeface = if (currentPage == DashboardPage.CALENDAR) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else {
            android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        textPageMusic.typeface = if (currentPage == DashboardPage.MUSIC) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else {
            android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
    }

    private fun goToPage(page: DashboardPage) {
        if (currentPage == page) return
        dashboardPager.setCurrentItem(page.index, true)
    }

    private fun goNextPage() {
        if (currentPage.index < DashboardPage.MUSIC.index) {
            goToPage(DashboardPage.fromIndex(currentPage.index + 1))
        }
    }

    private fun goPreviousPage() {
        if (currentPage.index > DashboardPage.HOME.index) {
            goToPage(DashboardPage.fromIndex(currentPage.index - 1))
        }
    }

    private fun shouldNavigatePages(keyCode: Int): Boolean {
        val focused = currentFocus ?: return true

        if (focused is RecyclerView &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            return false
        }

        if (currentPage != DashboardPage.MUSIC) {
            return true
        }

        // Music-page navigation contract:
        //   - LEFT always escapes back to Calendar on the very first press,
        //     no matter where focus is. The user reached Music with one RIGHT,
        //     so one LEFT should symmetrically take them back.
        //   - RIGHT stays "interior" inside the transport bar so users can
        //     still sweep Play -> Skip Next -> Cast without leaving the page
        //     (there is no dashboard page to the right of Music anyway).
        //   - Up Next: LEFT escapes, RIGHT hands off to default focus
        //     traversal which lands on the Play button (nextFocusRight).
        //   - Skip Previous is intentionally not on the D-pad path -- remote
        //     media keys (KEYCODE_MEDIA_PREVIOUS) handle it.
        return when (focused.id) {
            R.id.buttonPlayPause -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            R.id.buttonSkipPrevious -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            R.id.buttonSkipNext -> false
            R.id.buttonDeviceCast -> keyCode != KeyEvent.KEYCODE_DPAD_LEFT
            R.id.upNextContent -> keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
            else -> true
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            homeBinder?.updateClock()
            homeBinder?.refreshAmbient()
            calendarBinder?.updateDateLine()
            mainHandler.postDelayed(this, 1000)
        }
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
        backgroundBinder.setAmbient(true)
        pageIndicatorGroup.animate().cancel()
        pageIndicatorGroup.animate()
            .alpha(0f)
            .setDuration(AMBIENT_PAGER_FADE_OUT_MS)
            .start()

        mainHandler.removeCallbacks(drifterRunnable)
        mainHandler.postDelayed(drifterRunnable, AMBIENT_DRIFT_KICKOFF_MS)
    }

    private fun exitAmbientMode() {
        if (!ambientMode) return
        ambientMode = false

        homeBinder?.setWidgetsAmbient(false)
        backgroundBinder.setAmbient(false)
        pageIndicatorGroup.animate().cancel()
        pageIndicatorGroup.animate()
            .alpha(1f)
            .setDuration(AMBIENT_PAGER_FADE_IN_MS)
            .start()

        mainHandler.removeCallbacks(drifterRunnable)
        recenterContentDisplay()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentPage == DashboardPage.CALENDAR) {
                    calendarBinder?.scrollBy(calendarScrollStep)
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val KEY_PAGE = "dashboard_page"

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
    }
}
