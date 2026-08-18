package com.ambient.tvclock

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Binds the Split Decks home screen: a compact clock, a priority "stage"
 * card (live meeting > now playing > next up), and two provider decks —
 * Personal rendered in Google Calendar's vernacular (color dots), Work in
 * Outlook's (busy bars, Teams/RSVP/category metadata).
 *
 * Public API is unchanged from the previous layout so MainActivity's
 * ambient / watchdog / minimal-wallpaper plumbing keeps working as-is.
 */
class HomeScreenBinder(private val root: View) {

    // Clock cluster
    private val clockGroup: ViewGroup = root.findViewById(R.id.homeClockGroup)
    private val textClockTime: TextView = root.findViewById(R.id.textHomeClockTime)
    private val textClockSeconds: TextView = root.findViewById(R.id.textHomeClockSeconds)
    private val textClockAmPm: TextView = root.findViewById(R.id.textHomeClockAmPm)
    private val textClockDate: TextView = root.findViewById(R.id.textHomeClockDate)
    private val textMinimalWhisper: TextView = root.findViewById(R.id.textHomeMinimalWhisper)

    // Stage
    private val stage: View = root.findViewById(R.id.homeStage)
    private val stageArtFrame: View = root.findViewById(R.id.homeStageArtFrame)
    private val imageStageArt: ImageView = root.findViewById(R.id.imageHomeStageArt)
    private val imageStagePlaceholder: ImageView = root.findViewById(R.id.imageHomeStagePlaceholder)
    private val textStageTag: TextView = root.findViewById(R.id.textHomeStageTag)
    private val textStageInfo: TextView = root.findViewById(R.id.textHomeStageInfo)
    private val textStageTitle: TextView = root.findViewById(R.id.textHomeStageTitle)
    private val textStageMeta: TextView = root.findViewById(R.id.textHomeStageMeta)
    private val stageProgressTrack: FrameLayout = root.findViewById(R.id.homeStageProgressTrack)
    private val stageProgressFill: View = root.findViewById(R.id.homeStageProgressFill)

    // Decks
    private val widgetsRow: View = root.findViewById(R.id.homeWidgetsRow)
    private val personalDeck = DeckViews(
        rows = root.findViewById(R.id.deckPersonalRows),
        quiet = root.findViewById(R.id.deckPersonalQuiet),
        glyph = root.findViewById(R.id.textDeckPersonalGlyph),
        quietTitle = root.findViewById(R.id.textDeckPersonalQuietTitle),
        quietSub = root.findViewById(R.id.textDeckPersonalQuietSub),
        footer = root.findViewById(R.id.textDeckPersonalFooter)
    )
    private val workDeck = DeckViews(
        rows = root.findViewById(R.id.deckWorkRows),
        quiet = root.findViewById(R.id.deckWorkQuiet),
        glyph = root.findViewById(R.id.textDeckWorkGlyph),
        quietTitle = root.findViewById(R.id.textDeckWorkQuietTitle),
        quietSub = root.findViewById(R.id.textDeckWorkQuietSub),
        footer = root.findViewById(R.id.textDeckWorkFooter)
    )

    // Music pill
    private val musicPill: View = root.findViewById(R.id.homeMusicPill)
    private val pillArtFrame: View = root.findViewById(R.id.homePillArtFrame)
    private val imagePillArt: ImageView = root.findViewById(R.id.imageHomePillArt)
    private val imagePillPlaceholder: ImageView = root.findViewById(R.id.imageHomePillPlaceholder)
    private val textPillTrack: TextView = root.findViewById(R.id.textHomePillTrack)
    private val textPillArtist: TextView = root.findViewById(R.id.textHomePillArtist)

    // Ambient strip (unchanged layer)
    private val ambientRow: View = root.findViewById(R.id.homeAmbientRow)
    private val ambientNowGroup: View = root.findViewById(R.id.homeAmbientNowGroup)
    private val ambientNowLabel: TextView = root.findViewById(R.id.textAmbientNowLabel)
    private val ambientNowTitle: TextView = root.findViewById(R.id.textAmbientNowTitle)
    private val ambientNextGroup: View = root.findViewById(R.id.homeAmbientNextGroup)
    private val ambientNextLabel: TextView = root.findViewById(R.id.textAmbientNextLabel)
    private val ambientNextTitle: TextView = root.findViewById(R.id.textAmbientNextTitle)
    private val ambientMusicCorner: View = root.findViewById(R.id.homeAmbientMusicCorner)
    private val ambientDivider: View = root.findViewById(R.id.homeAmbientDivider)
    private val ambientArtFrame: View = root.findViewById(R.id.homeAmbientArtFrame)
    private val imageAmbientAlbumArt: ImageView = root.findViewById(R.id.imageAmbientAlbumArt)
    private val imageAmbientAlbumPlaceholder: ImageView = root.findViewById(R.id.imageAmbientAlbumPlaceholder)
    private val ambientTrackTitle: TextView = root.findViewById(R.id.textAmbientTrackTitle)
    private val ambientTrackArtist: TextView = root.findViewById(R.id.textAmbientTrackArtist)

