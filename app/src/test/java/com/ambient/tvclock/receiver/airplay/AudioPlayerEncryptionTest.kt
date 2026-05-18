package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AudioPlayerEncryptionTest — Tests for S6-4: graceful handling of unencrypted streams.
 *
 * WHY: AirPlay senders may omit the AES key/IV when the stream is unencrypted.
 * Before S6-4, [AudioPlayer.initialize] required non-null 16-byte arrays and callers
 * passed ByteArray(16) (zero key) when keys were absent. A zero-key cipher
 * produces garbage audio — the correct fix is to skip cipher setup entirely.
 *
 * WHAT WE TEST:
 * - [SessionDescription.isAudioEncrypted] is false when key or IV is absent
 * - [SessionDescription.isAudioEncrypted] is false when key is wrong length
 * - [SessionDescription.isAudioEncrypted] is true only when both key and IV are 16 bytes
 * - [AudioPlayer.initialize] with (null, non-null IV) → [IllegalArgumentException]
 *   (one null + one non-null is not a valid combination — caller bug)
 * - [AudioPlayer.initialize] with (non-null key, null IV) → [IllegalArgumentException]
 * - [AudioPlayer] pre-init safety: playAudioPacket with null does not crash
 *
 * NOTE: [AudioPlayer.initialize] with (null, null) proceeds to [AudioTrack.Builder]
 * which requires Android hardware — this path is covered by instrumentation tests.
 * Here we only test the validation layer (cipher guard and length checks).
 */
class AudioPlayerEncryptionTest {

    private lateinit var audioPlayer: AudioPlayer

    @Before
    fun setup() {
        audioPlayer = AudioPlayer()
    }

    // ─── SessionDescription.isAudioEncrypted ─────────────────────────────────

    @Test
    fun `isAudioEncrypted is false when both key and IV are null`() {
        val session = SessionDescription(hasVideo = false, hasAudio = true)
        assertFalse(session.isAudioEncrypted)
    }

    @Test
    fun `isAudioEncrypted is false when key is null and IV is non-null`() {
        val session = SessionDescription(
            hasVideo = false, hasAudio = true,
            aesKey = null,
            aesIv  = ByteArray(16)
        )
        assertFalse(session.isAudioEncrypted)
    }

    @Test
    fun `isAudioEncrypted is false when key is non-null and IV is null`() {
        val session = SessionDescription(
            hasVideo = false, hasAudio = true,
            aesKey = ByteArray(16),
            aesIv  = null
        )
        assertFalse(session.isAudioEncrypted)
    }

    @Test
    fun `isAudioEncrypted is false when key is wrong length (8 bytes)`() {
        val session = SessionDescription(
            hasVideo = false, hasAudio = true,
            aesKey = ByteArray(8),
            aesIv  = ByteArray(16)
        )
        assertFalse(session.isAudioEncrypted)
    }

    @Test
    fun `isAudioEncrypted is false when IV is wrong length (8 bytes)`() {
        val session = SessionDescription(
            hasVideo = false, hasAudio = true,
            aesKey = ByteArray(16),
            aesIv  = ByteArray(8)
        )
        assertFalse(session.isAudioEncrypted)
    }

    @Test
    fun `isAudioEncrypted is true when both key and IV are exactly 16 bytes`() {
        val session = SessionDescription(
            hasVideo = false, hasAudio = true,
            aesKey = ByteArray(16) { (it + 1).toByte() },
            aesIv  = ByteArray(16) { (it + 17).toByte() }
        )
        assertTrue(session.isAudioEncrypted)
    }

    // ─── AudioPlayer.initialize partial-null guard ────────────────────────────
    //
    // Passing one null and one non-null is a caller bug — enforce it via require().
    // (Both null = unencrypted path; both non-null = encrypted path. Mix is invalid.)

    @Test(expected = IllegalArgumentException::class)
    fun `initialize with null key and non-null IV throws IllegalArgumentException`() {
        // One null + one non-null: the require block checks both together
        audioPlayer.initialize(
            aesKey     = null,
            aesIv      = ByteArray(16),
            sampleRate = 44100,
            channels   = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `initialize with non-null key and null IV throws IllegalArgumentException`() {
        audioPlayer.initialize(
            aesKey     = ByteArray(16),
            aesIv      = null,
            sampleRate = 44100,
            channels   = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `initialize with wrong-length non-null key still throws IllegalArgumentException`() {
        // 15-byte key is non-null but wrong length — should still be rejected
        audioPlayer.initialize(
            aesKey     = ByteArray(15),
            aesIv      = ByteArray(16),
            sampleRate = 44100,
            channels   = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `initialize with wrong-length non-null IV still throws IllegalArgumentException`() {
        audioPlayer.initialize(
            aesKey     = ByteArray(16),
            aesIv      = ByteArray(8),
            sampleRate = 44100,
            channels   = 2
        )
    }

    // ─── Pre-init safety ──────────────────────────────────────────────────────

    @Test
    fun `playAudioPacket before initialize does not crash (unencrypted path)`() {
        // Before initialize(), isInitialized = false → should silently discard
        val packet = ByteArray(100) { it.toByte() }
        audioPlayer.playAudioPacket(packet)
    }

    @Test
    fun `release before initialize does not crash (unencrypted setup)`() {
        audioPlayer.release()
    }
}
