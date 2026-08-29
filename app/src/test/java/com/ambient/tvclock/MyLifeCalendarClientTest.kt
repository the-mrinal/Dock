package com.ambient.tvclock

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON→[CalendarEvent] mapping against my-life's `CalendarRange` shape (contract draft 4.1). */
class MyLifeCalendarClientTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private val body = """
        {"from":"2026-09-02","to":"2026-09-09","days":[
          {"date":"2026-09-02","events":[
            {"uid":"3F2A@icloud.com","start":"2026-09-02T10:00:00+05:30","end":"2026-09-02T10:30:00+05:30","all_day":false,
             "title":"Standup","location":"Room 4","calendar":"Work","source":"work","busy_status":"tentative",
             "categories":["Team","Sync"],"online_meeting_url":"https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc",
             "organizer":"Priya S","color":"#0F6CBD","my_response":null,"attendee_count":0},
            {"uid":"e1@google.com","start":"2026-09-02T19:30:00+05:30","end":"2026-09-02T21:00:00+05:30","all_day":false,
             "title":"Dinner with Sam","location":"Toit","calendar":"Mrinal","source":"personal","busy_status":"busy",
             "categories":[],"online_meeting_url":"https://meet.google.com/abc-defg-hij","organizer":"Sam",
             "color":"#3F51B5","my_response":"accepted","attendee_count":2},
            {"uid":"trip","start":"2026-09-02T00:00:00+05:30","end":"2026-09-04T00:00:00+05:30","all_day":true,
             "title":"Goa","location":null,"calendar":"Home","source":"personal","busy_status":"free",
             "categories":[],"online_meeting_url":null,"organizer":null,"color":null,"my_response":null,"attendee_count":0}
          ]},
          {"date":"2026-09-03","events":[
            {"uid":"trip","start":"2026-09-02T00:00:00+05:30","end":"2026-09-04T00:00:00+05:30","all_day":true,
             "title":"Goa","location":null,"calendar":"Home","source":"personal","busy_status":"free",
             "categories":[],"online_meeting_url":null,"organizer":null,"color":null,"my_response":null,"attendee_count":0},
            {"uid":"w2","start":"2026-09-03T11:00:00+05:30","end":"2026-09-03T11:30:00+05:30","all_day":false,
             "title":"1:1","location":null,"calendar":"Work","source":"work","busy_status":"busy",
             "categories":[],"online_meeting_url":null,"organizer":null,"color":null,"my_response":null,"attendee_count":0}
          ]},
          {"date":"2026-09-04","events":[
            {"uid":"p2","start":"2026-09-04T08:00:00+05:30","end":"2026-09-04T09:00:00+05:30","all_day":false,
             "title":"Gym","location":null,"calendar":"Home","source":"personal","busy_status":"busy",
             "categories":[],"online_meeting_url":null,"organizer":null,"color":null,"my_response":null,"attendee_count":0}
          ]}
        ]}
    """.trimIndent()

    private fun millis(iso: String): Long = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun mapsEveryFieldOfTodaysEvents() {
        val parsed = MyLifeCalendarClient.parse(body, zone)
        assertEquals(listOf("Goa", "Standup", "Dinner with Sam"), parsed.today.map { it.title })

        val standup = parsed.today.first { it.title == "Standup" }
        assertEquals(millis("2026-09-02T10:00:00+05:30"), standup.startMillis)
        assertEquals(millis("2026-09-02T10:30:00+05:30"), standup.endMillis)
        assertEquals(CalendarSource.WORK, standup.source)
        assertEquals("Work", standup.calendar)
        assertEquals(BusyStatus.TENTATIVE, standup.busyStatus)
        assertEquals(listOf("Team", "Sync"), standup.categories)
        assertEquals("https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc", standup.onlineMeetingUrl)
        assertEquals("Priya S", standup.organizer)
        assertEquals("#0F6CBD", standup.colorHex)
        assertNull(standup.myResponse)
        assertEquals("Room 4", standup.location)

        val dinner = parsed.today.first { it.title == "Dinner with Sam" }
        assertEquals(CalendarSource.PERSONAL, dinner.source)
        assertEquals(RsvpStatus.ACCEPTED, dinner.myResponse)
        assertEquals(2, dinner.attendeeCount)
        assertEquals("e1@google.com", dinner.uid)

        val trip = parsed.today.first { it.title == "Goa" }
        assertTrue(trip.isAllDay)
        assertEquals("", trip.location)
        assertEquals(BusyStatus.FREE, trip.busyStatus)
        assertNull(trip.colorHex)
    }

    @Test
    fun nextAfterTodayIsTheFirstEventStartingTomorrowOrLaterPerSource() {
        val parsed = MyLifeCalendarClient.parse(body, zone)
        // The multi-day trip is listed under tomorrow too, but it started today — not "next".
        assertEquals("Gym", parsed.nextAfterToday[CalendarSource.PERSONAL]?.title)
        assertEquals("1:1", parsed.nextAfterToday[CalendarSource.WORK]?.title)
    }

    @Test
    fun emptyRangeGivesEmptySnapshot() {
        val parsed = MyLifeCalendarClient.parse("""{"from":"2026-09-02","to":"2026-09-02","days":[]}""", zone)
        assertTrue(parsed.today.isEmpty())
        assertTrue(parsed.nextAfterToday.isEmpty())
    }

    @Test
    fun malformedEventIsSkippedNotFatal() {
        val parsed = MyLifeCalendarClient.parse(
            """{"days":[{"date":"2026-09-02","events":[{"title":"no times"},
               {"uid":"ok","start":"2026-09-02T10:00:00+05:30","end":"2026-09-02T10:30:00+05:30","all_day":false,
                "title":"Fine","calendar":"Home","source":"personal"}]}]}""",
            zone
        )
        assertEquals(listOf("Fine"), parsed.today.map { it.title })
        assertEquals(BusyStatus.BUSY, parsed.today.single().busyStatus)
        assertEquals(0, parsed.today.single().attendeeCount)
    }
}
