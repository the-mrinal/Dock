package com.ambient.tvclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: the notice board is the one section whose content comes from a feed the
 * user hand-writes, so the parser meets typos, half-filled entries and clocks
 * in other timezones. It must drop exactly the broken notice and keep the rest.
 *
 * WHAT WE TEST: field mapping and ordering, the liveness window boundaries,
 * timestamp strictness, and every way a single notice can be malformed.
 */
class NoticeContractTest {

    private val start = "2026-09-10T18:00:00+05:30"
    private val end = "2026-09-12T12:00:00+05:30"
    private val startMillis = 1789043400000L // 2026-09-10T12:30Z
    private val endMillis = 1789194600000L // 2026-09-12T06:30Z

    private fun feed(notices: String, version: String = "\"version\":1,") =
        """{$version"notices":[$notices]}"""

    private val fullNotice = """
        {"id":"plumber","title":"Plumber Thursday","html":"<p>10 to 12</p>",
         "starts_at":"$start","ends_at":"$end"}
    """.trimIndent()

    @Test
    fun `parses every field of a notice`() {
        val notices = NoticeContract.parse(feed(fullNotice))!!
        assertEquals(1, notices.size)
        val notice = notices.first()
        assertEquals("plumber", notice.id)
        assertEquals("Plumber Thursday", notice.title)
        assertEquals("<p>10 to 12</p>", notice.html)
        assertEquals(startMillis, notice.startsAtMillis)
        assertEquals(endMillis, notice.endsAtMillis)
    }

    @Test
    fun `keeps feed order`() {
        val second = """{"id":"b","html":"<p>b</p>","ends_at":"$end"}"""
        val notices = NoticeContract.parse(feed("$fullNotice,$second"))!!
        assertEquals(listOf("plumber", "b"), notices.map { it.id })
    }

    @Test
    fun `ignores unknown fields and a missing version`() {
        val notice = """{"id":"a","html":"<p>a</p>","ends_at":"$end","colour":"red"}"""
        val notices = NoticeContract.parse(feed(notice, version = ""))!!
        assertEquals("a", notices.single().id)
    }

    @Test
    fun `an empty board parses to an empty list`() {
        assertEquals(emptyList<Notice>(), NoticeContract.parse("""{"version":1,"notices":[]}"""))
    }

    @Test
    fun `refuses an unknown version, junk, and a feed with no notices array`() {
        assertNull(NoticeContract.parse(feed(fullNotice, version = "\"version\":2,")))
        assertNull(NoticeContract.parse("not json at all"))
        assertNull(NoticeContract.parse(""))
        assertNull(NoticeContract.parse(null))
        assertNull(NoticeContract.parse("""{"version":1}"""))
    }

    @Test
    fun `a broken notice is dropped and its siblings survive`() {
        val broken = listOf(
            """{"id":"no-end","html":"<p>x</p>"}""",
            """{"id":"bad-end","html":"<p>x</p>","ends_at":"whenever"}""",
            """{"id":"naive-end","html":"<p>x</p>","ends_at":"2026-09-12T12:00:00"}""",
            """{"id":"bad-start","html":"<p>x</p>","starts_at":"soon","ends_at":"$end"}""",
            """{"id":"blank-html","html":"   ","ends_at":"$end"}""",
            """{"id":"no-html","ends_at":"$end"}""",
        )
        for (entry in broken) {
            val notices = NoticeContract.parse(feed("$entry,$fullNotice"))!!
            assertEquals("dropped only the broken one: $entry", listOf("plumber"), notices.map { it.id })
        }
    }

    @Test
    fun `a missing or null start means live immediately`() {
        val missing = """{"id":"a","html":"<p>a</p>","ends_at":"$end"}"""
        val explicitNull = """{"id":"b","html":"<p>b</p>","starts_at":null,"ends_at":"$end"}"""
        val notices = NoticeContract.parse(feed("$missing,$explicitNull"))!!
        assertEquals(2, notices.size)
        assertTrue(notices.all { it.startsAtMillis == null })
    }

    @Test
    fun `a null title is absent rather than the string null`() {
        val notice = """{"id":"a","title":null,"html":"<p>a</p>","ends_at":"$end"}"""
        assertNull(NoticeContract.parse(feed(notice))!!.single().title)
    }

    @Test
    fun `a notice with no id gets one from its position`() {
        val first = """{"html":"<p>a</p>","ends_at":"$end"}"""
        val notices = NoticeContract.parse(feed("$first,$fullNotice"))!!
        assertEquals(listOf("notice-0", "plumber"), notices.map { it.id })
    }

    @Test
    fun `timestamps need an offset`() {
        assertEquals(endMillis, NoticeContract.parseTimestamp(end))
        assertEquals(endMillis, NoticeContract.parseTimestamp("2026-09-12T06:30:00Z"))
        assertNull(NoticeContract.parseTimestamp("2026-09-12T12:00:00"))
        assertNull(NoticeContract.parseTimestamp("tomorrow"))
        assertNull(NoticeContract.parseTimestamp(""))
    }

    @Test
    fun `live from the start instant until the instant before the end`() {
        val notice = Notice("a", null, "<p>a</p>", startMillis, endMillis)
        assertFalse(notice.isLive(startMillis - 1))
        assertTrue("start is inclusive", notice.isLive(startMillis))
        assertTrue(notice.isLive(endMillis - 1))
        assertFalse("end is exclusive", notice.isLive(endMillis))
    }

    @Test
    fun `a notice with no start is live until its end`() {
        val notice = Notice("a", null, "<p>a</p>", null, endMillis)
        assertTrue(notice.isLive(0L))
        assertTrue(notice.isLive(endMillis - 1))
        assertFalse(notice.isLive(endMillis))
    }
}
