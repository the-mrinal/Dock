package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Handles Spotify OAuth redirect if the system delivers it outside the WebView.
 */
class SpotifyCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        handleUri(uri)
    }

    private fun handleUri(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val verifier = SpotifyTokenStore.takeVerifier(this)
        if (code.isNullOrBlank() || verifier == null) {
            finish()
            return
        }
        thread {
            SpotifyApiClient.exchangeCode(this, code, verifier)
            runOnUiThread { finish() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { handleUri(it) }
    }
}
