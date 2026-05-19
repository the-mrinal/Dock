package com.ambient.tvclock.vpn

import java.security.SecureRandom

/**
 * Holds a one-shot, 6-digit numeric PIN with an expiry timestamp and a small
 * rate-limit. Used by [ConfigImportServer] to gate `POST /upload` so only the
 * user who can read the TV screen can push a config.
 */
class ConfigImportSession(
    val pin: String,
    val expiresAtMillis: Long,
) {
    private var failedAttempts = 0
    private var lockoutUntilMillis = 0L

    /** Returns true if [candidate] matches the PIN, in constant time, accounting for lockout/expiry. */
    @Synchronized
    fun verify(candidate: String): Verdict {
        val now = System.currentTimeMillis()
        if (now >= expiresAtMillis) return Verdict.EXPIRED
        if (now < lockoutUntilMillis) return Verdict.LOCKED_OUT
        if (constantTimeEquals(pin, candidate)) {
            failedAttempts = 0
            return Verdict.OK
        }
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockoutUntilMillis = now + LOCKOUT_MILLIS
            failedAttempts = 0
        }
        return Verdict.BAD_PIN
    }

    fun millisRemaining(): Long = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)

    enum class Verdict { OK, BAD_PIN, LOCKED_OUT, EXPIRED }

    companion object {
        private const val PIN_DIGITS = 6
        private const val TTL_MILLIS = 5L * 60 * 1000
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MILLIS = 60L * 1000

        fun generate(): ConfigImportSession {
            val rng = SecureRandom()
            val sb = StringBuilder(PIN_DIGITS)
            repeat(PIN_DIGITS) { sb.append(rng.nextInt(10)) }
            return ConfigImportSession(sb.toString(), System.currentTimeMillis() + TTL_MILLIS)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
