package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SpotifyTokenStore {

    private const val PREFS = "spotify_tokens"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_VERIFIER = "pkce_verifier"
    private const val KEY_USER_ID = "user_id"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresInSec: Int) {
        val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L - 60_000L
        prefs(context).edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, expiresAt)
            .remove(KEY_VERIFIER)
            .apply()
    }

    fun saveVerifier(context: Context, verifier: String) {
        prefs(context).edit().putString(KEY_VERIFIER, verifier).apply()
    }

    fun takeVerifier(context: Context): String? {
        val prefs = prefs(context)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        if (verifier != null) {
            prefs.edit().remove(KEY_VERIFIER).apply()
        }
        return verifier
    }

    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH, null)

    fun isConnected(context: Context): Boolean =
        getRefreshToken(context) != null || getAccessToken(context) != null

    fun isAccessTokenValid(context: Context): Boolean {
        val token = getAccessToken(context) ?: return false
        if (token.isBlank()) return false
        return System.currentTimeMillis() < prefs(context).getLong(KEY_EXPIRES, 0L)
    }

    fun getUserId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }

    fun saveUserId(context: Context, userId: String) {
        prefs(context).edit().putString(KEY_USER_ID, userId).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
