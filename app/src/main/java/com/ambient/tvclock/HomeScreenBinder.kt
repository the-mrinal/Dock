package com.ambient.tvclock

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Drives the redesigned Home surface (artboards 01 / 02 / 03).
 *
 * Active mode shows the HeroClock + two widget cards ("Today" + "Now playing").
 * Ambient mode crossfades the widget row out, slides the clock −40dp upward,
 * dims it to 78%, and reveals the horizontal "Tickler" capsule below the
 * clock with NowPlaying | divider | NOW event blocks.
 */
class HomeScreenBinder(private val root: View) {

    // --- Hero clock + date.
    private val clockGroup: ViewGroup = root.findViewById(R.id.homeClockGroup)
    private val heroClock: View = root.findViewById(R.id.homeHeroClock)
    private val textClockTime: TextView = root.findViewById(R.id.textHomeClockTime)
    private val textClockSeconds: TextView = root.findViewById(R.id.textHomeClockSeconds)
    private val textClockAmPm: TextView = root.findViewById(R.id.textHomeClockAmPm)
    private val textClockDate: TextView = root.findViewById(R.id.textHomeClockDate)
    private val textIdleHint: TextView = root.findViewById(R.id.textHomeIdleHint)

    // --- Active state: widget grid.
    private val widgetsRow: View = root.findViewById(R.id.homeWidgetsRow)

    // Today widget views.
    private val textTodayBadge: TextView = root.findViewById(R.id.textHomeTodayBadge)
    private val textTodayUntil: TextView = root.findViewById(R.id.textHomeTodayUntil)
    private val textTodayTitle: TextView = root.findViewById(R.id.textHomeTodayTitle)
    private val textTodayMeta: TextView = root.findViewById(R.id.textHomeTodayMeta)
    private val homeTodayAlsoChip: View = root.findViewById(R.id.homeTodayAlsoChip)
    private val textTodayAlsoTitle: TextView = root.findViewById(R.id.textHomeTodayAlsoTitle)
    private val homeTodayNextRow: View = root.findViewById(R.id.homeTodayNextRow)
    private val textTodayNextTime: TextView = root.findViewById(R.id.textHomeTodayNextTime)
    private val textTodayNextTitle: TextView = root.findViewById(R.id.textHomeTodayNextTitle)
    private val textTodayMore: TextView = root.findViewById(R.id.textHomeTodayMore)

    // Music widget views.
    private val musicArtFrame: View = root.findViewById(R.id.homeMusicArtFrame)
    private val imageAlbumArt: ImageView = root.findViewById(R.id.imageHomeAlbumArt)
    private val imagePlaceholder: ImageView = root.findViewById(R.id.imageHomeAlbumPlaceholder)
    private val textTrackTitle: TextView = root.findViewById(R.id.textHomeTrackTitle)
    private val textTrackArtist: TextView = root.findViewById(R.id.textHomeTrackArtist)
    private val textUpNext: TextView = root.findViewById(R.id.textHomeUpNext)

    // --- Ambient state: tickler capsule.
    private val ambientFrame: View = root.findViewById(R.id.homeAmbientFrame)
    private val ambientTickler: View = root.findViewById(R.id.homeAmbientTickler)
    private val homeAmbientMusic: View = root.findViewById(R.id.homeAmbientMusic)
    private val homeAmbientCalendar: View = root.findViewById(R.id.homeAmbientCalendar)
    private val homeAmbientDivider: View = root.findViewById(R.id.homeAmbientDivider)
    private val ambientArtFrame: View = root.findViewById(R.id.homeAmbientArtFrame)
    private val imageAmbientAlbumArt: ImageView = root.findViewById(R.id.imageAmbientAlbumArt)
    private val imageAmbientAlbumPlaceholder: ImageView =
        root.findViewById(R.id.imageAmbientAlbumPlaceholder)
    private val textAmbientTrackTitle: TextView = root.findViewById(R.id.textAmbientTrackTitle)
    private val textAmbientNowTitle: TextView = root.findViewById(R.id.textAmbientNowTitle)
    private val textAmbientDayTile: TextView = root.findViewById(R.id.textAmbientDayTile)

