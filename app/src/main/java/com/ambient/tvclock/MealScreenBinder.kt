package com.ambient.tvclock

import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Binds the Meal Plan screen ([R.layout.screen_meal]) to the current
 * [MealPlanSnapshot]. The screen is passive: there is no D-pad interaction
 * inside it, just a 60-second re-render that keeps the "in Xh Ym" countdown
 * and the hydration ribbon current.
 *
 * Render priority:
 *   1. URL not set        → empty-state overlay, content hidden.
 *   2. Fetch failed,
 *      no cache available → fetch-error overlay.
 *   3. Otherwise          → full content. Stale banner overlays added on top
 *                           when the snapshot is served from cache (offline)
 *                           or the plan is older than 14 days.
 */
class MealScreenBinder(private val root: View) {

    private val context = root.context

    private val scroll: ScrollView = root.findViewById(R.id.mealContentScroll)
    private val staleCacheBanner: TextView = root.findViewById(R.id.textMealStaleCache)
    private val planAgeBanner: TextView = root.findViewById(R.id.textMealPlanAge)

    private val emptyState: LinearLayout = root.findViewById(R.id.groupMealEmptyUrl)
    private val errorState: LinearLayout = root.findViewById(R.id.groupMealFetchError)
    private val errorTitle: TextView = root.findViewById(R.id.textMealFetchErrorTitle)
    private val errorUrl: TextView = root.findViewById(R.id.textMealFetchErrorUrl)
    private val errorSubtitle: TextView = root.findViewById(R.id.textMealFetchErrorSubtitle)

    private val textDayName: TextView = root.findViewById(R.id.textMealDayName)
    private val textKindBadge: TextView = root.findViewById(R.id.textMealKindBadge)
    private val textProteinBadge: TextView = root.findViewById(R.id.textMealProteinBadge)

    private val cardUpNext: View = root.findViewById(R.id.cardMealUpNext)
    private val textUpNextEyebrow: TextView = root.findViewById(R.id.textMealUpNextEyebrow)
    private val textUpNextTitle: TextView = root.findViewById(R.id.textMealUpNextTitle)
    private val textUpNextBody: TextView = root.findViewById(R.id.textMealUpNextBody)
    private val textUpNextProtein: TextView = root.findViewById(R.id.textMealUpNextProtein)
    private val textUpNextPreEvent: TextView = root.findViewById(R.id.textMealUpNextPreEvent)

    private val mealRowsContainer: LinearLayout = root.findViewById(R.id.mealRowsContainer)
    private val textNoDayData: TextView = root.findViewById(R.id.textMealNoDayData)

