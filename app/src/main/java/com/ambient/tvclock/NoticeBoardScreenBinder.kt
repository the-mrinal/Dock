package com.ambient.tvclock

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Binds the notice board: one notice on screen at a time, stepped with D-PAD
 * UP/DOWN and auto-advanced while nobody touches the remote.
 *
 * The notices are rendered as one document of full-height cards (see
 * [NoticeHtml]) and "paging" is a scroll of exactly one WebView height. Doing
 * it that way rather than reloading a single card per step means stepping
 * costs nothing and images stay decoded — but it does mean the card height in
 * the CSS and the scroll step here must agree, so neither may change alone.
 *
 * JavaScript is off: notices are static HTML, so there is nothing to pause and
 * no reason to give feed content a script engine. The WebView is deliberately
 * non-focusable — the page root uses blocksDescendants — so MainActivity's
 * focusMovesWithinPage() never sees a focus candidate here and LEFT/RIGHT
 * always switch dashboard pages.
 */
class NoticeBoardScreenBinder(root: View) {

    private val context: Context = root.context
    private val container: FrameLayout = root.findViewById(R.id.noticesWebContainer)
    private val counter: TextView = root.findViewById(R.id.textNoticesCounter)
    private val handler = Handler(Looper.getMainLooper())

    private val webView: WebView = createWebView().also {
        container.addView(
            it,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var notices: List<Notice> = emptyList()
    private var renderedKey: Pair<List<Notice>, String>? = null
    private var index = 0
    private var pageVisible = false
    private var restoreAttempts = 0

    private val advanceRunnable = object : Runnable {
        override fun run() {
            if (!pageVisible || notices.size < 2) return
            showIndex(index + 1)
            handler.postDelayed(this, AUTO_ADVANCE_MS)
        }
    }

    /**
     * Show [live] (already filtered to what is on the board right now). Cheap
     * to call often: an unchanged list and base URL re-render nothing, which
     * matters because the minute tick and every poll come through here.
     */
    fun bind(live: List<Notice>, baseUrl: String, nowMillis: Long) {
        val key = live to baseUrl
        if (key == renderedKey) return

        // Hold the reader's place across a refresh when their notice is still
        // up; anything else starts at the top of the board.
        val currentId = notices.getOrNull(index)?.id
        notices = live
        renderedKey = key
        index = live.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        // The base URL is the feed's own address so relative image paths in a
        // notice resolve against it.
        webView.loadDataWithBaseURL(
            baseUrl.ifBlank { null },
            NoticeHtml.render(live, nowMillis),
            "text/html",
            "utf-8",
            null,
        )
        updateCounter()
        applyIndex()
        scheduleAdvance()
    }

    fun next() {
        showIndex(index + 1)
        scheduleAdvance()
    }

    fun previous() {
        showIndex(index - 1)
        scheduleAdvance()
    }

    fun onPageVisible() {
        pageVisible = true
        scheduleAdvance()
    }

    fun onPageHidden() {
        pageVisible = false
        handler.removeCallbacks(advanceRunnable)
    }

    fun destroy() {
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(restoreRunnable)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Wraps around, so a two-notice board loops with either direction. */
    private fun showIndex(target: Int) {
        if (notices.isEmpty()) {
            index = 0
            updateCounter()
            return
        }
        index = Math.floorMod(target, notices.size)
        updateCounter()
        applyIndex()
    }

    /**
     * Scroll the requested notice into view, retrying until it lands.
     *
     * A scroll is clamped to the content height the WebView has laid out so
     * far, and a fresh document is not that tall yet — so the obvious "scroll
     * once the page finishes" silently snaps back to the first card and leaves
     * the counter claiming a card nobody can see. Retry until the scroll takes,
     * and if the document truly cannot go that far, believe the screen and move
     * the counter to the card actually showing.
     */
    private fun applyIndex() {
        restoreAttempts = 0
        handler.removeCallbacks(restoreRunnable)
        handler.post(restoreRunnable)
    }

    private val restoreRunnable = object : Runnable {
        override fun run() {
            val step = webView.height
            val target = index * step
            if (step > 0) {
                webView.scrollTo(0, target)
                if (webView.scrollY == target) return
            }
            if (restoreAttempts++ < MAX_RESTORE_ATTEMPTS) {
                handler.postDelayed(this, RESTORE_RETRY_MS)
                return
            }
            if (step > 0 && notices.isNotEmpty()) {
                index = (webView.scrollY / step).coerceIn(0, notices.size - 1)
                updateCounter()
            }
        }
    }

    private fun updateCounter() {
        if (notices.size < 2) {
            counter.visibility = View.GONE
            return
        }
        counter.text = context.getString(R.string.notices_counter, index + 1, notices.size)
        counter.visibility = View.VISIBLE
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advanceRunnable)
        if (pageVisible && notices.size > 1) {
            handler.postDelayed(advanceRunnable, AUTO_ADVANCE_MS)
        }
    }

    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(Color.BLACK)
        webViewClient = object : WebViewClient() {
            // Display-only surface: a link on a notice has nowhere to go on a
            // TV with no pointer, so nothing navigates.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                // A load resets the scroll to the top; put the reader back on
                // the notice they were on, once the document is tall enough to
                // allow it.
                applyIndex()
            }
        }
    }

    companion object {
        // Long enough to read a notice, short enough that a second one on the
        // board is never effectively hidden.
        private const val AUTO_ADVANCE_MS = 30_000L

        // A fresh document reaches its full height within a frame or two;
        // roughly a second of retries covers a slow one without spinning.
        private const val RESTORE_RETRY_MS = 80L
        private const val MAX_RESTORE_ATTEMPTS = 12
    }
}