    private class DeckViews(
        val rows: LinearLayout,
        val quiet: View,
        val glyph: TextView,
        val quietTitle: TextView,
        val quietSub: TextView,
        val footer: TextView
    )

    private enum class StageMode { NONE, MEETING, MUSIC, NEXT }

    private val eyebrowFormatter = SimpleDateFormat("EEE · MMM d", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm", Locale.getDefault())
    private val secondsFormatter = SimpleDateFormat(":ss", Locale.getDefault())
    private val amPmFormatter = SimpleDateFormat("a", Locale.getDefault())
    private val rowTimeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val rowTimeShortFormatter = SimpleDateFormat("h:mm", Locale.getDefault())
    private val previewFormatter = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
    private val calendarBuffer = Calendar.getInstance()

    private val stageArtState = NowPlayingArtwork.State()
    private val pillArtState = NowPlayingArtwork.State()
    private val ambientArtworkState = NowPlayingArtwork.State()

    private var lastTimeText: String? = null
    private var lastSecondsText: String? = null
    private var lastAmPmText: String? = null
    private var lastDateText: String? = null
    private var lastRenderedMinute = -1L
    private var calendarSnapshot: CalendarSnapshot = CalendarSnapshot(emptyList(), 0L)
    private var nowPlaying: NowPlayingInfo? = null
    private var queueUpNext: String? = null
    private var stageMode = StageMode.NONE
    private var minimalWallpaperMode: Boolean = false
    private var widgetsAmbient: Boolean = false
    private val defaultClockTimePx: Float = textClockTime.textSize
    private val inflater = LayoutInflater.from(root.context)

    init {
        for (frame in arrayOf(stageArtFrame, pillArtFrame)) {
            frame.outlineProvider = object : ViewOutlineProviderRounded(8f) {}
            frame.clipToOutline = true
        }
        ambientArtFrame.outlineProvider = object : ViewOutlineProviderRounded(6f) {}
        ambientArtFrame.clipToOutline = true

        // Seconds collapse: AM/PM slides into the vacated slot (see the
        // previous layout's rationale — behavior is preserved verbatim).
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

    private abstract class ViewOutlineProviderRounded(private val radius: Float) :
        android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
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
        val date = eyebrowFormatter.format(now).uppercase(Locale.getDefault())
        if (force || date != lastDateText) {
            textClockDate.text = date
            lastDateText = date
        }

        // Stage countdowns ("43 MIN LEFT") and NOW transitions are minute-
        // grained; re-render when the minute flips rather than every second.
        val minute = calendarBuffer.timeInMillis / 60_000L
        if (force || minute != lastRenderedMinute) {
            lastRenderedMinute = minute
            renderHome()
        }
    }

    fun setSecondsVisible(visible: Boolean) {
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

    // ------------------------------------------------------------------
    // Data binds
    // ------------------------------------------------------------------

    fun bindCalendar(snapshot: CalendarSnapshot) {
        calendarSnapshot = snapshot
        renderHome()
        renderAmbientCalendar()
    }

    fun bindNowPlaying(info: NowPlayingInfo?) {
        nowPlaying = info
        renderHome()
        renderAmbientMusic()
    }

    fun bindQueue(snapshot: SpotifyQueueSnapshot) {
        val next = snapshot.upNext
        queueUpNext = if (snapshot.state == SpotifyQueueState.OK && next != null) {
            next.title
        } else {
            null
        }
        if (stageMode == StageMode.MUSIC) {
            renderHome()
        }
    }

    // ------------------------------------------------------------------
    // Master render
    // ------------------------------------------------------------------

    private fun renderHome() {
        if (minimalWallpaperMode) {
            stage.visibility = View.GONE
            musicPill.visibility = View.GONE
            // The ambient row is the richer version of the same thought — now
            // and next, laid out to be read across a room. When it is up, the
            // whisper is the same event printed twice.
            if (widgetsAmbient) {
                textMinimalWhisper.visibility = View.GONE
            } else {
                renderMinimalWhisper()
            }
            return
        }
        textMinimalWhisper.visibility = View.GONE
        renderStage()
        renderDeck(CalendarSource.PERSONAL, personalDeck)
        renderDeck(CalendarSource.WORK, workDeck)
        renderMusicPill()
    }

    private fun activeMusic(): NowPlayingInfo? {
        val info = nowPlaying ?: return null
        if (!NowPlayingPreferences.isEnabled(root.context)) return null
        if (!info.hasActiveSession || info.title.isBlank()) return null
        return info
    }

    private fun calendarEvents(): List<CalendarEvent> =
        if (CalendarPreferences.isEnabled(root.context)) calendarSnapshot.events else emptyList()

    // ------------------------------------------------------------------
    // Stage: live meeting > now playing > next up
    // ------------------------------------------------------------------

    private fun renderStage() {
        val context = root.context
        val now = System.currentTimeMillis()
        val events = calendarEvents()
        val live = events
            .filter { !it.isAllDay && it.isHappeningNow(now) }
            .minByOrNull { it.endMillis }
        val music = activeMusic()
        val nextToday = events
            .filter { !it.isAllDay && it.startMillis > now }
            .minByOrNull { it.startMillis }

        stageMode = when {
            live != null -> StageMode.MEETING
            music != null -> StageMode.MUSIC
            nextToday != null -> StageMode.NEXT
            else -> StageMode.NONE
        }

        when (stageMode) {
            StageMode.MEETING -> bindStageMeeting(context, live!!, now)
            StageMode.MUSIC -> bindStageMusic(context, music!!)
            StageMode.NEXT -> bindStageNext(context, nextToday!!, now)
            StageMode.NONE -> {
                stage.visibility = View.GONE
                return
            }
        }
        stage.visibility = View.VISIBLE
    }

    private fun bindStageMeeting(context: Context, event: CalendarEvent, now: Long) {
        val accent = ContextCompat.getColor(context, R.color.accent_now)
        applyStageAccent(accent)
        textStageTag.text = context.getString(R.string.stage_live_now)
        val range = formatRange(event)
        val minutesLeft = ((event.endMillis - now) / 60_000L + 1).coerceAtLeast(1)
        textStageInfo.text = if (minutesLeft <= 120) {
            context.getString(R.string.stage_min_left, range, minutesLeft)
        } else {
            range
        }
        textStageTitle.text = event.title
        textStageMeta.text = buildEventMeta(context, event, includeOrganizer = true)
        stageArtFrame.visibility = View.GONE

        stageProgressTrack.visibility = View.VISIBLE
        val fraction = ((now - event.startMillis).toFloat() /
            (event.endMillis - event.startMillis).coerceAtLeast(1L)).coerceIn(0f, 1f)
        stageProgressFill.setBackgroundColor(accent)
        stageProgressTrack.post {
            val lp = stageProgressFill.layoutParams
            lp.width = (stageProgressTrack.width * fraction).toInt().coerceAtLeast(1)
            stageProgressFill.layoutParams = lp
        }
    }

    private fun bindStageMusic(context: Context, info: NowPlayingInfo) {
        val accent = ContextCompat.getColor(context, R.color.accent_spotify)
        applyStageAccent(accent)
        textStageTag.text = context.getString(R.string.stage_now_playing)
        textStageInfo.text = info.album.ifBlank { "" }.uppercase(Locale.getDefault())
        textStageTitle.text = info.title
        val artist = info.artist.ifBlank { context.getString(R.string.unknown_artist) }
        textStageMeta.text = queueUpNext?.let {
            "$artist · ${context.getString(R.string.home_up_next, it, "").substringBefore(" — ").trim()}"
        } ?: artist
        stageProgressTrack.visibility = View.GONE
        stageArtFrame.visibility = View.VISIBLE
        NowPlayingArtwork.bind(imageStageArt, imageStagePlaceholder, info, stageArtState)
    }

    private fun bindStageNext(context: Context, event: CalendarEvent, now: Long) {
        val accent = eventAccentColor(context, event)
        applyStageAccent(accent)
        textStageTag.text = context.getString(R.string.stage_next_up)
        textStageInfo.text = context.getString(
            R.string.stage_in_duration,
            formatCountdown(event.startMillis - now)
        )
        textStageTitle.text = event.title
        val parts = mutableListOf(formatRange(event))
        cleanLocation(event)?.let { parts.add(it) }
        textStageMeta.text = parts.joinToString(" · ")
        stageProgressTrack.visibility = View.GONE
        stageArtFrame.visibility = View.GONE
    }

    private fun applyStageAccent(accent: Int) {
        // Layer 0 of the stage background is the accent rail (see bg_stage.xml).
        (stage.background?.mutate() as? android.graphics.drawable.LayerDrawable)
            ?.getDrawable(0)?.setTint(accent)
        textStageTag.background?.mutate()?.setTint(accent)
        textStageTag.setTextColor(contrastTextColor(accent))
    }

    // ------------------------------------------------------------------
    // Decks
    // ------------------------------------------------------------------

    private fun renderDeck(source: CalendarSource, deck: DeckViews) {
        val context = root.context
        val now = System.currentTimeMillis()
        val configured = when (source) {
            CalendarSource.PERSONAL ->
                CalendarPreferences.getPersonalUrl(context).isNotBlank() ||
                    GoogleCalendarClient.isConfigured
            CalendarSource.WORK -> CalendarPreferences.getWorkUrl(context).isNotBlank()
        }

        if (!CalendarPreferences.isEnabled(context) || !configured) {
            showDeckQuiet(deck, glyph = "+", title = context.getString(R.string.deck_setup_hint), sub = null)
            deck.footer.text = ""
            return
        }

        val events = calendarSnapshot.events
            .filter { it.source == source }
            .sortedBy { it.startMillis }

        if (events.isEmpty()) {
            when (source) {
                CalendarSource.PERSONAL -> showDeckQuiet(
                    deck,
                    glyph = "☾",
                    title = context.getString(R.string.deck_quiet_personal_title),
                    sub = context.getString(R.string.deck_quiet_personal_subtitle)
                )
                CalendarSource.WORK -> showDeckQuiet(
                    deck,
                    glyph = "✓",
                    title = context.getString(R.string.deck_quiet_work_title),
                    sub = context.getString(R.string.deck_quiet_work_subtitle)
                )
            }
            deck.footer.text = nextPreviewText(context, source)
            return
        }

        deck.quiet.visibility = View.GONE
        deck.rows.visibility = View.VISIBLE
        deck.rows.removeAllViews()

        val allDay = events.filter { it.isAllDay }
        val timed = events.filter { !it.isAllDay }
        val capacity = (MAX_ROWS - if (allDay.isNotEmpty()) 1 else 0).coerceAtLeast(1)
        val upcoming = timed.filter { !it.isPast(now) }
        val past = timed.filter { it.isPast(now) }
        val chosenUpcoming = upcoming.take(capacity)
        val fillPast = past.takeLast((capacity - chosenUpcoming.size).coerceAtLeast(0))
        val visibleTimed = (fillPast + chosenUpcoming).sortedBy { it.startMillis }

        allDay.firstOrNull()?.let { addDeckRow(deck.rows, it, now) }
        visibleTimed.forEach { addDeckRow(deck.rows, it, now) }

        val hidden = (timed.size - visibleTimed.size) + (allDay.size - allDay.size.coerceAtMost(1))
        deck.footer.text = buildFooter(context, source, events, now, hidden)
    }

    private fun showDeckQuiet(deck: DeckViews, glyph: String, title: String, sub: String?) {
        deck.rows.visibility = View.GONE
        deck.rows.removeAllViews()
        deck.quiet.visibility = View.VISIBLE
        deck.glyph.text = glyph
        deck.quietTitle.text = title
        if (sub == null) {
            deck.quietSub.visibility = View.GONE
        } else {
            deck.quietSub.visibility = View.VISIBLE
            deck.quietSub.text = sub
        }
    }

    private fun nextPreviewText(context: Context, source: CalendarSource): String {
        val next = calendarSnapshot.nextAfterToday[source]
            ?: return context.getString(R.string.deck_nothing_scheduled)
        val stamp = if (next.isAllDay) {
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(next.startMillis))
        } else {
            previewFormatter.format(Date(next.startMillis))
        }.uppercase(Locale.getDefault())
        val title = next.title.uppercase(Locale.getDefault()).let {
            if (it.length > 18) it.take(17).trimEnd() + "…" else it
        }
        return context.getString(R.string.deck_next_preview, stamp, title)
    }

    private fun buildFooter(
        context: Context,
        source: CalendarSource,
        events: List<CalendarEvent>,
        now: Long,
        hidden: Int
    ): String {
        val done = events.count { it.isPast(now) }
        val tentative = events.count { it.busyStatus == BusyStatus.TENTATIVE }
        val noun = when (source) {
            CalendarSource.PERSONAL -> if (events.size == 1) "EVENT" else "EVENTS"
            CalendarSource.WORK -> if (events.size == 1) "MEETING" else "MEETINGS"
        }
        val parts = mutableListOf("${events.size} $noun")
        if (done > 0) parts.add("$done DONE")
        if (source == CalendarSource.WORK && tentative > 0) parts.add("$tentative TENTATIVE")
        if (hidden > 0) parts.add(context.getString(R.string.deck_more_count, hidden))
        return parts.joinToString(" · ")
    }

    private fun addDeckRow(container: LinearLayout, event: CalendarEvent, now: Long) {
        val context = root.context
        val row = inflater.inflate(R.layout.item_home_deck_row, container, false)
        val rowRoot: View = row.findViewById(R.id.rowRoot)
        val dot: View = row.findViewById(R.id.rowDot)
        val bar: View = row.findViewById(R.id.rowBar)
        val timeStart: TextView = row.findViewById(R.id.rowTimeStart)
        val timeEnd: TextView = row.findViewById(R.id.rowTimeEnd)
        val title: TextView = row.findViewById(R.id.rowTitle)
        val nowTag: TextView = row.findViewById(R.id.rowNowTag)
        val meta: TextView = row.findViewById(R.id.rowMeta)

        val happening = !event.isAllDay && event.isHappeningNow(now)
        val past = event.isPast(now)

        if (event.isAllDay) {
            timeStart.text = context.getString(R.string.calendar_all_day)
            timeEnd.visibility = View.GONE
        } else {
            timeStart.text = rowTimeFormatter.format(Date(event.startMillis))
            timeEnd.text = "– ${rowTimeShortFormatter.format(Date(event.endMillis))}"
        }
        title.text = event.title

        when (event.source) {
            CalendarSource.PERSONAL -> {
                dot.visibility = View.VISIBLE
                bar.visibility = View.GONE
                val color = when {
                    happening -> ContextCompat.getColor(context, R.color.accent_now)
                    event.isAllDay -> ContextCompat.getColor(context, R.color.gcal_allday)
                    else -> eventAccentColor(context, event)
                }
                dot.background?.mutate()?.setTint(color)
                if (event.isAllDay) {
                    stylePersonalAllDayPill(title)
                } else {
                    val metaText = buildEventMeta(context, event, includeOrganizer = false)
                    if (metaText.isNotEmpty()) {
                        meta.visibility = View.VISIBLE
                        meta.text = metaText
                    }
                }
            }
            CalendarSource.WORK -> {
                dot.visibility = View.GONE
                bar.visibility = View.VISIBLE
                if (event.busyStatus == BusyStatus.TENTATIVE) {
                    bar.setBackgroundResource(R.drawable.shape_event_bar_tentative)
                }
                val color = if (happening) {
                    ContextCompat.getColor(context, R.color.accent_now)
                } else {
                    ContextCompat.getColor(context, R.color.outlook_blue)
                }
                bar.background?.mutate()?.setTint(color)
                val metaText = buildEventMeta(context, event, includeOrganizer = false)
                if (metaText.isNotEmpty()) {
                    meta.visibility = View.VISIBLE
                    meta.text = metaText
                }
            }
        }

        if (happening) {
            nowTag.visibility = View.VISIBLE
            rowRoot.setBackgroundResource(R.drawable.bg_deck_row_now)
        }
        if (past) {
            rowRoot.alpha = PAST_ROW_ALPHA
            title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }

        container.addView(row)
    }

    private fun stylePersonalAllDayPill(title: TextView) {
        // The all-day title renders as a compact Google-style pill instead of
        // a plain line; shrink it to its content so the pill hugs the text.
        (title.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            title.layoutParams = lp
        }
        title.setBackgroundResource(R.drawable.bg_allday_pill)
        title.setTextColor(ContextCompat.getColor(title.context, R.color.gcal_allday))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val h = (10 * title.resources.displayMetrics.density).toInt()
        val v = (3 * title.resources.displayMetrics.density).toInt()
        title.setPadding(h, v, h, v)
    }

    /**
     * " · "-joined metadata line in each provider's own vocabulary:
     * meeting service (Teams/Meet/Zoom), RSVP proxy from busy status,
     * first category as a colored dot + name, then room/location.
     */
    private fun buildEventMeta(
        context: Context,
        event: CalendarEvent,
        includeOrganizer: Boolean
    ): CharSequence {
        val builder = SpannableStringBuilder()
        val dimColor = ContextCompat.getColor(context, R.color.text_dim)

        fun appendPart(text: String, color: Int, bold: Boolean = false) {
            if (builder.isNotEmpty()) {
                appendColored(builder, " · ", dimColor)
            }
            appendColored(builder, text, color, bold)
        }

        event.onlineMeetingUrl?.let { url ->
            val label = when {
                url.contains("teams.microsoft") -> context.getString(R.string.meta_teams)
                url.contains("meet.google") -> context.getString(R.string.meta_meet)
                url.contains("zoom.us") -> context.getString(R.string.meta_zoom)
                else -> null
            }
            label?.let {
                appendPart(it, ContextCompat.getColor(context, R.color.teams_chip), bold = true)
            }
        }

        // Real RSVP (API sources) beats the busy-status proxy (ICS sources).
        when (event.myResponse) {
            RsvpStatus.ACCEPTED -> appendPart(
                context.getString(R.string.meta_accepted),
                ContextCompat.getColor(context, R.color.rsvp_accepted),
                bold = true
            )
            RsvpStatus.TENTATIVE -> appendPart(
                context.getString(R.string.meta_tentative),
                ContextCompat.getColor(context, R.color.rsvp_tentative),
                bold = true
            )
            RsvpStatus.NEEDS_ACTION -> appendPart(
                context.getString(R.string.meta_awaiting),
                dimColor
            )
            RsvpStatus.DECLINED, RsvpStatus.ORGANIZER -> Unit
            null -> when (event.busyStatus) {
                BusyStatus.TENTATIVE -> appendPart(
                    context.getString(R.string.meta_tentative),
                    ContextCompat.getColor(context, R.color.rsvp_tentative),
                    bold = true
                )
                BusyStatus.OOF -> appendPart(
                    context.getString(R.string.meta_oof),
                    ContextCompat.getColor(context, R.color.text_muted)
                )
                BusyStatus.FREE -> appendPart(context.getString(R.string.meta_free), dimColor)
                BusyStatus.BUSY -> if (event.source == CalendarSource.WORK &&
                    (event.onlineMeetingUrl != null || event.organizer != null)
                ) {
                    // On the user's own published calendar, committed meetings
                    // ride as BUSY — a fair proxy for "accepted" until Graph lands.
                    appendPart(
                        context.getString(R.string.meta_accepted),
                        ContextCompat.getColor(context, R.color.rsvp_accepted),
                        bold = true
                    )
                }
            }
        }

        if (event.attendeeCount > 1) {
            appendPart(
                context.getString(R.string.meta_people, event.attendeeCount),
                ContextCompat.getColor(context, R.color.text_muted)
            )
        }

        event.categories.firstOrNull()?.let { category ->
            if (builder.isNotEmpty()) appendColored(builder, " · ", dimColor)
            appendColored(builder, "● ", categoryColor(category))
            appendColored(builder, category, ContextCompat.getColor(context, R.color.text_muted))
        }

        if (includeOrganizer) {
            event.organizer?.let {
                appendPart(it, ContextCompat.getColor(context, R.color.text_muted))
            }
        }

        cleanLocation(event)?.let {
            appendPart(it, ContextCompat.getColor(context, R.color.text_muted))
        }

        return builder
    }

    private fun appendColored(
        builder: SpannableStringBuilder,
        text: String,
        color: Int,
        bold: Boolean = false
    ) {
        val start = builder.length
        builder.append(text)
        builder.setSpan(
            ForegroundColorSpan(color),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (bold) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Location minus URLs (Outlook often stuffs the Teams link in there). */
    private fun cleanLocation(event: CalendarEvent): String? {
        val location = event.location.trim()
        if (location.isEmpty() || location.startsWith("http")) return null
        val cleaned = location.substringBefore("http").trim().trimEnd(';', ',')
        return cleaned.ifEmpty { null }
    }

    private fun eventAccentColor(context: Context, event: CalendarEvent): Int {
        event.colorHex?.let {
            try {
                return Color.parseColor(it)
            } catch (_: IllegalArgumentException) {
                // fall through to source accent
            }
        }
        return when (event.source) {
            CalendarSource.PERSONAL -> ContextCompat.getColor(context, R.color.gcal_fallback)
            CalendarSource.WORK -> ContextCompat.getColor(context, R.color.outlook_blue)
        }
    }

    private fun categoryColor(name: String): Int {
        val index = ((name.hashCode() % CATEGORY_COLORS.size) + CATEGORY_COLORS.size) %
            CATEGORY_COLORS.size
        return CATEGORY_COLORS[index]
    }

    private fun contrastTextColor(background: Int): Int {
        val luminance = (0.299 * Color.red(background) +
            0.587 * Color.green(background) +
            0.114 * Color.blue(background)) / 255.0
        return if (luminance > 0.55) 0xFF0A0A0B.toInt() else Color.WHITE
    }

    private fun formatRange(event: CalendarEvent): String {
        if (event.isAllDay) return root.context.getString(R.string.calendar_all_day)
        val start = rowTimeShortFormatter.format(Date(event.startMillis))
        val end = rowTimeFormatter.format(Date(event.endMillis))
        return "$start – $end"
    }

    private fun formatCountdown(deltaMillis: Long): String {
        val totalMinutes = (deltaMillis / 60_000L).coerceAtLeast(1)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours <= 0 -> "$minutes MIN"
            minutes == 0L -> "$hours H"
            else -> "$hours H $minutes M"
        }
    }

    // ------------------------------------------------------------------
    // Music pill
    // ------------------------------------------------------------------

    private fun renderMusicPill() {
        val info = activeMusic()
        val show = info != null && stageMode == StageMode.MEETING
        if (!show) {
            musicPill.visibility = View.GONE
            NowPlayingArtwork.reset(pillArtState)
            return
        }
        musicPill.visibility = View.VISIBLE
        textPillTrack.text = info!!.title
        textPillArtist.text = info.artist.ifBlank {
            root.context.getString(R.string.unknown_artist)
        }
        NowPlayingArtwork.bind(imagePillArt, imagePillPlaceholder, info, pillArtState)
    }

    // ------------------------------------------------------------------
    // Minimal wallpaper mode
    // ------------------------------------------------------------------

    fun setMinimalWallpaperMode(enabled: Boolean) {
        if (minimalWallpaperMode == enabled) return
        minimalWallpaperMode = enabled

        if (enabled) {
            textClockTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, MINIMAL_CLOCK_SP)
            textClockTime.alpha = MINIMAL_FOREGROUND_ALPHA
            textClockDate.alpha = MINIMAL_FOREGROUND_ALPHA * 0.75f
            textClockSeconds.visibility = View.GONE
            textClockAmPm.visibility = View.GONE
            widgetsRow.visibility = View.INVISIBLE
            stage.visibility = View.GONE
            musicPill.visibility = View.GONE
        } else {
            textClockTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultClockTimePx)
            textClockTime.alpha = 1f
            textClockDate.alpha = 1f
            textClockAmPm.visibility = View.VISIBLE
            widgetsRow.visibility = View.VISIBLE
            textMinimalWhisper.visibility = View.GONE
        }
        applyWallpaperTextShadows(enabled)
        positionAmbientRow(enabled)
        renderHome()
    }

