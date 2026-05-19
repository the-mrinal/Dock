package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * AudioPlayerTest — Unit tests for [AudioPlayer].
 *
 * WHY: [AudioPlayer] handles both decryption and audio output. Bugs in its
 * lifecycle management or input validation can cause silent audio, crashes,
 * or resource leaks.
 *
 * WHAT WE TEST:
 * - Construction succeeds without Android APIs being called
 * - Calling [AudioPlayer.playAudioPacket] before [AudioPlayer.initialize] does not crash
 * - Calling [AudioPlayer.release] before [AudioPlayer.initialize] does not crash
 * - Calling [AudioPlayer.release] twice does not crash (double-release safety)
 * - [AudioPlayer.initialize] with a wrong-length AES key throws [IllegalArgumentException]
 * - [AudioPlayer.initialize] with a wrong-length AES IV throws [IllegalArgumentException]
 *
 * NOTE: [AudioPlayer.initialize] calls [android.media.AudioTrack.Builder] internally,
 * which requires real Android audio hardware and is therefore NOT testable in JVM unit
 * tests. Tests that require initialization call only the [require()] validation path
 * (which fires before any Android API) — passing a wrong-length key or IV guarantees
 * the exception is thrown before [AudioTrack] is touched.
 *
 * Full audio output testing is covered by instrumentation tests on real devices.
 * (see docs/TESTING.md for manual test scenarios)
 */
class AudioPlayerTest {

    private lateinit var audioPlayer: AudioPlayer

    @Before
    fun setup() {
        audioPlayer = AudioPlayer()
    }

    // ─── Construction ──────────────────────────────────────────────────────────

    @Test
    fun `AudioPlayer is created successfully`() {
        assertNotNull(audioPlayer)
    }

    // ─── Pre-initialize safety ─────────────────────────────────────────────────

    /**
     * Test: playAudioPacket() before initialize() does not crash.
     *
     * WHY: Race condition — if an audio UDP packet arrives before RECORD is fully
     * processed (and before AudioPlayer.initialize() is called), the app must not
     * crash. The packet should be silently discarded with a log warning.
     */
    @Test
    fun `playAudioPacket before initialize does not crash`() {
        val dummyPacket = ByteArray(20) { it.toByte() }
        // Should not throw any exception — returns early with a log warning
        audioPlayer.playAudioPacket(dummyPacket)
    }

    /**
     * Test: playAudioPacket() with an empty array before initialize() does not crash.
     *
     * WHY: RULE 4 — malformed / empty network input must never crash the app.
     */
    @Test
    fun `playAudioPacket with empty bytes before initialize does not crash`() {
        audioPlayer.playAudioPacket(ByteArray(0))
    }

    /**
     * Test: release() before initialize() does not crash.
     *
     * WHY: onDestroy() always calls release(). If the activity is destroyed before
     * AudioPlayer was ever initialized, release() must be a safe no-op.
     */
    @Test
    fun `release before initialize does not crash`() {
        audioPlayer.release()
    }

    /**
     * Test: release() called twice does not crash.
     *
     * WHY: Double-release is an easy mistake in error-handling paths.
     * The second call must be a safe no-op (idempotent).
     */
    @Test
    fun `release can be called twice safely`() {
        audioPlayer.release()
        audioPlayer.release()  // Should not throw
    }

    // ─── Input validation for initialize() ────────────────────────────────────

    /**
     * Test: initialize() with a 15-byte AES key (not 16) throws [IllegalArgumentException].
     *
     * WHY: AES-128 requires exactly 16 bytes. Allowing shorter keys would silently
     * use a weaker cipher or corrupt the stream. We reject this at the boundary.
     *
     * The require() check fires BEFORE any Android API is called, making this
     * test safe for JVM unit test execution.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `initialize with 15-byte key throws IllegalArgumentException`() {
        val shortKey = ByteArray(15)  // 15 bytes — one too few for AES-128
        val validIv  = ByteArray(16)
        audioPlayer.initialize(shortKey, validIv, sampleRate = 44100, channels = 2)
    }

    /**
     * Test: initialize() with an 8-byte AES IV (not 16) throws [IllegalArgumentException].
     *
     * WHY: The IV must be exactly 16 bytes (128 bits) to match the AES block size.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `initialize with 8-byte IV throws IllegalArgumentException`() {
        val validKey = ByteArray(16)
        val shortIv  = ByteArray(8)   // 8 bytes — not a valid AES-128 IV
        audioPlayer.initialize(validKey, shortIv, sampleRate = 44100, channels = 2)
    }

    /**
     * Test: initialize() with a 17-byte AES key (too long) throws [IllegalArgumentException].
     *
     * WHY: AES-128 key is exactly 16 bytes — we don't silently truncate.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `initialize with 17-byte key throws IllegalArgumentException`() {
        val longKey = ByteArray(17)   // 17 bytes — too long for AES-128
        val validIv = ByteArray(16)
        audioPlayer.initialize(longKey, validIv, sampleRate = 44100, channels = 2)
    }

    /**
     * Test: initialize() with zero-length key throws [IllegalArgumentException].
     *
     * WHY: An empty key is meaningless and should never reach the cipher.
     * The require() check catches this before any crypto code runs.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `initialize with empty key throws IllegalArgumentException`() {
        audioPlayer.initialize(
            aesKey     = ByteArray(0),
            aesIv      = ByteArray(16),
            sampleRate = 44100,
            channels   = 2
        )
    }
}
