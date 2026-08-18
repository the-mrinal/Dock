package com.ambient.tvclock

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class IcalParserTimeZoneTest {

    private lateinit var originalTz: TimeZone

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
        // A zone with a different offset than any TZID under test, so a fallback to
        // the device zone or GMT shows up as a wrong epoch.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    @Test
    fun windowsTzidResolvesToIana() {
        val events = parseSingle(
            """
            DTSTART;TZID=India Standard Time:20260817T103000
            DTEND;TZID=India Standard Time:20260817T110000
            SUMMARY:Team Dynamo DSM
            """
        )
        assertEquals(epoch("Asia/Kolkata", 2026, 8, 17, 10, 30), events.single().startMillis)
        assertEquals(epoch("Asia/Kolkata", 2026, 8, 17, 11, 0), events.single().endMillis)
    }

    @Test
    fun utcSuffixOverridesDefaultZone() {
        val events = parseSingle(
            """
            DTSTART:20260817T050000Z
            DTEND:20260817T053000Z
            SUMMARY:UTC event
            """
        )
        // 05:00 UTC is the same instant as 10:30 IST.
        assertEquals(epoch("Asia/Kolkata", 2026, 8, 17, 10, 30), events.single().startMillis)
    }

    @Test
    fun ianaTzidPassesThrough() {
        val events = parseSingle(
            """
            DTSTART;TZID=Europe/Berlin:20260817T090000
            SUMMARY:Berlin event
            """
        )
        assertEquals(epoch("Europe/Berlin", 2026, 8, 17, 9, 0), events.single().startMillis)
        assertEquals("Europe/Berlin", events.single().timeZoneId)
    }

    @Test
    fun customTzidFallsBackToVtimezoneOffset() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Customized Time Zone
            BEGIN:STANDARD
            DTSTART:16010101T000000
            TZOFFSETFROM:+0530
            TZOFFSETTO:+0530
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID=Customized Time Zone:20260817T103000
            SUMMARY:Custom zone event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = IcalParser.parse(ics, CalendarSource.WORK)
        assertEquals(epoch("Asia/Kolkata", 2026, 8, 17, 10, 30), events.single().startMillis)
    }

    @Test
    fun resolveIdMapsWindowsNamesAndStripsQuotes() {
        assertEquals("Asia/Kolkata", IcalTimeZones.resolveId("India Standard Time"))
        assertEquals("America/Los_Angeles", IcalTimeZones.resolveId("\"Pacific Standard Time\""))
        assertEquals("Europe/Berlin", IcalTimeZones.resolveId("Europe/Berlin"))
        assertEquals("GMT+05:30", IcalTimeZones.resolveId("(UTC+05:30) Chennai, Kolkata, Mumbai, New Delhi"))
        assertNull(IcalTimeZones.resolveId("Totally Made Up Zone"))
        assertNull(IcalTimeZones.resolveId(null))
    }

    @Test
    fun offsetToZoneIdHandlesIcsOffsetForms() {
        assertEquals("GMT+05:30", IcalTimeZones.offsetToZoneId("+0530"))
        assertEquals("GMT-08:00", IcalTimeZones.offsetToZoneId("-08:00"))
        assertEquals("GMT+05:30", IcalTimeZones.offsetToZoneId("+053000"))
        assertNull(IcalTimeZones.offsetToZoneId("garbage"))
    }

    private fun parseSingle(veventBody: String): List<CalendarEvent> {
        val ics = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("BEGIN:VEVENT")
            veventBody.trimIndent().lines().filter { it.isNotBlank() }.forEach { appendLine(it) }
            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }
        return IcalParser.parse(ics, CalendarSource.WORK)
    }

    private fun epoch(tz: String, y: Int, month: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply {
            clear()
            set(y, month - 1, d, h, min, 0)
        }.timeInMillis
}
