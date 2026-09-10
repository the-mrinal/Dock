package com.ambient.tvclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: the board pages between notices by scrolling the WebView one view height
 * at a time, which only lands correctly if every card is exactly one viewport
 * tall and the document holds exactly the live notices. Those two properties
 * are a contract between this renderer and NoticeBoardScreenBinder.
 *
 * WHAT WE TEST: the card-per-notice structure, the height/viewport rules the
 * paging depends on, and that titles are escaped while bodies stay raw.
 */
class NoticeHtmlTest {

    private val now = 1_000_000L

    private fun notice(
        id: String,
        title: String? = null,
        html: String = "<p>body of $id</p>",
        startsAt: Long? = null,
        endsAt: Long = now + 1,
    ) = Notice(id, title, html, startsAt, endsAt)

    @Test
    fun `renders one card per live notice in order`() {
        val out = NoticeHtml.render(listOf(notice("first"), notice("second")), now)
        assertEquals(2, out.split("<article").size - 1)
        assertTrue(out.indexOf("body of first") < out.indexOf("body of second"))
        assertTrue(out.contains("id=\"first\""))
    }

    @Test
    fun `every card is one viewport tall on a fixed canvas`() {
        val out = NoticeHtml.render(listOf(notice("a")), now)
        assertTrue("fixed canvas width", out.contains("content=\"width=1280\""))
        assertTrue("card fills the viewport", out.contains("height:100vh"))
        assertTrue("card clips rather than scrolls", out.contains(".card{box-sizing:border-box;height:100vh;overflow:hidden"))
        // The document must stay scrollable or the WebView clamps its scroll
        // range to one screen and the board cannot page.
        assertFalse("root must not hide overflow", out.contains("html,body{margin:0;padding:0;background:#000;color:#F5F5F5;overflow:hidden"))
    }

    @Test
    fun `escapes the title but leaves the body markup alone`() {
        val out = NoticeHtml.render(
            listOf(notice("a", title = "Fish & <b>chips</b>", html = "<p class=\"x\">raw <b>markup</b></p>")),
            now,
        )
        assertTrue(out.contains("<h2>Fish &amp; &lt;b&gt;chips&lt;/b&gt;</h2>"))
        assertTrue(out.contains("<p class=\"x\">raw <b>markup</b></p>"))
    }

    @Test
    fun `escapes an id so it cannot break out of the attribute`() {
        val out = NoticeHtml.render(listOf(notice("a\" onload=\"x")), now)
        assertFalse(out.contains("onload=\"x\">"))
        assertTrue(out.contains("id=\"a&quot; onload=&quot;x\""))
    }

    @Test
    fun `omits a card with no title`() {
        val out = NoticeHtml.render(listOf(notice("a")), now)
        assertFalse(out.contains("<h2>"))
    }

    @Test
    fun `leaves out notices that are expired or not yet started`() {
        val out = NoticeHtml.render(
            listOf(
                notice("expired", endsAt = now),
                notice("future", startsAt = now + 1, endsAt = now + 2),
                notice("live"),
            ),
            now,
        )
        assertEquals(1, out.split("<article").size - 1)
        assertTrue(out.contains("body of live"))
    }

    @Test
    fun `an empty board renders a document with no cards`() {
        val out = NoticeHtml.render(emptyList(), now)
        assertFalse(out.contains("<article"))
        assertTrue(out.contains("<body></body>"))
    }
}
