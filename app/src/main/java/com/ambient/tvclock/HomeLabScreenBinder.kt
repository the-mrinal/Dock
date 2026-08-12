package com.ambient.tvclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Binds the Home Lab page: a display-only WebView showing the user's home lab
 * dashboard. A single WebView is created lazily and re-parented into whichever
 * page root the adapter hands us, so the loaded page survives adapter swaps
 * (settings changes rebuild the pager) without a visible reload.
 *
 * The WebView is deliberately non-focusable — the page root uses
 * blocksDescendants — so MainActivity's focusMovesWithinPage() never sees a
 * focus candidate here and LEFT/RIGHT always switch dashboard pages.
 */
class HomeLabScreenBinder(private val context: Context) {

    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var errorGroup: View? = null
    private var loadedUrl: String? = null
    private var lastGoodLoadMs = 0L
    private var hadError = false
    private var pageVisible = false
    private val handler = Handler(Looper.getMainLooper())

    private val retryRunnable = Runnable {
        if (pageVisible) reload()
    }

    /** Called on every adapter bind — re-parents the retained WebView. */
    fun attach(root: View) {
        container = root.findViewById(R.id.homeLabWebContainer)
        errorGroup = root.findViewById(R.id.homeLabErrorGroup)
        val web = webView ?: createWebView().also { webView = it }
        if (web.parent !== container) {
            (web.parent as? ViewGroup)?.removeView(web)
            container?.addView(
                web,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        showError(hadError)
        loadIfNeeded()
    }

    fun onPageVisible() {
        pageVisible = true
        webView?.onResume()
        loadIfNeeded()
    }

    fun onPageHidden() {
        pageVisible = false
        webView?.onPause()
        handler.removeCallbacks(retryRunnable)
    }

    fun scrollBy(dy: Int) {
        val web = webView ?: return
        // WebView clamps the bottom edge internally; only guard the top.
        web.scrollTo(0, (web.scrollY + dy).coerceAtLeast(0))
    }

    fun destroy() {
        handler.removeCallbacks(retryRunnable)
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        container = null
        errorGroup = null
    }

    private fun loadIfNeeded() {
        val url = HomeLabPreferences.getUrl(context)
        val stale = SystemClock.elapsedRealtime() - lastGoodLoadMs > STALE_RELOAD_MS
        if (loadedUrl != url || hadError || lastGoodLoadMs == 0L || stale) {
            reload()
        }
    }

    private fun reload() {
        val web = webView ?: return
        handler.removeCallbacks(retryRunnable)
        hadError = false
        loadedUrl = HomeLabPreferences.getUrl(context)
        web.loadUrl(loadedUrl!!)
    }

    private fun showError(show: Boolean) {
        errorGroup?.visibility = if (show) View.VISIBLE else View.GONE
        webView?.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    // Display-only surface: allow same-host SPA routing, swallow
                    // everything else (external links have nowhere to go on TV).
                    val configuredHost = Uri.parse(HomeLabPreferences.getUrl(context)).host
                    return request.url.host != configuredHost
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    hadError = true
                    showError(true)
                    handler.removeCallbacks(retryRunnable)
                    if (pageVisible) {
                        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (hadError) return
                    lastGoodLoadMs = SystemClock.elapsedRealtime()
                    showError(false)
                }
            }
        }
    }

    companion object {
        // The dashboard refreshes its own stats client-side; a full reload is
        // only useful after long absence (network sleep, stale JS state).
        private const val STALE_RELOAD_MS = 30 * 60 * 1000L
        private const val RETRY_DELAY_MS = 30 * 1000L
    }
}
