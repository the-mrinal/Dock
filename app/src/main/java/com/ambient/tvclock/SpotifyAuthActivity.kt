package com.ambient.tvclock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlin.concurrent.thread

class SpotifyAuthActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loading: View

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SpotifyApiClient.hasClientId()) {
            Toast.makeText(this, R.string.spotify_no_client_id, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContentView(R.layout.activity_spotify_auth)
        webView = findViewById(R.id.spotifyWebView)
        loading = findViewById(R.id.spotifyAuthLoading)

        val verifier = SpotifyPkce.generateVerifier()
        SpotifyTokenStore.saveVerifier(this, verifier)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleRedirect(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && handleRedirect(url)) {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, url)
            }
        }
        webView.loadUrl(SpotifyApiClient.buildAuthorizeUrl(verifier))
    }

    private fun handleRedirect(url: String): Boolean {
        if (!url.startsWith("com.ambient.tvclock://spotify-callback")) {
            return false
        }
        val uri = Uri.parse(url)
        val error = uri.getQueryParameter("error")
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            return true
        }
        val verifier = SpotifyTokenStore.takeVerifier(this)
        if (verifier == null) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        showLoading(true)
        thread {
            val ok = try {
                SpotifyApiClient.exchangeCode(this, code, verifier)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.spotify_connect_failed, Toast.LENGTH_LONG).show()
                }
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }
        return true
    }

    private fun showLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
        webView.visibility = if (show) View.GONE else View.VISIBLE
    }

    companion object {
        fun intent(activity: Activity): Intent =
            Intent(activity, SpotifyAuthActivity::class.java)
    }
}
