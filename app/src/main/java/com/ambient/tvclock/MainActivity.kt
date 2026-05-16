package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var textClockTime: TextView
    private lateinit var textClockDate: TextView
    private lateinit var textNowPlayingTime: TextView
    private lateinit var textNowPlayingDate: TextView
    private lateinit var textNowPlayingBadge: TextView
    private lateinit var clockDisplayGroup: LinearLayout
    private lateinit var nowPlayingGroup: LinearLayout
    private lateinit var mediaControlsGroup: LinearLayout
    private lateinit var contentDisplayGroup: FrameLayout
    private lateinit var rootContainer: RelativeLayout
    private lateinit var albumArtContainer: FrameLayout
    private lateinit var imageAlbumArt: ImageView
    private lateinit var imageAlbumPlaceholder: ImageView
    private lateinit var textTrackTitle: TextView
    private lateinit var textTrackArtist: TextView
    private lateinit var textTrackAlbum: TextView
    private lateinit var buttonSkipPrevious: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSkipNext: ImageButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private val oneMinuteMs = 60 * 1000L

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    private var lastArtworkKey: String? = null
    private var isNowPlayingVisible = false
    private lateinit var nowPlayingPoller: NowPlayingPoller

    private val nowPlayingListener: (NowPlayingInfo?) -> Unit = { info ->
        mainHandler.post { applyNowPlaying(info) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        contentDisplayGroup = findViewById(R.id.contentDisplayGroup)
        clockDisplayGroup = findViewById(R.id.clockDisplayGroup)
        nowPlayingGroup = findViewById(R.id.nowPlayingGroup)
        mediaControlsGroup = findViewById(R.id.mediaControlsGroup)
        textClockTime = findViewById(R.id.textClockTime)
        textClockDate = findViewById(R.id.textClockDate)
        textNowPlayingTime = findViewById(R.id.textNowPlayingTime)
        textNowPlayingDate = findViewById(R.id.textNowPlayingDate)
        textNowPlayingBadge = findViewById(R.id.textNowPlayingBadge)
        albumArtContainer = findViewById(R.id.albumArtContainer)
        imageAlbumArt = findViewById(R.id.imageAlbumArt)
        imageAlbumPlaceholder = findViewById(R.id.imageAlbumPlaceholder)
        textTrackTitle = findViewById(R.id.textTrackTitle)
        textTrackArtist = findViewById(R.id.textTrackArtist)
        textTrackAlbum = findViewById(R.id.textTrackAlbum)
        buttonSkipPrevious = findViewById(R.id.buttonSkipPrevious)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonSkipNext = findViewById(R.id.buttonSkipNext)

        setupAlbumArtClip()
        setupMediaControls()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nowPlayingPoller = NowPlayingPoller(this)

        startClockTicker()
        startPixelDrifter()
        resetInactivityWatchdog()
    }

    private fun setupAlbumArtClip() {
        val radius = resources.getDimension(R.dimen.album_art_radius)
        albumArtContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        albumArtContainer.clipToOutline = true
    }

    private fun setupMediaControls() {
        buttonSkipPrevious.setOnClickListener {
            resetInactivityWatchdog()
            MediaTransport.skipToPrevious(this)
            mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
        }
        buttonPlayPause.setOnClickListener {
            resetInactivityWatchdog()
            MediaTransport.playPause(this)
            mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
        }
        buttonSkipNext.setOnClickListener {
            resetInactivityWatchdog()
            MediaTransport.skipToNext(this)
            mainHandler.postDelayed({ NowPlayingSessionReader.publish(this) }, 400)
        }
    }

    override fun onStart() {
        super.onStart()
        NowPlayingCenter.addListener(nowPlayingListener)
        nowPlayingPoller.start()
    }

    override fun onStop() {
        nowPlayingPoller.stop()
        NowPlayingCenter.removeListener(nowPlayingListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityWatchdog()
        NotificationAccess.requestListenerReconnect(this)
        nowPlayingPoller.start()
    }

    private fun applyNowPlaying(info: NowPlayingInfo?) {
        val showNowPlaying = NowPlayingPreferences.isEnabled(this) &&
            info != null &&
            info.hasActiveSession

        if (showNowPlaying) {
            val track = info!!
            clockDisplayGroup.visibility = View.GONE
            nowPlayingGroup.visibility = View.VISIBLE

            textTrackTitle.text = track.title
            textTrackArtist.text = track.artist.ifEmpty { getString(R.string.unknown_artist) }

            if (track.album.isNotEmpty()) {
                textTrackAlbum.visibility = View.VISIBLE
                textTrackAlbum.text = track.album
            } else {
                textTrackAlbum.visibility = View.GONE
            }

            val accentColor = if (MediaSessionHelper.isSpotify(track.packageName)) {
                R.color.accent_spotify
            } else {
                R.color.accent_now_playing
            }
            textNowPlayingBadge.setTextColor(ContextCompat.getColor(this, accentColor))

            val showSpotifyControls = MediaSessionHelper.isSpotify(track.packageName)
            mediaControlsGroup.visibility = if (showSpotifyControls) View.VISIBLE else View.GONE

            buttonPlayPause.setImageResource(
                if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            buttonSkipPrevious.isEnabled = track.canSkipPrevious
            buttonSkipPrevious.alpha = if (track.canSkipPrevious) 1f else 0.35f
            buttonSkipNext.isEnabled = track.canSkipNext
            buttonSkipNext.alpha = if (track.canSkipNext) 1f else 0.35f
            buttonPlayPause.isEnabled = track.canPlay || track.canPause
            buttonPlayPause.alpha = if (track.canPlay || track.canPause) 1f else 0.35f

            val artKey = "${track.packageName}|${track.title}|${track.artist}"
            if (artKey != lastArtworkKey) {
                lastArtworkKey = artKey
                if (track.artwork != null) {
                    imageAlbumArt.setImageBitmap(track.artwork)
                    imageAlbumArt.visibility = View.VISIBLE
                    imageAlbumPlaceholder.visibility = View.GONE
                } else {
                    imageAlbumArt.setImageDrawable(null)
                    imageAlbumArt.visibility = View.GONE
                    imageAlbumPlaceholder.visibility = View.VISIBLE
                }
            }
        } else {
            nowPlayingGroup.visibility = View.GONE
            clockDisplayGroup.visibility = View.VISIBLE
            lastArtworkKey = null
        }

        if (showNowPlaying != isNowPlayingVisible) {
            isNowPlayingVisible = showNowPlaying
            updateDriftBehavior(showNowPlaying)
        }
    }

    private fun updateDriftBehavior(nowPlayingActive: Boolean) {
        if (nowPlayingActive) {
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

    private val clockRunnable = object : Runnable {
        override fun run() {
            val calendar = Calendar.getInstance()
            val now = calendar.time
            val timeText = timeFormatter.format(now)
            val dateText = dateFormatter.format(now)
            textClockTime.text = timeText
            textClockDate.text = dateText
            textNowPlayingTime.text = timeText
            textNowPlayingDate.text = dateText
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startClockTicker() {
        mainHandler.post(clockRunnable)
    }

    private val drifterRunnable = object : Runnable {
        override fun run() {
            if (!isNowPlayingVisible) {
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
        if (isNowPlayingVisible && handleMediaKey(keyCode)) {
            resetInactivityWatchdog()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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
}
