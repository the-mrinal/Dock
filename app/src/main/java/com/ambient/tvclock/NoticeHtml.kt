package com.ambient.tvclock

/**
 * Wraps notice fragments in Dock's own dark shell.
 *
 * The board shows one notice at a time: every card is exactly one viewport
 * tall and clips whatever overflows, so the document is `n` screens high and
 * [NoticeBoardScreenBinder] pages through it by scrolling in whole-view steps.
 * The height rules here are load-bearing rather than cosmetic — a card that
 * can grow would put the paging out of step with the screen.
 *
 * A short notice is centred in its card; `safe center` drops back to the top
 * the moment the content is taller than the card, so a long notice loses its
 * tail rather than its title — and on an engine that doesn't know `safe`, the
 * whole declaration is ignored and the card is top-aligned, which is the same
 * safe outcome.
 *
 * The document itself must stay scrollable (no `overflow:hidden` on the root)
 * or the WebView clamps its scroll range to one screen and paging goes
 * nowhere. Nothing can scroll it by hand: it holds no focus and the remote's
 * UP/DOWN are handled by MainActivity.
 *
 * The viewport is pinned to a fixed [CANVAS_WIDTH_PX] CSS pixels and scaled by
 * the WebView to whatever the panel is, which is what lets the feed be authored
 * against one known canvas size regardless of the TV.
 *
 * Pure string work, no Android types, so it is testable on the JVM.
 */
object NoticeHtml {

    /** The CSS-pixel width every notice is authored against. */
    const val CANVAS_WIDTH_PX = 1280

    /**
     * A full HTML document holding one card per notice live at [nowMillis], in
     * feed order. Filtering here as well as in the caller is deliberate: the
     * rendered document is what the paging index counts against, so the two
     * must never disagree.
     */
    fun render(notices: List<Notice>, nowMillis: Long): String {
        val live = notices.filter { it.isLive(nowMillis) }
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=")
            append(CANVAS_WIDTH_PX)
            append("\"><style>")
            append(STYLES)
            append("</style></head><body>")
            for (notice in live) {
                append("<article class=\"card\" id=\"")
                append(escape(notice.id))
                append("\">")
                notice.title?.let {
                    append("<h2>")
                    append(escape(it))
                    append("</h2>")
                }
                append("<div class=\"body\">")
                // The feed is self-hosted and trusted; the fragment goes in raw
                // so notices can carry their own markup and styling. JavaScript
                // is off in the WebView, so scripts in it are inert.
                append(notice.html)
                append("</div></article>")
            }
            append("</body></html>")
        }
    }

    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // Colours mirror res/values/colors.xml; sizes are in the 1280px canvas.
    private val STYLES = """
        html,body{margin:0;padding:0;background:#000;color:#F5F5F5;
        font-family:sans-serif-light,sans-serif;font-weight:300;font-size:28px;line-height:1.4}
        .card{box-sizing:border-box;height:100vh;overflow:hidden;padding:48px;background:#000;
        display:flex;flex-direction:column;justify-content:safe center}
        h2{margin:0 0 20px;font-family:sans-serif-medium,sans-serif;font-weight:500;
        font-size:40px;line-height:1.2;color:#F5F5F5}
        .body{color:#B3B3B3}
        .body p{margin:0 0 14px}
        .body p:last-child{margin-bottom:0}
        .body img{max-width:100%;height:auto}
        .body a{color:#F5F5F5;text-decoration:none}
        .body code{font-family:monospace;color:#F5F5F5}
        .body small,.body .meta{color:#707070}
    """.trimIndent().replace("\n", "")
}