    // --- Formatters + caches.
    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm", Locale.getDefault())
    private val secondsFormatter = SimpleDateFormat(":ss", Locale.getDefault())
    private val amPmFormatter = SimpleDateFormat("a", Locale.getDefault())
    private val dayOfMonthFormatter = SimpleDateFormat("dd", Locale.getDefault())
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
    private var latestQueueTeaser: String? = null

    init {
        roundClip(musicArtFrame, 12f)
        roundClip(ambientArtFrame, 8f)
    }

    private fun roundClip(view: View, radiusDp: Float) {
        val pxRadius = radiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, pxRadius)
            }
        }
        view.clipToOutline = true
    }

    // ------------------------------------------------------------------
    // Clock
    // ------------------------------------------------------------------

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
        textAmbientDayTile.text = dayOfMonthFormatter.format(now)
    }

    /**
     * Hide the seconds field. Ambient mode hides it permanently; active mode
     * also collapses it after a short post-input fade so the clock face
     * settles into a calm h:mm read.
     */
    fun setSecondsVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (textClockSeconds.visibility == target) return
        textClockSeconds.visibility = target
    }

    // ------------------------------------------------------------------
    // Today widget (artboards 01 / 02)
    // ------------------------------------------------------------------

    fun bindCalendar(snapshot: CalendarSnapshot) {
        ambientCalendarSnapshot = snapshot
        renderAmbientCalendar()
        val context = root.context

        if (!CalendarPreferences.isEnabled(context)) {
            showTodayEmpty(context.getString(R.string.calendar_no_events))
            return
        }
        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            showTodayEmpty(context.getString(R.string.calendar_add_in_settings))
            return
        }

        val now = System.currentTimeMillis()
        val events = snapshot.events
        if (events.isEmpty()) {
            showTodayEmpty(context.getString(R.string.calendar_no_events))
            return
        }

        val next = CalendarDisplayHelper.nextUpcoming(events, now)
        if (next == null) {
            showTodayEmpty(context.getString(R.string.calendar_no_events))
            return
        }

        val happening = next.isHappeningNow(now)
        val concurrentOthers = if (happening) {
            events.count { it !== next && it.isHappeningNow(now) }
        } else 0

        // Badge: "Now · Personal" or "Until 12:00" or "Ends in 7m".
        applyTodayBadge(next, happening, now)

        textTodayTitle.text = next.title
        textTodayMeta.text =
            if (next.location.isNotEmpty()) next.location
            else CalendarDisplayHelper.sourceLabel(context, next.source)

        if (happening && concurrentOthers > 0) {
            // Multi-now variant — show "+N also now" chip with the other event's title.
            homeTodayAlsoChip.visibility = View.VISIBLE
            homeTodayNextRow.visibility = View.GONE
            val also = events.firstOrNull { it !== next && it.isHappeningNow(now) }
            textTodayAlsoTitle.text = also?.title.orEmpty()
        } else {
            // Single-now / upcoming variant — show "Next up · time · title" if a
            // following event exists.
            val following = events.firstOrNull { it !== next && !it.isPast(now) && it !== next }
            if (following != null) {
                homeTodayAlsoChip.visibility = View.GONE
                homeTodayNextRow.visibility = View.VISIBLE
                textTodayNextTime.text = CalendarDisplayHelper.formatTime(following.startMillis)
                textTodayNextTitle.text = following.title
            } else {
                homeTodayAlsoChip.visibility = View.GONE
                homeTodayNextRow.visibility = View.GONE
            }
        }

        val remaining = CalendarDisplayHelper.remainingCount(events, now, next)
        if (remaining > 0) {
            textTodayMore.visibility = View.VISIBLE
            textTodayMore.text = context.getString(R.string.home_widget_more_today, remaining)
        } else {
            textTodayMore.visibility = View.GONE
        }
    }

    private fun applyTodayBadge(
        event: CalendarEvent,
        happening: Boolean,
        now: Long
    ) {
        val context = root.context
        val isWork = event.source == CalendarSource.WORK

        textTodayBadge.visibility = View.VISIBLE
        textTodayBadge.setBackgroundResource(
            if (isWork) R.drawable.bg_now_badge_work else R.drawable.bg_now_badge_personal
        )
        textTodayBadge.setTextColor(0xFF0A0A0B.toInt())
        textTodayBadge.text = context.getString(
            if (isWork) R.string.home_widget_now_work else R.string.home_widget_now_personal
        )

        if (!happening) {
            // Upcoming — show "Until 12:00" using the event start time.
            textTodayUntil.setTextColor(0x99FFFFFF.toInt())
            textTodayUntil.text = context.getString(
                R.string.home_widget_until,
                CalendarDisplayHelper.formatTime(event.startMillis)
            )
            return
        }

        // Ending-soon variant — amber eyebrow when ≤10 minutes remain.
        val remainingMs = event.endMillis - now
        val remainingMin = (remainingMs / 60_000L).toInt()
        if (remainingMin in 0..ENDING_SOON_MINUTES) {
            textTodayUntil.setTextColor(0xFFE8B85C.toInt())
            textTodayUntil.text = context.getString(
                R.string.home_widget_ends_in,
                remainingMin.coerceAtLeast(0),
                CalendarDisplayHelper.formatTime(event.endMillis)
            )
        } else {
            textTodayUntil.setTextColor(0x99FFFFFF.toInt())
            textTodayUntil.text = context.getString(
                R.string.home_widget_until,
                CalendarDisplayHelper.formatTime(event.endMillis)
            )
        }
    }

    private fun showTodayEmpty(message: String) {
        textTodayBadge.visibility = View.GONE
        textTodayUntil.text = ""
        textTodayTitle.text = message
        textTodayMeta.text = ""
        homeTodayAlsoChip.visibility = View.GONE
        homeTodayNextRow.visibility = View.GONE
        textTodayMore.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Music widget
    // ------------------------------------------------------------------

    fun bindNowPlaying(info: NowPlayingInfo?) {
        ambientNowPlaying = info
        renderAmbientMusic()
        val context = root.context
        val show = NowPlayingPreferences.isEnabled(context) && info != null && info.hasActiveSession

        if (!show) {
            if (lastTrackKey != EMPTY_TRACK_KEY) {
                textTrackTitle.text = context.getString(R.string.home_nothing_playing)
                textTrackArtist.text = ""
                textTrackTitle.isSelected = false
                lastTrackKey = EMPTY_TRACK_KEY
            }
            textUpNext.visibility = View.INVISIBLE
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
        val next = snapshot.upNext
        if (snapshot.state == SpotifyQueueState.OK && next != null) {
            // The leading "Up next" word is rendered in XML; only the
            // title/artist value lives in this cell.
            val artist = next.artist.ifEmpty { "—" }
            val value = "${next.title} · $artist"
            latestQueueTeaser = value
            textUpNext.visibility = View.VISIBLE
            textUpNext.text = value
        } else {
            latestQueueTeaser = null
            textUpNext.visibility = View.INVISIBLE
        }
    }

    // ------------------------------------------------------------------
    // Active ↔ Ambient crossfade
    // ------------------------------------------------------------------

    /**
     * Drive the visual switch between the active dashboard widgets and the
     * ambient tickler. HeroClock translates upward by 40dp on entry (matching
     * the HTML's `translateY(-40)` transform on the clock during ambient).
     */
    fun setWidgetsAmbient(ambient: Boolean) {
        if (ambient) {
            renderAmbientCalendar()
            renderAmbientMusic()
            applyAmbientBlur(true)
            ambientFrame.visibility = View.VISIBLE
            animateAlpha(widgetsRow, 0f, AMBIENT_FADE_OUT_MS)
            animateAlpha(ambientFrame, 1f, AMBIENT_FADE_OUT_MS)
            animateAlpha(textIdleHint, 1f, AMBIENT_FADE_OUT_MS)
            // Slide the clock up by 40dp and dim to 78% so the tickler reads
            // as the brighter element. The number tracks the HTML's
            // `transform: translateY(-40px)` on HeroClock.
            heroClock.animate().cancel()
            heroClock.animate()
                .translationY(-CLOCK_AMBIENT_LIFT_PX())
                .alpha(0.78f)
                .setDuration(AMBIENT_FADE_OUT_MS)
                .start()
        } else {
            applyAmbientBlur(false)
            animateAlpha(widgetsRow, 1f, AMBIENT_FADE_IN_MS)
            animateAlpha(ambientFrame, 0f, AMBIENT_FADE_IN_MS)
            animateAlpha(textIdleHint, 0f, AMBIENT_FADE_IN_MS)
            heroClock.animate().cancel()
            heroClock.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(AMBIENT_FADE_IN_MS)
                .withEndAction { /* nothing — ambientFrame stays GONE-equivalent at alpha=0 */ }
                .start()
        }
    }

    /**
     * Apply / clear the tickler's backdrop look on ambient enter/exit.
     *
     * We deliberately do NOT call `setRenderEffect(blur)` on the tickler:
     * `RenderEffect` is forward-only on Android, so attaching a blur to
     * the tickler blurs its *own* text and album-art (Stream C's reported
     * bug). The sibling ArtWashView already produces a heavily blurred
     * radial wash, and the tickler's translucent capsule background
     * (`bg_ambient_capsule`) lets that wash show through — that is the
     * "backdrop blur." This function stays as a hook for future ambient
     * effects (e.g. saturate, color shift) without touching the contents.
     */
    private fun applyAmbientBlur(@Suppress("UNUSED_PARAMETER") enable: Boolean) {
        // Intentionally empty; see kdoc.
    }

    private fun CLOCK_AMBIENT_LIFT_PX(): Float =
        40f * root.resources.displayMetrics.density

    fun refreshAmbient() {
        renderAmbientCalendar()
        renderAmbientMusic()
    }

    private fun renderAmbientCalendar() {
        val context = root.context
        if (!CalendarPreferences.isEnabled(context)) {
            homeAmbientCalendar.visibility = View.GONE
            updateAmbientDivider()
            return
        }
        val now = System.currentTimeMillis()
        val events = ambientCalendarSnapshot.events
        val happening = events.firstOrNull { !it.isPast(now) && it.isHappeningNow(now) }
            ?: events.firstOrNull { !it.isPast(now) }

        if (happening != null) {
            homeAmbientCalendar.visibility = View.VISIBLE
            textAmbientNowTitle.text = happening.title
        } else {
            homeAmbientCalendar.visibility = View.GONE
        }
        updateAmbientDivider()
    }

    private fun renderAmbientMusic() {
        val context = root.context
        val info = ambientNowPlaying
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession &&
            info.title.isNotBlank()
        if (!show) {
            homeAmbientMusic.visibility = View.GONE
            NowPlayingArtwork.reset(ambientArtworkState)
            updateAmbientDivider()
            return
        }
        val track = info!!
        homeAmbientMusic.visibility = View.VISIBLE
        val artistSuffix = track.artist.ifBlank { context.getString(R.string.unknown_artist) }
        textAmbientTrackTitle.text = "${track.title} · $artistSuffix"
        NowPlayingArtwork.bind(
            imageAmbientAlbumArt,
            imageAmbientAlbumPlaceholder,
            track,
            ambientArtworkState
        )
        updateAmbientDivider()
    }

    private fun updateAmbientDivider() {
        val musicShown = homeAmbientMusic.visibility == View.VISIBLE
        val calendarShown = homeAmbientCalendar.visibility == View.VISIBLE
        homeAmbientDivider.visibility = if (musicShown && calendarShown) View.VISIBLE else View.GONE
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
        private const val AMBIENT_FADE_OUT_MS = 600L
        private const val AMBIENT_FADE_IN_MS = 300L
        private const val ENDING_SOON_MINUTES = 10
    }
}
