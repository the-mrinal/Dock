package com.ambient.tvclock

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The Dock's one calendar source: my-life's `GET /calendar/upcoming?days=8`
 * (contract: openclaw-proposal/spec/my-life.openapi.yaml, tag `calendar`).
 *
 * Everything the old on-device ICS parser and Google client derived — busy
 * status, categories, meeting link, organizer, colour, RSVP, attendee count,
 * recurrence expansion, time zones — arrives pre-computed. This class only maps
 * JSON to [CalendarEvent] and splits "today" (day 0) from the per-source "next
 * after today" preview (days 1–7).
 */
object MyLifeCalendarClient {

    private const val TAG = "MyLifeCalendarClient"

    /** Today plus the seven days the empty deck's "NEXT ·" preview looks across. */
    const val LOOKAHEAD_DAYS = 8

    class Parsed(
        val today: List<CalendarEvent>,
        val nextAfterToday: Map<CalendarSource, CalendarEvent>
    )

    /** Null when my-life is unreachable or refuses the key — callers keep the last snapshot. */
    fun fetch(context: Context): Parsed? {
        val base = MyLifePreferences.getUrl(context)
        val key = MyLifePreferences.getKey(context)
        if (base.isBlank() || key.isBlank()) return null
        val request = Request.Builder()
            .url("$base/calendar/upcoming?days=$LOOKAHEAD_DAYS")
            .get()
            .header("X-Life-Key", key)
            .header("Accept", "application/json")
            .header("User-Agent", "Dock/${BuildConfig.VERSION_NAME}")
            .build()
        return try {
            HttpClients.shared.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from my-life")
                    return null
                }
                parse(response.body?.string() ?: return null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    internal fun parse(body: String, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val days = JSONObject(body).getJSONArray("days")
        if (days.length() == 0) return Parsed(emptyList(), emptyMap())
        val today = events(days.getJSONObject(0).getJSONArray("events"))
        val next = mutableMapOf<CalendarSource, CalendarEvent>()
        if (days.length() > 1) {
            val tomorrowStart = LocalDate.parse(days.getJSONObject(1).getString("date"))
                .atStartOfDay(zone).toInstant().toEpochMilli()
            for (i in 1 until days.length()) {
                for (event in events(days.getJSONObject(i).getJSONArray("events"))) {
                    // A multi-day event that began today is listed again tomorrow; it isn't "next".
                    if (event.startMillis < tomorrowStart) continue
                    val current = next[event.source]
                    if (current == null || event.startMillis < current.startMillis) {
                        next[event.source] = event
                    }
                }
            }
        }
        return Parsed(today, next)
    }

    private fun events(array: JSONArray): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>(array.length())
        for (i in 0 until array.length()) {
            try {
                out.add(event(array.getJSONObject(i)))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping event: ${e.message}")
            }
        }
        return out.sortedBy { it.startMillis }
    }

    private fun event(e: JSONObject): CalendarEvent = CalendarEvent(
        uid = e.optString("uid"),
        title = e.getString("title"),
        startMillis = OffsetDateTime.parse(e.getString("start")).toInstant().toEpochMilli(),
        endMillis = OffsetDateTime.parse(e.getString("end")).toInstant().toEpochMilli(),
        isAllDay = e.optBoolean("all_day", false),
        location = e.optStringOrNull("location").orEmpty(),
        calendar = e.optString("calendar"),
        source = if (e.optString("source") == "work") CalendarSource.WORK else CalendarSource.PERSONAL,
        busyStatus = when (e.optString("busy_status")) {
            "free" -> BusyStatus.FREE
            "tentative" -> BusyStatus.TENTATIVE
            "oof" -> BusyStatus.OOF
            else -> BusyStatus.BUSY
        },
        categories = e.optJSONArray("categories")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        onlineMeetingUrl = e.optStringOrNull("online_meeting_url"),
        organizer = e.optStringOrNull("organizer"),
        colorHex = e.optStringOrNull("color"),
        myResponse = when (e.optStringOrNull("my_response")) {
            "accepted" -> RsvpStatus.ACCEPTED
            "tentative" -> RsvpStatus.TENTATIVE
            "declined" -> RsvpStatus.DECLINED
            "needs_action" -> RsvpStatus.NEEDS_ACTION
            "organizer" -> RsvpStatus.ORGANIZER
            else -> null
        },
        attendeeCount = e.optInt("attendee_count", 0)
    )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }
}