    private val textDayNote: TextView = root.findViewById(R.id.textMealDayNote)
    private val groupPrepNote: LinearLayout = root.findViewById(R.id.groupMealPrepNote)
    private val listPrepItems: LinearLayout = root.findViewById(R.id.listMealPrepItems)

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            currentSnapshot?.let { refreshUpNext(it) }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }
    private var currentSnapshot: MealPlanSnapshot? = null

    fun bind(snapshot: MealPlanSnapshot) {
        currentSnapshot = snapshot
        when {
            snapshot.error == MealPlanError.URL_EMPTY -> renderEmpty()
            snapshot.plan == null && snapshot.error == MealPlanError.FETCH_FAILED ->
                renderError(R.string.meal_fetch_error_title)
            snapshot.plan == null && snapshot.error == MealPlanError.PARSE_FAILED ->
                renderError(R.string.meal_parse_error_title)
            snapshot.plan != null -> renderContent(snapshot)
            else -> renderError(R.string.meal_fetch_error_title)
        }
        scheduleTick()
    }

    fun detach() {
        handler.removeCallbacks(refreshRunnable)
        currentSnapshot = null
    }

    /** Smooth-scroll the page by [dy] px. Called by MainActivity for D-pad UP/DOWN. */
    fun scrollBy(dy: Int) {
        scroll.smoothScrollBy(0, dy)
    }

    // ---------------------------- render branches ----------------------------

    private fun renderEmpty() {
        scroll.visibility = View.GONE
        errorState.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        staleCacheBanner.visibility = View.GONE
        planAgeBanner.visibility = View.GONE
    }

    private fun renderError(titleRes: Int) {
        scroll.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        errorTitle.setText(titleRes)
        val url = MealPlanPreferences.getUrl(context)
        errorUrl.visibility = if (url.isBlank()) View.GONE else View.VISIBLE
        errorUrl.text = url
        errorSubtitle.setText(R.string.meal_fetch_error_subtitle)
        staleCacheBanner.visibility = View.GONE
        planAgeBanner.visibility = View.GONE
    }

    private fun renderContent(snapshot: MealPlanSnapshot) {
        val plan = snapshot.plan ?: return
        scroll.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        errorState.visibility = View.GONE

        renderStaleBanners(snapshot)
        renderHeader(plan)
        renderMealRows(plan)
        renderDayNote(plan)
        renderPrepNote(plan)
        refreshUpNext(snapshot)
    }

    private fun renderStaleBanners(snapshot: MealPlanSnapshot) {
        if (snapshot.isFromCache && snapshot.lastUpdatedMillis > 0) {
            val ago = formatUpdatedAgo(System.currentTimeMillis() - snapshot.lastUpdatedMillis)
            staleCacheBanner.text = context.getString(R.string.meal_cache_stale_fmt, ago)
            staleCacheBanner.visibility = View.VISIBLE
        } else {
            staleCacheBanner.visibility = View.GONE
        }
        val generated = snapshot.plan?.generatedAtMs
        if (generated != null) {
            val days = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(generated).atZone(ZoneId.systemDefault()).toLocalDate(),
                LocalDateTime.now().toLocalDate()
            ).toInt()
            if (days >= STALE_PLAN_THRESHOLD_DAYS) {
                planAgeBanner.text = context.getString(R.string.meal_plan_age_fmt, days)
                planAgeBanner.visibility = View.VISIBLE
            } else {
                planAgeBanner.visibility = View.GONE
            }
        } else {
            planAgeBanner.visibility = View.GONE
        }
    }

    private fun renderHeader(plan: MealPlan) {
        val todayKey = MealEventTimeline.dayKey(LocalDateTime.now().toLocalDate())
        val day = plan.days[todayKey]
        val dayLabel = day?.label ?: todayKey.replaceFirstChar { it.uppercase() }
        textDayName.text = dayLabel

        val kind = day?.kind ?: DayKind.NORMAL
        val (kindText, kindColor, kindBg) = when (kind) {
            DayKind.VEG -> Triple(
                day?.kindLabel ?: context.getString(R.string.meal_kind_veg),
                ContextCompat.getColor(context, R.color.meal_olive),
                R.drawable.bg_meal_badge_olive
            )
            DayKind.DIY -> Triple(
                day?.kindLabel ?: context.getString(R.string.meal_kind_diy),
                ContextCompat.getColor(context, R.color.meal_red),
                R.drawable.bg_meal_badge_red
            )
            DayKind.NORMAL -> Triple(
                day?.kindLabel ?: context.getString(R.string.meal_kind_normal),
                ContextCompat.getColor(context, R.color.meal_olive),
                R.drawable.bg_meal_badge_olive
            )
        }
        textKindBadge.text = kindText
        textKindBadge.setTextColor(kindColor)
        textKindBadge.setBackgroundResource(kindBg)

        val proteinText = day?.proteinSummary
            ?: plan.dailyTargets?.proteinGrams?.let { "~$it g" }
        if (proteinText != null) {
            textProteinBadge.text = context.getString(R.string.meal_protein_target_fmt, proteinText)
            textProteinBadge.visibility = View.VISIBLE
        } else {
            textProteinBadge.visibility = View.GONE
        }
    }

    private fun renderMealRows(plan: MealPlan) {
        val todayKey = MealEventTimeline.dayKey(LocalDateTime.now().toLocalDate())
        val day = plan.days[todayKey]
        mealRowsContainer.removeAllViews()
        if (day == null || day.meals.isEmpty()) {
            textNoDayData.visibility = View.VISIBLE
            return
        }
        textNoDayData.visibility = View.GONE
        val inflater = LayoutInflater.from(context)
        day.meals.forEach { meal ->
            val row = inflater.inflate(R.layout.item_meal_row, mealRowsContainer, false)
            row.findViewById<TextView>(R.id.textMealRowTime).text = formatTimeShort(meal.time)
            row.findViewById<TextView>(R.id.textMealRowLabel).text = meal.label
            row.findViewById<TextView>(R.id.textMealRowBody).text = meal.body
            val proteinView = row.findViewById<TextView>(R.id.textMealRowProtein)
            if (meal.protein.isNullOrBlank()) {
                proteinView.visibility = View.GONE
            } else {
                proteinView.visibility = View.VISIBLE
                proteinView.text = meal.protein
            }
            mealRowsContainer.addView(row)
        }
    }

    private fun renderDayNote(plan: MealPlan) {
        val todayKey = MealEventTimeline.dayKey(LocalDateTime.now().toLocalDate())
        val note = plan.days[todayKey]?.note
        if (note.isNullOrBlank()) {
            textDayNote.visibility = View.GONE
        } else {
            textDayNote.visibility = View.VISIBLE
            textDayNote.text = HtmlCompat.fromHtml(note, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    private fun renderPrepNote(plan: MealPlan) {
        val todayKey = MealEventTimeline.dayKey(LocalDateTime.now().toLocalDate())
        val prep = plan.days[todayKey]?.prepForTomorrow.orEmpty()
        if (prep.isEmpty()) {
            groupPrepNote.visibility = View.GONE
            return
        }
        groupPrepNote.visibility = View.VISIBLE
        listPrepItems.removeAllViews()
        val inflater = LayoutInflater.from(context)
        prep.forEach { item ->
            val view = inflater.inflate(R.layout.item_meal_prep, listPrepItems, false) as TextView
            view.text = HtmlCompat.fromHtml("→ $item", HtmlCompat.FROM_HTML_MODE_LEGACY)
            listPrepItems.addView(view)
        }
    }

    private fun refreshUpNext(snapshot: MealPlanSnapshot) {
        val plan = snapshot.plan ?: run {
            cardUpNext.visibility = View.GONE
            return
        }
        val now = LocalDateTime.now()
        val next = MealEventTimeline.nextEvent(plan, now)
        if (next == null) {
            cardUpNext.visibility = View.GONE
            return
        }
        cardUpNext.visibility = View.VISIBLE
        val countdown = formatCountdown(now, next.time, next.dayKey)
        textUpNextEyebrow.text = context.getString(R.string.meal_up_next_eyebrow)
            .uppercase() + " · " + countdown.uppercase()
        textUpNextTitle.text = "${next.label} — ${formatTimeShort(next.time)}"

        val body = next.meal?.body ?: next.detail
        if (body.isNullOrBlank()) {
            textUpNextBody.visibility = View.GONE
        } else {
            textUpNextBody.visibility = View.VISIBLE
            textUpNextBody.text = body
        }

        val protein = next.meal?.protein
        if (protein.isNullOrBlank()) {
            textUpNextProtein.visibility = View.GONE
        } else {
            textUpNextProtein.visibility = View.VISIBLE
            textUpNextProtein.text =
                context.getString(R.string.meal_protein_label) + " · " + protein
        }

        renderPreEventRibbon(plan, now, next)
    }

    private fun renderPreEventRibbon(
        plan: MealPlan,
        now: LocalDateTime,
        next: MealEventTimeline.TimelineEntry
    ) {
        val lines = mutableListOf<String>()

        // Hydration prompt: only when the next event is a meal AND we're inside
        // the window. Shows the user's water reminder right before each meal.
        val hydration = plan.hydrationPrompt
        if (hydration != null && next.kind == EventKind.MEAL) {
            val minutesToNext = Duration.between(now.toLocalTime(), next.time).toMinutes()
            if (minutesToNext in 0..hydration.minutesBeforeMeal.toLong()) {
                lines += context.getString(
                    R.string.meal_before_event_fmt,
                    next.label,
                    hydration.text
                )
            }
        }

        // Pending buys before the next event (e.g. "Pick up milk on the way home").
        MealEventTimeline.pendingBuysBefore(plan, now, next).forEach { buy ->
            val detail = buy.detail
            val text = if (detail.isNullOrBlank()) buy.label else "${buy.label} — $detail"
            lines += context.getString(R.string.meal_before_event_fmt, next.label, text)
        }

        if (lines.isEmpty()) {
            textUpNextPreEvent.visibility = View.GONE
        } else {
            textUpNextPreEvent.visibility = View.VISIBLE
            textUpNextPreEvent.text = lines.joinToString("\n")
        }
    }

    // ---------------------------- formatting helpers ----------------------------

    private fun formatTimeShort(time: LocalTime): String {
        val is24 = DateFormat.is24HourFormat(context)
        val hour12 = if (time.hour == 0) 12 else if (time.hour > 12) time.hour - 12 else time.hour
        return if (is24) {
            "%02d:%02d".format(time.hour, time.minute)
        } else {
            val suffix = if (time.hour < 12) "AM" else "PM"
            if (time.minute == 0) "$hour12 $suffix" else "%d:%02d %s".format(hour12, time.minute, suffix)
        }
    }

    private fun formatCountdown(now: LocalDateTime, targetTime: LocalTime, targetDayKey: String): String {
        val today = MealEventTimeline.dayKey(now.toLocalDate())
        val targetDate = if (targetDayKey == today) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val target = LocalDateTime.of(targetDate, targetTime)
        val seconds = Duration.between(now, target).seconds.coerceAtLeast(0L)
        return when {
            seconds < 60 -> context.getString(R.string.meal_in_sec_fmt, seconds.toInt())
            seconds < 3600 -> context.getString(R.string.meal_in_min_fmt, (seconds / 60).toInt())
            else -> {
                val hours = (seconds / 3600).toInt()
                val mins = ((seconds % 3600) / 60).toInt()
                context.getString(R.string.meal_in_hr_min_fmt, hours, mins)
            }
        }
    }

    private fun formatUpdatedAgo(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000L
        return when {
            minutes < 1 -> context.getString(R.string.meal_updated_just_now)
            minutes < 60 -> context.getString(R.string.meal_updated_min_fmt, minutes.toInt())
            minutes < 60 * 24 -> context.getString(R.string.meal_updated_hr_fmt, (minutes / 60).toInt())
            else -> context.getString(R.string.meal_updated_day_fmt, (minutes / (60 * 24)).toInt())
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
        private const val STALE_PLAN_THRESHOLD_DAYS = 14
    }
}
