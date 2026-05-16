package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var rootContainer: RelativeLayout
    private lateinit var contentDisplayGroup: FrameLayout
    private lateinit var dashboardPager: ViewPager2
    private lateinit var textPageHome: TextView
    private lateinit var textPageCalendar: TextView
    private lateinit var textPageMusic: TextView

    private var homeBinder: HomeScreenBinder? = null
    private var calendarBinder: CalendarScreenBinder? = null
    private var musicBinder: MusicScreenBinder? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val oneMinuteMs = 60 * 1000L

    private var currentPage = DashboardPage.HOME
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

        rootContainer = findViewById(R.id.rootContainer)
        contentDisplayGroup = findViewById(R.id.contentDisplayGroup)
        dashboardPager = findViewById(R.id.dashboardPager)
        textPageHome = findViewById(R.id.textPageHome)
        textPageCalendar = findViewById(R.id.textPageCalendar)
        textPageMusic = findViewById(R.id.textPageMusic)

        dashboardPager.isUserInputEnabled = false
        dashboardPager.offscreenPageLimit = 3
        dashboardPager.adapter = DashboardPagerAdapter { page, view ->
            when (page) {
                DashboardPage.HOME -> {
                    homeBinder = HomeScreenBinder(view)
                    homeBinder?.updateClock()
                    homeBinder?.bindCalendar(CalendarCenter.current)
                    homeBinder?.bindNowPlaying(NowPlayingCenter.current)
                    homeBinder?.bindQueue(SpotifyQueueCenter.current)
                }
                DashboardPage.CALENDAR -> {
                    calendarBinder = CalendarScreenBinder(view)
                    calendarBinder?.bind(CalendarCenter.current)
                }
                DashboardPage.MUSIC -> {
                    musicBinder = MusicScreenBinder(view) {
                        resetInactivityWatchdog()
                        spotifyQueuePoller.publishNow()
                    }
                    musicBinder?.bindNowPlaying(NowPlayingCenter.current)
                    musicBinder?.bindQueue(SpotifyQueueCenter.current)
                    musicBinder?.requestControlFocus()
                }
            }
        }

        dashboardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = DashboardPage.fromIndex(position)
                updatePageIndicator()
                updateDriftBehavior()
                if (currentPage == DashboardPage.MUSIC) {
                    musicBinder?.requestControlFocus()
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
        startPixelDrifter()
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
        nowPlayingPoller.start()
        calendarPoller.publishNow()
    }

    private fun applyNowPlaying(info: NowPlayingInfo?) {
        homeBinder?.bindNowPlaying(info)
        musicBinder?.bindNowPlaying(info)
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
        if (currentPage == DashboardPage.MUSIC || currentPage == DashboardPage.HOME) {
            if (focused.id == R.id.buttonSkipPrevious ||
                focused.id == R.id.buttonPlayPause ||
                focused.id == R.id.buttonSkipNext ||
                focused.id == R.id.upNextContent ||
                focused.id == R.id.textDeviceLabel
            ) {
                return false
            }
            if (focused.parent is RecyclerView) {
                return false
            }
        }
        if (focused is RecyclerView &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            return false
        }
        return true
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            homeBinder?.updateClock()
            calendarBinder?.updateDateLine()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startClockTicker() {
        mainHandler.post(clockRunnable)
    }

    private fun updateDriftBehavior() {
        if (currentPage != DashboardPage.HOME) {
            mainHandler.removeCallbacks(drifterRunnable)
            centerContentDisplay()
        } else {
            centerContentDisplay()
            mainHandler.removeCallbacks(drifterRunnable)
            mainHandler.postDelayed(drifterRunnable, oneMinuteMs)
        }
    }

    private fun centerContentDisplay() {
        val params = contentDisplayGroup.layoutParams as? RelativeLayout.LayoutParams ?: return
        params.removeRule(RelativeLayout.ALIGN_PARENT_START)
        params.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
        params.addRule(RelativeLayout.CENTER_IN_PARENT)
        params.leftMargin = 0
        params.topMargin = 0
        contentDisplayGroup.layoutParams = params
    }

    private val drifterRunnable = object : Runnable {
        override fun run() {
            if (currentPage == DashboardPage.HOME) {
                driftLayoutPosition()
            }
            mainHandler.postDelayed(this, oneMinuteMs)
        }
    }

    private fun startPixelDrifter() {
        mainHandler.postDelayed(drifterRunnable, oneMinuteMs)
    }

    private fun driftLayoutPosition() {
        val containerWidth = rootContainer.width
        val containerHeight = rootContainer.height
        val groupWidth = contentDisplayGroup.width
        val groupHeight = contentDisplayGroup.height

        if (containerWidth > 0 && containerHeight > 0 && groupWidth > 0 && groupHeight > 0) {
            val maxHorizontalMargin = containerWidth - groupWidth
            val maxVerticalMargin = containerHeight - groupHeight

            if (maxHorizontalMargin > 0 && maxVerticalMargin > 0) {
                val params = contentDisplayGroup.layoutParams as RelativeLayout.LayoutParams
                params.removeRule(RelativeLayout.CENTER_IN_PARENT)
                params.leftMargin = Random.nextInt(0, maxHorizontalMargin)
                params.topMargin = Random.nextInt(0, maxVerticalMargin)
                contentDisplayGroup.layoutParams = params
            }
        }
    }

    private val watchdogRunnable = Runnable {
        terminateAppAndSleep()
    }

    private fun resetInactivityWatchdog() {
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
    }
}