    /**
     * Keep the artwork clear when a photo owns the screen.
     *
     * The ambient row is centred, which is also where a grainstorm wallpaper
     * sets its quote — the two land on top of each other and neither reads.
     * Over a photo the row goes to the bottom-left corner, under the clock:
     * one left-hand column of text, the whole rest of the frame left to the
     * image. Centred again as soon as there is no photo to work around, so
     * album art and the plain black screensaver are untouched.
     */
    private fun positionAmbientRow(overPhoto: Boolean) {
        val params = ambientRow.layoutParams as? FrameLayout.LayoutParams ?: return
        params.gravity = if (overPhoto) {
            Gravity.BOTTOM or Gravity.START
        } else {
            Gravity.CENTER
        }
        params.bottomMargin = if (overPhoto) {
            (AMBIENT_ROW_PHOTO_BOTTOM_DP * root.resources.displayMetrics.density).toInt()
        } else {
            0
        }
        ambientRow.layoutParams = params
        // Centred content reads as stranded once the block is in a corner.
        (ambientRow as? LinearLayout)?.gravity = if (overPhoto) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }
    }

    /** One quiet line under the clock when a photo owns the screen. */
    private fun renderMinimalWhisper() {
        val context = root.context
        val now = System.currentTimeMillis()
        val next = CalendarDisplayHelper.nextUpcoming(calendarEvents(), now)
        if (next == null) {
            textMinimalWhisper.visibility = View.GONE
            return
        }
        val prefix = when {
            next.isAllDay -> context.getString(R.string.calendar_all_day)
            next.isHappeningNow(now) -> context.getString(R.string.calendar_happening_now)
            else -> CalendarDisplayHelper.formatTime(next.startMillis)
        }
        textMinimalWhisper.visibility = View.VISIBLE
        textMinimalWhisper.text = "$prefix · ${next.title}"
    }

    private fun applyWallpaperTextShadows(enabled: Boolean) {
        val shadowColor = if (enabled) MINIMAL_TEXT_SHADOW_COLOR else 0
        val radius = if (enabled) MINIMAL_TEXT_SHADOW_RADIUS else 0f
        val dy = if (enabled) MINIMAL_TEXT_SHADOW_DY else 0f
        for (tv in arrayOf(textClockTime, textClockDate, textMinimalWhisper)) {
            tv.setShadowLayer(radius, 0f, dy, shadowColor)
        }
    }

    // ------------------------------------------------------------------
    // Ambient
    // ------------------------------------------------------------------

    fun setWidgetsAmbient(ambient: Boolean) {
        widgetsAmbient = ambient
        renderHome()
        if (ambient) {
            renderAmbientCalendar()
            renderAmbientMusic()
            animateAlpha(widgetsRow, 0f, AMBIENT_FADE_OUT_MS)
            animateAlpha(stage, 0f, AMBIENT_FADE_OUT_MS)
            animateAlpha(musicPill, 0f, AMBIENT_FADE_OUT_MS)
            animateAlpha(ambientRow, 1f, AMBIENT_FADE_OUT_MS)
        } else {
            animateAlpha(widgetsRow, 1f, AMBIENT_FADE_IN_MS)
            animateAlpha(stage, 1f, AMBIENT_FADE_IN_MS)
            animateAlpha(musicPill, 1f, AMBIENT_FADE_IN_MS)
            animateAlpha(ambientRow, 0f, AMBIENT_FADE_IN_MS)
        }
    }

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
        val events = calendarSnapshot.events
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

    private fun updateAmbientDivider() {
        val musicShown = ambientMusicCorner.visibility == View.VISIBLE
        val calendarShown = ambientNowGroup.visibility == View.VISIBLE ||
            ambientNextGroup.visibility == View.VISIBLE
        ambientDivider.visibility = if (musicShown && calendarShown) View.VISIBLE else View.GONE
    }

    private fun ambientLabelForNow(context: Context, event: CalendarEvent, now: Long): String {
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

    private fun ambientLabelForNext(context: Context, event: CalendarEvent): String {
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
        val info = nowPlaying
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
        private const val AMBIENT_FADE_OUT_MS = 1200L
        private const val AMBIENT_FADE_IN_MS = 320L
        private const val SECONDS_FADE_OUT_MS = 900L
        private const val SECONDS_FADE_IN_MS = 220L

        /** Timed rows per deck (an all-day pill consumes one slot). */
        private const val MAX_ROWS = 4
        private const val PAST_ROW_ALPHA = 0.38f

        /** How far above the bottom edge the now/next row sits over a photo. */
        private const val AMBIENT_ROW_PHOTO_BOTTOM_DP = 24f
        private const val MINIMAL_CLOCK_SP = 56f
        private const val MINIMAL_FOREGROUND_ALPHA = 0.7f
        private const val MINIMAL_TEXT_SHADOW_COLOR = 0xCC000000.toInt()
        private const val MINIMAL_TEXT_SHADOW_RADIUS = 4f
        private const val MINIMAL_TEXT_SHADOW_DY = 2f

        /**
         * Outlook preset-ish palette for category dots; the ICS CATEGORIES
         * property carries names only, so color is assigned by stable hash.
         */
        private val CATEGORY_COLORS = intArrayOf(
            0xFFF7630C.toInt(),
            0xFF10893E.toInt(),
            0xFF0078D4.toInt(),
            0xFF8764B8.toInt(),
            0xFFC239B3.toInt(),
            0xFFCA5010.toInt()
        )
    }
}
