package com.ambient.tvclock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: the board draws exactly the notice its counter names, so the renderer
 * takes one notice and the document holds one card. That is what keeps the
 * screen and the counter from drifting apart — an earlier version scrolled a
 * strip of every live notice and could show a card nobody asked for.
 *
 * WHAT WE TEST: the single-card structure, the fixed canvas the sizing guide
 * is written against, and that titles are escaped while bodies stay raw.
 */
class NoticeHtmlTest {

    private fun notice(
        id: String = "a",
        title: String? = null,
        html: String = "<p>body of $id</p>",
    ) = Notice(id, title, html, null, 2_000_000L)

    @Test
    fun `renders the notice it is given as one card`() {
        val out = NoticeHtml.render(notice("first", html = "<p>body of first</p>"))
        assertTrue(out.contains("id=\"first\""))
        assertTrue(out.contains("body of first"))
        assertTrue("exactly one card", out.split("<article").size - 1 == 1)
    }

    @Test
    fun `the card fills the viewport on a fixed canvas`() {
        val out = NoticeHtml.render(notice())
        assertTrue("fixed canvas width", out.contains("content=\"width=1280\""))
        assertTrue("card fills the viewport", out.contains("height:100vh"))
        assertTrue("card clips rather than scrolls", out.contains("overflow:hidden"))
    }

    @Test
    fun `escapes the title but leaves the body markup alone`() {
        val out = NoticeHtml.render(
            notice(title = "Fish & <b>chips</b>", html = "<p class=\"x\">raw <b>markup</b></p>"),
        )
        assertTrue(out.contains("<h2>Fish &amp; &lt;b&gt;chips&lt;/b&gt;</h2>"))
        assertTrue(out.contains("<p class=\"x\">raw <b>markup</b></p>"))
    }

    @Test
    fun `escapes an id so it cannot break out of the attribute`() {
        val out = NoticeHtml.render(notice(id = "a\" onload=\"x"))
        assertFalse(out.contains("onload=\"x\">"))
        assertTrue(out.contains("id=\"a&quot; onload=&quot;x\""))
    }

    @Test
    fun `omits the heading when a notice has no title`() {
        assertFalse(NoticeHtml.render(notice()).contains("<h2>"))
    }

    @Test
    fun `an empty board renders a document with no card`() {
        val out = NoticeHtml.render(null)
        assertFalse(out.contains("<article"))
        assertTrue(out.contains("<body></body>"))
    }
}
