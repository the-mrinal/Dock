package com.ambient.tvclock

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Full-screen WebView that shows the ad-block / home-lab dashboard. Launched from
 * the Ad Block page's "Open dashboard" button with the dashboard URL as an extra,
 * so it works whether or not the separate Home Lab page is enabled.
 *
 * Unlike [HomeLabScreenBinder] (which is non-focusable because it lives inside the
 * dashboard pager), this standalone WebView is focusable so the remote's D-pad can
 * drive the dashboard's own on-page navigation. BACK finishes the activity.
 */
class DashboardWebActivity : Activity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        if (url.isBlank()) {
            root.addView(centeredMessage(getString(R.string.adblock_status_unavailable)))
            setContentView(root)
            return
        }

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
        }
        webView = web
        root.addView(
            web,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setContentView(root)
        web.loadUrl(url)
        web.requestFocus()
    }

    private fun centeredMessage(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            val pad = (resources.displayMetrics.density * 32).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.CENTER }
        }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.let { web ->
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.ambient.tvclock.extra.DASHBOARD_URL"
    }
}
