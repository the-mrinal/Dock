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
 * Each step re-renders the WebView with just that notice (see [NoticeHtml])
 * rather than scrolling one long document, so the card on screen is always the
 * one the counter names. The document holds no scroll position to lose.
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
    private var baseUrl: String = ""
    private var index = 0
    private var pageVisible = false

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
    fun bind(live: List<Notice>, feedUrl: String, nowMillis: Long) {
        val key = live to feedUrl
        if (key == renderedKey) return

        // Hold the reader's place across a refresh when their notice is still
        // up; anything else starts at the top of the board.
        val currentId = notices.getOrNull(index)?.id
        notices = live
        renderedKey = key
        baseUrl = feedUrl
        index = live.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        updateCounter()
        renderCurrent()
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
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Wraps around, so a two-notice board loops with either direction. */
    private fun showIndex(target: Int) {
        if (notices.isEmpty()) {
            index = 0
            updateCounter()
            renderCurrent()
            return
        }
        index = Math.floorMod(target, notices.size)
        updateCounter()
        renderCurrent()
    }

    /**
     * Draw the notice the counter is naming. The base URL is the feed's own
     * address, so relative image paths inside a notice resolve against it.
     */
    private fun renderCurrent() {
        webView.loadDataWithBaseURL(
            baseUrl.ifBlank { null },
            NoticeHtml.render(notices.getOrNull(index)),
            "text/html",
            "utf-8",
            null,
        )
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

        }
    }

    companion object {
        // Long enough to read a notice, short enough that a second one on the
        // board is never effectively hidden.
        private const val AUTO_ADVANCE_MS = 30_000L
    }
}
