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
    private val textCalendarPrimary: TextView = root.findViewById(R.id.textHomeCalendarPrimary)
    private val textCalendarMeta: TextView = root.findViewById(R.id.textHomeCalendarMeta)
    private val textCalendarTeaser: TextView = root.findViewById(R.id.textHomeCalendarTeaser)
    private val textTrackTitle: TextView = root.findViewById(R.id.textHomeTrackTitle)
    private val textTrackArtist: TextView = root.findViewById(R.id.textHomeTrackArtist)
    private val textUpNext: TextView = root.findViewById(R.id.textHomeUpNext)
    private val imageAlbumArt: ImageView = root.findViewById(R.id.imageHomeAlbumArt)
    private val imagePlaceholder: ImageView = root.findViewById(R.id.imageHomeAlbumPlaceholder)
    private val albumArtContainer: View = root.findViewById(R.id.homeAlbumArtContainer)

    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val artworkState = NowPlayingArtwork.State()

    init {
        albumArtContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 8f)
            }
        }
        albumArtContainer.clipToOutline = true
    }

    fun updateClock() {
        val now = Calendar.getInstance().time
        textClockTime.text = timeFormatter.format(now)
        textClockDate.text = dateFormatter.format(now)
    }

    fun bindCalendar(snapshot: CalendarSnapshot) {
        val context = root.context
        if (!CalendarPreferences.isEnabled(context)) {
            textCalendarPrimary.text = context.getString(R.string.calendar_no_events)
            textCalendarMeta.visibility = View.GONE
            textCalendarTeaser.visibility = View.GONE
            return
        }
        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            textCalendarPrimary.text = context.getString(R.string.calendar_add_in_settings)
            textCalendarMeta.visibility = View.GONE
            textCalendarTeaser.visibility = View.GONE
            return
        }

        val now = System.currentTimeMillis()
        val events = snapshot.events
        if (events.isEmpty()) {
            textCalendarPrimary.text = context.getString(R.string.calendar_no_events)
            textCalendarMeta.visibility = View.GONE
            textCalendarTeaser.visibility = View.GONE
            return
        }

        val next = CalendarDisplayHelper.nextUpcoming(events, now)
        if (next == null) {
            textCalendarPrimary.text = context.getString(R.string.calendar_no_events)
            textCalendarMeta.visibility = View.GONE
            textCalendarTeaser.visibility = View.GONE
            return
        }

        val timeLabel = if (next.isHappeningNow(now)) {
            context.getString(R.string.calendar_happening_now)
        } else {
            CalendarDisplayHelper.formatEventTime(next).substringBefore(" –")
        }
        textCalendarPrimary.text = "$timeLabel  ${next.title}"
        textCalendarMeta.visibility = View.VISIBLE
        textCalendarMeta.text = CalendarDisplayHelper.sourceLabel(context, next.source)

        val more = CalendarDisplayHelper.remainingCount(events, now, next)
        if (more > 0) {
            textCalendarTeaser.visibility = View.VISIBLE
            textCalendarTeaser.text = context.getString(R.string.calendar_more_today, more)
        } else {
            textCalendarTeaser.visibility = View.GONE
        }
    }

    fun bindNowPlaying(info: NowPlayingInfo?) {
        val context = root.context
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession

        if (!show) {
            textTrackTitle.text = context.getString(R.string.home_nothing_playing)
            textTrackArtist.text = ""
            textUpNext.visibility = View.GONE
            imageAlbumArt.visibility = View.GONE
            imagePlaceholder.visibility = View.VISIBLE
            NowPlayingArtwork.reset(artworkState)
            return
        }

        val track = info!!
        textTrackTitle.text = track.title
        textTrackTitle.isSelected = true
        textTrackArtist.text = track.artist.ifEmpty { context.getString(R.string.unknown_artist) }
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
}
