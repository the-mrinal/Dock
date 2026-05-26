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
    private val homeCalendarWidget: View = root.findViewById(R.id.homeCalendarWidget)
    private val homeMusicWidget: View = root.findViewById(R.id.homeMusicWidget)
    private val textCalendarSectionLabel: TextView = root.findViewById(R.id.textHomeCalendarSectionLabel)
    private val textMusicSectionLabel: TextView = root.findViewById(R.id.textHomeMusicSectionLabel)
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
    private var minimalWallpaperMode: Boolean = false
    // Snapshot the original sizes in raw pixels so we can restore them without
    // having to know the scaledDensity (which is deprecated on newer SDKs).
    private val defaultClockTimePx: Float = textClockTime.textSize
    private val defaultClockDatePx: Float = textClockDate.textSize

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
        // Minimal wallpaper mode owns clock chrome; ignore the inactivity
        // watchdog's setSecondsVisible(true) so the seconds stay hidden.
        if (minimalWallpaperMode) {
            if (textClockSeconds.visibility != View.GONE) {
                textClockSeconds.visibility = View.GONE
            }
            return
        }
        val target = if (visible) View.VISIBLE else View.GONE
        if (textClockSeconds.visibility == target) return
        textClockSeconds.visibility = target
    }

    /**
     * Engage / disengage the "minimal wallpaper" home layout. Triggered
     * automatically when an Unsplash photo becomes the active background so
     * the photo reads as the focal element with only the essentials painted
     * over it: clock, date, event title, track title.
     *
     * When enabled:
     *  - Widget card backgrounds vanish (no boxes)
     *  - Section labels, badges, meta chips, album art, artist, "up next"
     *    are all hidden — calendar widget shows just the event title, music
     *    widget shows just the track title.
     *  - The clock shrinks to [MINIMAL_CLOCK_SP], seconds + AM/PM are hidden,
     *    everything dims to [MINIMAL_FOREGROUND_ALPHA].
     *  - A dark drop-shadow is added to every text view that remains, so it
     *    stays legible over any photo.
     *
     * When disabled the clock/dates/backgrounds are restored. View-level
     * visibility of data-driven rows (badge, time, meta, teaser) is left to
     * the next bind* call — the pollers in MainActivity republish on resume,
     * so a stale hidden state self-corrects almost immediately.
     */
    fun setMinimalWallpaperMode(enabled: Boolean) {
        if (minimalWallpaperMode == enabled) return
        minimalWallpaperMode = enabled

        applyWallpaperBackground(enabled)
        applyWallpaperClock(enabled)
        applyWallpaperWidgets(enabled)
        applyWallpaperTextShadows(enabled)
    }

    private fun applyWallpaperBackground(enabled: Boolean) {
        val ctx = root.context
        val bg = if (enabled) null else ctx.getDrawable(R.drawable.bg_widget_card)
        homeCalendarWidget.background = bg
        homeMusicWidget.background = bg
    }

    private fun applyWallpaperClock(enabled: Boolean) {
        if (enabled) {
            textClockTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, MINIMAL_CLOCK_SP)
            textClockDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, MINIMAL_DATE_SP)
            textClockTime.alpha = MINIMAL_FOREGROUND_ALPHA
            textClockDate.alpha = MINIMAL_FOREGROUND_ALPHA * 0.75f
            textClockSeconds.visibility = View.GONE
            textClockAmPm.visibility = View.GONE
        } else {
            textClockTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, defaultClockTimePx)
            textClockDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, defaultClockDatePx)
            textClockTime.alpha = 1f
            textClockDate.alpha = 1f
            // Defer to the inactivity watchdog's decision on seconds visibility;
            // a forced VISIBLE here would override a recently-faded state.
            textClockAmPm.visibility = View.VISIBLE
        }
    }

    private fun applyWallpaperWidgets(enabled: Boolean) {
        if (enabled) {
            hideMinimalSubviews()
        } else {
            // Coming out of minimal mode: show the album-art slot + section
            // labels back. The data-driven rows (badge / time / meta / teaser
            // / artist / upNext) are visibility-flipped by the next bind*()
            // call based on current data, so we leave them alone here.
            textCalendarSectionLabel.visibility = View.VISIBLE
            textMusicSectionLabel.visibility = View.VISIBLE
            albumArtContainer.visibility = View.VISIBLE
            textTrackArtist.visibility = View.VISIBLE
        }

        // Fade the kept titles too so they read as "subtitled wallpaper"
        // rather than fighting the photo for attention.
        val titleAlpha = if (enabled) MINIMAL_FOREGROUND_ALPHA else 1f
        textCalendarPrimary.alpha = titleAlpha
        textTrackTitle.alpha = titleAlpha
    }

    /**
     * Hard-hide every view that minimal wallpaper mode wants out of the way.
     * Called from [setMinimalWallpaperMode] and re-applied at the end of each
     * data-binding pass so a calendar refresh / now-playing update can't
     * silently restore something to VISIBLE behind our back.
     */
    private fun hideMinimalSubviews() {
        textCalendarSectionLabel.visibility = View.GONE
        textMusicSectionLabel.visibility = View.GONE
        textCalendarBadge.visibility = View.GONE
        textCalendarTime.visibility = View.GONE
        textCalendarMeta.visibility = View.GONE
        textCalendarMeta.setBackgroundResource(0)
        textCalendarTeaser.visibility = View.GONE
        albumArtContainer.visibility = View.GONE
        textTrackArtist.visibility = View.GONE
        textUpNext.visibility = View.GONE
        textClockSeconds.visibility = View.GONE
        textClockAmPm.visibility = View.GONE
    }

    private fun enforceMinimalIfActive() {
        if (minimalWallpaperMode) hideMinimalSubviews()
    }

    private fun applyWallpaperTextShadows(enabled: Boolean) {
        val shadowColor = if (enabled) MINIMAL_TEXT_SHADOW_COLOR else 0
        val radius = if (enabled) MINIMAL_TEXT_SHADOW_RADIUS else 0f
        val dy = if (enabled) MINIMAL_TEXT_SHADOW_DY else 0f
        for (tv in arrayOf(
            textClockTime,
            textClockDate,
            textCalendarPrimary,
            textTrackTitle,
        )) {
            tv.setShadowLayer(radius, 0f, dy, shadowColor)
        }
    }

    fun bindCalendar(snapshot: CalendarSnapshot) {
        try {
            bindCalendarInternal(snapshot)
        } finally {
            enforceMinimalIfActive()
        }
    }

    private fun bindCalendarInternal(snapshot: CalendarSnapshot) {
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
        try {
            bindNowPlayingInternal(info)
        } finally {
            enforceMinimalIfActive()
        }
    }

    private fun bindNowPlayingInternal(info: NowPlayingInfo?) {
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
        enforceMinimalIfActive()
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

        // Minimal wallpaper mode: shrunk clock + dim everything so the photo
        // dominates. Sized so the time still reads at 10 ft viewing distance
        // on a 1080p / 4K panel but stops being the focal element.
        private const val MINIMAL_CLOCK_SP = 56f
        private const val MINIMAL_DATE_SP = 16f
        private const val MINIMAL_FOREGROUND_ALPHA = 0.7f

        // Dark halo behind every remaining text element so titles + the clock
        // stay legible even over a bright photo.
        private const val MINIMAL_TEXT_SHADOW_COLOR = 0xCC000000.toInt()
        private const val MINIMAL_TEXT_SHADOW_RADIUS = 4f
        private const val MINIMAL_TEXT_SHADOW_DY = 2f
    }
}
