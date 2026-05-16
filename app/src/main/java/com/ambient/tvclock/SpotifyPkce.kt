package com.ambient.tvclock

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object SpotifyPkce {

    private const val VERIFIER_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun generateVerifier(): String {
        val random = SecureRandom()
        val length = 64
        return buildString(length) {
            repeat(length) {
                append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)])
            }
        }
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
