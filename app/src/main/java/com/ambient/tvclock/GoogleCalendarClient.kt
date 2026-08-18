package com.ambient.tvclock

import android.util.Log
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Read-only Google Calendar API v3 client for the personal deck.
 *
 * Auth model: a Desktop-client refresh token provisioned once on a computer
 * (Google's TV device flow does not permit Calendar scopes — see
 * docs/CALENDAR_API_RESEARCH.md). Credentials arrive via BuildConfig from
 * local.properties; when absent, [isConfigured] is false and callers fall
 * back to the ICS feed.
 *
 * Delivers what ICS cannot: per-event colors (colorId → hex via the colors
 * endpoint, falling back to each calendar's own color), the user's RSVP,
 * attendee counts, Meet links, and server-side recurrence expansion.
 */
object GoogleCalendarClient {

    private const val TAG = "GoogleCalendarClient"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/calendar/v3"

    val isConfigured: Boolean
        get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank() &&
            BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET.isNotBlank() &&
            BuildConfig.GOOGLE_OAUTH_REFRESH_TOKEN.isNotBlank()

    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L

    /** colorId → background hex for events; fetched once, essentially static. */
    private var eventColors: Map<String, String>? = null

    /**
     * The colors endpoint still serves Google's legacy palette (e.g.
     * Blueberry = #5484ED), but the Google Calendar apps render the modern
     * palette (#3F51B5). Users see the modern hues, so prefer them; the
     * fetched map covers any id missing here.
     */
    private val MODERN_EVENT_COLORS = mapOf(
        "1" to "#7986CB",  // Lavender
        "2" to "#33B679",  // Sage
        "3" to "#8E24AA",  // Grape
        "4" to "#E67C73",  // Flamingo
        "5" to "#F6BF26",  // Banana
        "6" to "#F4511E",  // Tangerine
        "7" to "#039BE5",  // Peacock
        "8" to "#616161",  // Graphite
        "9" to "#3F51B5",  // Blueberry
        "10" to "#0B8043", // Basil
        "11" to "#D50000"  // Tomato
    )

    /**
     * Fetch events across all selected calendars in [windowStartMillis,
     * windowEndMillis), expanded to single instances and sorted by start.
     * Declined events are dropped (matching Google Calendar's own emphasis).
     * Returns null when the API is unreachable so the caller can fall back.
     */
    fun fetchEvents(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent>? {
        return try {
            val token = obtainAccessToken() ?: return null
            val colors = obtainEventColors(token)
            val calendars = fetchCalendarList(token) ?: return null
            val out = mutableListOf<CalendarEvent>()
            for (cal in calendars) {
                fetchCalendarEvents(token, cal, colors, windowStartMillis, windowEndMillis, out)
            }
            out.sortedBy { it.startMillis }
        } catch (e: Exception) {
            Log.e(TAG, "fetchEvents failed: ${e.message}", e)
            null
        }
    }

    @Synchronized
    private fun obtainAccessToken(): String? {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessTokenExpiresAt - 60_000L) return it }

        val body = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_OAUTH_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET)
            .add("refresh_token", BuildConfig.GOOGLE_OAUTH_REFRESH_TOKEN)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        HttpClients.shared.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "Token refresh failed: HTTP ${resp.code}")
                return null
            }
            val json = JSONObject(resp.body?.string() ?: return null)
            accessToken = json.optString("access_token").ifBlank { return null }
            accessTokenExpiresAt = now + json.optLong("expires_in", 3600L) * 1000L
            return accessToken
        }
    }

    private fun obtainEventColors(token: String): Map<String, String> {
        eventColors?.let { return it }
        val map = mutableMapOf<String, String>()
        getJson(token, "$API/colors?fields=event")?.optJSONObject("event")?.let { event ->
            for (key in event.keys()) {
                event.optJSONObject(key)?.optString("background")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { map[key] = it }
            }
        }
        if (map.isNotEmpty()) eventColors = map
        return map
    }

    private class CalendarRef(val id: String, val colorHex: String?)

    private fun fetchCalendarList(token: String): List<CalendarRef>? {
        val json = getJson(
            token,
            "$API/users/me/calendarList?fields=items(id,backgroundColor,selected,primary)"
        ) ?: return null
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<CalendarRef>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            // Respect the user's own calendar visibility choices in the
            // Google Calendar UI ("selected" checkbox); primary always shows.
            if (!item.optBoolean("selected", false) && !item.optBoolean("primary", false)) {
                continue
            }
            out.add(
                CalendarRef(
                    id = item.getString("id"),
                    colorHex = item.optString("backgroundColor").ifBlank { null }
                )
            )
        }
        return out
    }

    private fun fetchCalendarEvents(
        token: String,
        cal: CalendarRef,
        colors: Map<String, String>,
        windowStartMillis: Long,
        windowEndMillis: Long,
        out: MutableList<CalendarEvent>
    ) {
        val rfc3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val zone = ZoneId.systemDefault()
        val timeMin = OffsetDateTime.ofInstant(Date(windowStartMillis).toInstant(), zone).format(rfc3339)
        val timeMax = OffsetDateTime.ofInstant(Date(windowEndMillis).toInstant(), zone).format(rfc3339)
        val fields = "items(summary,start,end,location,colorId,status,transparency," +
            "attendees(self,responseStatus),organizer(displayName,email),hangoutLink,eventType)"
        val url = "$API/calendars/${java.net.URLEncoder.encode(cal.id, "UTF-8")}/events" +
            "?singleEvents=true&orderBy=startTime&maxResults=100" +
            "&timeMin=${java.net.URLEncoder.encode(timeMin, "UTF-8")}" +
            "&timeMax=${java.net.URLEncoder.encode(timeMax, "UTF-8")}" +
            "&fields=${java.net.URLEncoder.encode(fields, "UTF-8")}"
        val json = getJson(token, url) ?: return
        val items = json.optJSONArray("items") ?: return

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("status") == "cancelled") continue
            val title = item.optString("summary")
            if (title.isBlank()) continue

            var myResponse: RsvpStatus? = null
            var attendeeCount = 0
            item.optJSONArray("attendees")?.let { attendees ->
                attendeeCount = attendees.length()
                for (a in 0 until attendees.length()) {
                    val attendee = attendees.getJSONObject(a)
                    if (attendee.optBoolean("self", false)) {
                        myResponse = when (attendee.optString("responseStatus")) {
                            "accepted" -> RsvpStatus.ACCEPTED
                            "tentative" -> RsvpStatus.TENTATIVE
                            "declined" -> RsvpStatus.DECLINED
                            else -> RsvpStatus.NEEDS_ACTION
                        }
                    }
                }
            }
            if (myResponse == RsvpStatus.DECLINED) continue

            val start = item.optJSONObject("start") ?: continue
            val end = item.optJSONObject("end")
            val allDay = start.has("date")
            val startMillis: Long
            val endMillis: Long
            try {
                if (allDay) {
                    startMillis = LocalDate.parse(start.getString("date"))
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    // API end.date is exclusive; render as inclusive end-of-window.
                    endMillis = end?.optString("date")?.ifBlank { null }
                        ?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }
                        ?: (startMillis + 24 * 60 * 60 * 1000L)
                } else {
                    startMillis = OffsetDateTime.parse(start.getString("dateTime"))
                        .toInstant().toEpochMilli()
                    endMillis = end?.optString("dateTime")?.ifBlank { null }
                        ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
                        ?: (startMillis + 60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unparsable event time: ${e.message}")
                continue
            }

            out.add(
                CalendarEvent(
                    title = title,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    isAllDay = allDay,
                    location = item.optString("location"),
                    source = CalendarSource.PERSONAL,
                    busyStatus = if (item.optString("transparency") == "transparent") {
                        BusyStatus.FREE
                    } else {
                        BusyStatus.BUSY
                    },
                    onlineMeetingUrl = item.optString("hangoutLink").ifBlank { null },
                    organizer = item.optJSONObject("organizer")?.let { org ->
                        org.optString("displayName").ifBlank { org.optString("email") }
                    }?.ifBlank { null },
                    colorHex = item.optString("colorId").ifBlank { null }
                        ?.let { MODERN_EVENT_COLORS[it] ?: colors[it] } ?: cal.colorHex,
                    myResponse = myResponse,
                    attendeeCount = attendeeCount
                )
            )
        }
    }

    private fun getJson(token: String, url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        HttpClients.shared.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "GET ${url.substringBefore('?')} failed: HTTP ${resp.code}")
                return null
            }
            return JSONObject(resp.body?.string() ?: return null)
        }
    }
}
