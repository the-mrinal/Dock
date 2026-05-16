package com.ambient.tvclock

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeScreenBinder(private val root: View) {

    private val textClockTime: TextView = root.findViewById(R.id.textHomeClockTime)
    private val textClockDate: TextView = root.findViewById(R.id.textHomeClockDate)
    private val textCalendarBadge: TextView = root.findViewById(R.id.textHomeCalendarBadge)
    private val textCalendarTime: TextView = root.findViewById(R.id.textHomeCalendarTime)
    private val textCalendarPrimary: TextView = root.findViewById(R.id.textHomeCalendarPrimary)
    private val textCalendarMeta: TextView = root.findViewById(R.id.textHomeCalendarMeta)
    private val textCalendarTeaser: TextView = root.findViewById(R.id.textHomeCalendarTeaser)
    private val textTrackTitle: TextView = root.findViewById(R.id.textHomeTrackTitle)
    private val textTrackArtist: TextView = root.findViewById(R.id.textHomeTrackArtist)
    private val textUpNext: TextView = root.findViewById(R.id.textHomeUpNext)
    private val imageAlbumArt: ImageView = root.findViewById(R.id.imageHomeAlbumArt)
    private val imagePlaceholder: ImageView = root.findViewById(R.id.imageHomeAlbumPlaceholder)
    private val albumArtContainer: View = root.findViewById(R.id.homeAlbumArtContainer)
    private val calendarWidget: View = root.findViewById(R.id.homeCalendarWidget)
    private val musicWidget: View = root.findViewById(R.id.homeMusicWidget)

    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val calendarBuffer = Calendar.getInstance()
    private val artworkState = NowPlayingArtwork.State()
    private var lastTimeText: String? = null
    private var lastDateText: String? = null
    private var lastTrackKey: String? = null

    init {
        albumArtContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 8f)
            }
        }
        albumArtContainer.clipToOutline = true
    }

    fun updateClock(force: Boolean = false) {
        calendarBuffer.timeInMillis = System.currentTimeMillis()
        val time = timeFormatter.format(calendarBuffer.time)
        if (force || time != lastTimeText) {
            textClockTime.text = time
            lastTimeText = time
        }
        val date = dateFormatter.format(calendarBuffer.time)
        if (force || date != lastDateText) {
            textClockDate.text = date
            lastDateText = date
        }
    }

    fun bindCalendar(snapshot: CalendarSnapshot) {
        val context = root.context
        if (!CalendarPreferences.isEnabled(context)) {
            showCalendarEmpty(context.getString(R.string.calendar_no_events))
            return
        }
        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            showCalendarEmpty(context.getString(R.string.calendar_add_in_settings))
            return
        }

        val now = System.currentTimeMillis()
        val events = snapshot.events
        if (events.isEmpty()) {
            showCalendarEmpty(context.getString(R.string.calendar_no_events))
            return
        }

        val next = CalendarDisplayHelper.nextUpcoming(events, now)
        if (next == null) {
            showCalendarEmpty(context.getString(R.string.calendar_no_events))
            return
        }

        val happening = next.isHappeningNow(now)
        if (happening) {
            textCalendarBadge.visibility = View.VISIBLE
            textCalendarTime.visibility = View.GONE
        } else {
            textCalendarBadge.visibility = View.GONE
            if (next.isAllDay) {
                textCalendarTime.visibility = View.VISIBLE
                textCalendarTime.text = context.getString(R.string.calendar_all_day)
            } else {
                textCalendarTime.visibility = View.VISIBLE
                textCalendarTime.text = CalendarDisplayHelper.formatTime(next.startMillis)
            }
        }

        textCalendarPrimary.text = next.title
        textCalendarMeta.visibility = View.VISIBLE
        textCalendarMeta.text = CalendarDisplayHelper.sourceLabel(context, next.source)
        textCalendarMeta.setBackgroundResource(
            when (next.source) {
                CalendarSource.PERSONAL -> R.drawable.bg_chip_personal
                CalendarSource.WORK -> R.drawable.bg_chip_work
            }
        )

        val more = CalendarDisplayHelper.remainingCount(events, now, next)
        if (more > 0) {
            textCalendarTeaser.visibility = View.VISIBLE
            textCalendarTeaser.text = context.getString(R.string.calendar_more_today, more)
        } else {
            textCalendarTeaser.visibility = View.GONE
        }
    }

    private fun showCalendarEmpty(message: String) {
        textCalendarBadge.visibility = View.GONE
        textCalendarTime.visibility = View.GONE
        textCalendarPrimary.text = message
        textCalendarMeta.visibility = View.GONE
        textCalendarMeta.setBackgroundResource(0)
        textCalendarTeaser.visibility = View.GONE
    }

    fun bindNowPlaying(info: NowPlayingInfo?) {
        val context = root.context
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession

        if (!show) {
            if (lastTrackKey != EMPTY_TRACK_KEY) {
                textTrackTitle.text = context.getString(R.string.home_nothing_playing)
                textTrackArtist.text = ""
                textTrackTitle.isSelected = false
                lastTrackKey = EMPTY_TRACK_KEY
            }
            textUpNext.visibility = View.GONE
            imageAlbumArt.visibility = View.GONE
            imagePlaceholder.visibility = View.VISIBLE
            NowPlayingArtwork.reset(artworkState)
            return
        }

        val track = info!!
        val key = "${track.title}|${track.artist}"
        if (key != lastTrackKey) {
            textTrackTitle.text = track.title
            textTrackArtist.text = track.artist.ifEmpty { context.getString(R.string.unknown_artist) }
            textTrackTitle.isSelected = true
            lastTrackKey = key
        }
        NowPlayingArtwork.bind(imageAlbumArt, imagePlaceholder, track, artworkState)
    }

    fun bindQueue(snapshot: SpotifyQueueSnapshot) {
        val context = root.context
        val next = snapshot.upNext
        if (snapshot.state == SpotifyQueueState.OK && next != null) {
            textUpNext.visibility = View.VISIBLE
            textUpNext.text = context.getString(
                R.string.home_up_next,
                next.title,
                next.artist.ifEmpty { "—" }
            )
        } else {
            textUpNext.visibility = View.GONE
        }
    }

    /**
     * Crossfade the secondary widgets (calendar + now playing) without changing
     * layout. Hidden state keeps the views in their slots so the clock stays put,
     * while the dim cards minimise burn-in risk when nobody is interacting.
     */
    fun setWidgetsAmbient(ambient: Boolean) {
        val targetAlpha = if (ambient) 0f else 1f
        val duration = if (ambient) AMBIENT_FADE_OUT_MS else AMBIENT_FADE_IN_MS
        animateAlpha(calendarWidget, targetAlpha, duration)
        animateAlpha(musicWidget, targetAlpha, duration)
    }

    private fun animateAlpha(view: View, alpha: Float, durationMs: Long) {
        view.animate().cancel()
        view.animate()
            .alpha(alpha)
            .setDuration(durationMs)
            .start()
    }

    companion object {
        private const val EMPTY_TRACK_KEY = "__empty__"
        private const val AMBIENT_FADE_OUT_MS = 1200L
        private const val AMBIENT_FADE_IN_MS = 320L
    }
}
