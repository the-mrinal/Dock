package com.ambient.tvclock

import android.animation.LayoutTransition
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeScreenBinder(private val root: View) {

    private val clockGroup: ViewGroup = root.findViewById(R.id.homeClockGroup)
    private val textClockTime: TextView = root.findViewById(R.id.textHomeClockTime)
    private val textClockSeconds: TextView = root.findViewById(R.id.textHomeClockSeconds)
    private val textClockAmPm: TextView = root.findViewById(R.id.textHomeClockAmPm)
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
    private val widgetsRow: View = root.findViewById(R.id.homeWidgetsRow)
    private val ambientRow: View = root.findViewById(R.id.homeAmbientRow)
    private val ambientCluster: View = root.findViewById(R.id.homeAmbientCluster)
    private val ambientDivider: View = root.findViewById(R.id.homeAmbientDivider)
    private val ambientNowGroup: View = root.findViewById(R.id.homeAmbientNowGroup)
    private val ambientNowLabel: TextView = root.findViewById(R.id.textAmbientNowLabel)
    private val ambientNowTitle: TextView = root.findViewById(R.id.textAmbientNowTitle)
    private val ambientNextGroup: View = root.findViewById(R.id.homeAmbientNextGroup)
    private val ambientNextLabel: TextView = root.findViewById(R.id.textAmbientNextLabel)
    private val ambientNextTitle: TextView = root.findViewById(R.id.textAmbientNextTitle)
    private val ambientMusicCorner: View = root.findViewById(R.id.homeAmbientMusicCorner)
    private val ambientArtFrame: View = root.findViewById(R.id.homeAmbientArtFrame)
    private val imageAmbientAlbumArt: ImageView = root.findViewById(R.id.imageAmbientAlbumArt)
    private val imageAmbientAlbumPlaceholder: ImageView = root.findViewById(R.id.imageAmbientAlbumPlaceholder)
    private val ambientTrackTitle: TextView = root.findViewById(R.id.textAmbientTrackTitle)
    private val ambientTrackArtist: TextView = root.findViewById(R.id.textAmbientTrackArtist)

    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm", Locale.getDefault())
    private val secondsFormatter = SimpleDateFormat(":ss", Locale.getDefault())
    private val amPmFormatter = SimpleDateFormat("a", Locale.getDefault())
    private val calendarBuffer = Calendar.getInstance()
    private val artworkState = NowPlayingArtwork.State()
    private val ambientArtworkState = NowPlayingArtwork.State()
    private var lastTimeText: String? = null
    private var lastSecondsText: String? = null
    private var lastAmPmText: String? = null
    private var lastDateText: String? = null
    private var lastTrackKey: String? = null
    private var ambientCalendarSnapshot: CalendarSnapshot = CalendarSnapshot(emptyList(), 0L)
    private var ambientNowPlaying: NowPlayingInfo? = null

    init {
        albumArtContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 8f)
            }
        }
        albumArtContainer.clipToOutline = true

        ambientArtFrame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 6f)
            }
        }
        ambientArtFrame.clipToOutline = true

        // Animate the AM/PM sliding to fill the seconds' slot when seconds
        // collapse (and back out when they reappear). Visibility flips trigger
        // a coordinated fade of the seconds view itself plus a CHANGE animation
        // on the AM/PM neighbour so the cluster reads as a single calm motion.
        clockGroup.layoutTransition = LayoutTransition().apply {
            setDuration(LayoutTransition.DISAPPEARING, SECONDS_FADE_OUT_MS)
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, SECONDS_FADE_OUT_MS)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0L)
            setStartDelay(LayoutTransition.DISAPPEARING, 0L)
            setDuration(LayoutTransition.APPEARING, SECONDS_FADE_IN_MS)
            setDuration(LayoutTransition.CHANGE_APPEARING, SECONDS_FADE_IN_MS)
            setStartDelay(LayoutTransition.APPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGE_APPEARING, 0L)
        }
    }

    fun updateClock(force: Boolean = false) {
        calendarBuffer.timeInMillis = System.currentTimeMillis()
        val now = calendarBuffer.time
        val time = timeFormatter.format(now)
        if (force || time != lastTimeText) {
            textClockTime.text = time
            lastTimeText = time
        }
        val seconds = secondsFormatter.format(now)
        if (force || seconds != lastSecondsText) {
            textClockSeconds.text = seconds
            lastSecondsText = seconds
        }
        val amPm = amPmFormatter.format(now)
        if (force || amPm != lastAmPmText) {
            textClockAmPm.text = amPm
            lastAmPmText = amPm
        }
        val date = dateFormatter.format(now)
        if (force || date != lastDateText) {
            textClockDate.text = date
            lastDateText = date
        }
    }

    /**
     * Toggle the seconds segment. Seconds are a "live indicator" that the
     * dashboard is actively in use; after a stretch of no input we collapse
     * them out so the clock face settles into a calmer h:mm AM/PM read.
     *
     * We flip visibility (not just alpha) so the slot's space is reclaimed --
     * otherwise the AM/PM would float with a stale gap where the seconds used
     * to live. The clock group's LayoutTransition fades the seconds out and
     * simultaneously slides the AM/PM to its new position.
     */
    fun setSecondsVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (textClockSeconds.visibility == target) return
        textClockSeconds.visibility = target
    }

    fun bindCalendar(snapshot: CalendarSnapshot) {
        ambientCalendarSnapshot = snapshot
        renderAmbientCalendar()
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
        ambientNowPlaying = info
        renderAmbientMusic()
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
     * Crossfade between the active dashboard widgets (calendar + now playing
     * cards) and the minimal ambient cluster (now / next event + listening to).
     *
     * Both views live in the same FrameLayout slot, so the clock above stays
     * roughly centered in either state. The widgets fade out a bit slower than
     * the ambient cluster fades back in, giving the screen a calmer "settle".
     */
    fun setWidgetsAmbient(ambient: Boolean) {
        if (ambient) {
            renderAmbientCalendar()
            renderAmbientMusic()
            animateAlpha(widgetsRow, 0f, AMBIENT_FADE_OUT_MS)
            // Ambient music + calendar live in one horizontal row, so we fade
            // the row as a single unit. Whether each column is visible inside
            // it is owned by render*() (e.g. no track -> music column GONE,
            // divider GONE so the calendar can centre itself).
            animateAlpha(ambientRow, 1f, AMBIENT_FADE_OUT_MS)
        } else {
            animateAlpha(widgetsRow, 1f, AMBIENT_FADE_IN_MS)
            animateAlpha(ambientRow, 0f, AMBIENT_FADE_IN_MS)
        }
    }

    /**
     * Re-renders ambient lines from the most recent calendar + now playing
     * snapshots using the *current* wall time. Called from the per-second
     * clock tick so event boundaries (e.g. a meeting just ended) flip to the
     * next upcoming entry without waiting for a calendar refresh.
     */
    fun refreshAmbient() {
        renderAmbientCalendar()
        renderAmbientMusic()
    }

    private fun renderAmbientCalendar() {
        val context = root.context
        if (!CalendarPreferences.isEnabled(context)) {
            ambientNowGroup.visibility = View.GONE
            ambientNextGroup.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val events = ambientCalendarSnapshot.events
        val happening = events.firstOrNull { !it.isPast(now) && it.isHappeningNow(now) }
        val next = events.firstOrNull { !it.isPast(now) && it !== happening }

        if (happening != null) {
            ambientNowGroup.visibility = View.VISIBLE
            ambientNowLabel.text = ambientLabelForNow(context, happening, now)
            ambientNowTitle.text = happening.title
        } else {
            ambientNowGroup.visibility = View.GONE
        }

        if (next != null) {
            ambientNextGroup.visibility = View.VISIBLE
            ambientNextLabel.text = ambientLabelForNext(context, next)
            ambientNextTitle.text = next.title
        } else {
            ambientNextGroup.visibility = View.GONE
        }
        updateAmbientDivider()
    }

    /**
     * Divider is shown only when BOTH ambient columns actually carry content,
     * so a single populated column can centre itself horizontally and never
     * floats next to an empty seam.
     */
    private fun updateAmbientDivider() {
        val musicShown = ambientMusicCorner.visibility == View.VISIBLE
        val calendarShown = ambientNowGroup.visibility == View.VISIBLE ||
            ambientNextGroup.visibility == View.VISIBLE
        ambientDivider.visibility = if (musicShown && calendarShown) View.VISIBLE else View.GONE
    }

    private fun ambientLabelForNow(
        context: android.content.Context,
        event: CalendarEvent,
        now: Long
    ): String {
        if (event.isAllDay) {
            return context.getString(R.string.ambient_label_all_day)
        }
        if (event.endMillis <= now) {
            return context.getString(R.string.ambient_label_now)
        }
        return context.getString(
            R.string.ambient_label_now_until,
            CalendarDisplayHelper.formatTime(event.endMillis)
        )
    }

    private fun ambientLabelForNext(
        context: android.content.Context,
        event: CalendarEvent
    ): String {
        if (event.isAllDay) {
            return context.getString(R.string.ambient_label_all_day)
        }
        return context.getString(
            R.string.ambient_label_next_at,
            CalendarDisplayHelper.formatTime(event.startMillis)
        )
    }

    private fun renderAmbientMusic() {
        val context = root.context
        val info = ambientNowPlaying
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession &&
            info.title.isNotBlank()
        if (!show) {
            ambientMusicCorner.visibility = View.GONE
            NowPlayingArtwork.reset(ambientArtworkState)
            updateAmbientDivider()
            return
        }
        val track = info!!
        ambientMusicCorner.visibility = View.VISIBLE
        updateAmbientDivider()
        ambientTrackTitle.text = track.title
        ambientTrackArtist.text = track.artist.ifBlank {
            context.getString(R.string.unknown_artist)
        }
        NowPlayingArtwork.bind(
            imageAmbientAlbumArt,
            imageAmbientAlbumPlaceholder,
            track,
            ambientArtworkState
        )
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
        private const val SECONDS_FADE_OUT_MS = 900L
        private const val SECONDS_FADE_IN_MS = 220L
    }
}
