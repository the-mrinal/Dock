package com.ambient.tvclock.receiver.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for the pure-Kotlin helpers that drive the AirPlay 2 mirror-audio path.
 *
 * These cover the bits that decide whether real Mac audio reaches MediaCodec:
 * the AudioSpecificConfig bytes, the RTP "no-data" marker filter, the RTP
 * sequence-number extraction, the triple-send dedup window, and the partial-block
 * AES-CBC decryption (UxPlay `raop_buffer.c` semantics).
 *
 * MediaCodec / AudioTrack themselves need real Android hardware and stay covered
 * by on-device manual testing (see docs/TESTING.md).
 */
class AudioPlayerHelpersTest {

    // ─── AudioSpecificConfig builder ────────────────────────────────────────

    @Test
    fun `ASC for 44_1 kHz stereo matches Apple's config=F8E85000`() {
        val asc = AudioPlayer.buildAacEldAsc(sampleRate = 44100, channels = 2)
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00), asc)
    }

    @Test
    fun `ASC for 48 kHz stereo encodes sample rate index 3`() {
        val asc = AudioPlayer.buildAacEldAsc(sampleRate = 48000, channels = 2)
        // 11111 000111 0011 0010 1 0000 0 0000 → grouped as
        // 11111000 11100110 01010000 00000000 = F8 E6 50 00
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x50, 0x00), asc)
    }

    @Test
    fun `ASC for 44_1 kHz mono encodes channel config 1`() {
        val asc = AudioPlayer.buildAacEldAsc(sampleRate = 44100, channels = 1)
        // 11111 000111 0100 0001 1 0000 0 0000 → F8 E8 30 00
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x30, 0x00), asc)
    }

    @Test
    fun `ASC for unknown sample rate falls back to 44_1 kHz`() {
        // 12345 isn't in the MPEG-4 table — must not emit an invalid index.
        // Falling back to 44.1k keeps the receiver alive (AirPlay's default).
        val asc = AudioPlayer.buildAacEldAsc(sampleRate = 12345, channels = 2)
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00), asc)
    }

    // ─── No-data marker filter ───────────────────────────────────────────────

    @Test
    fun `isNoDataMarker accepts header-only 12 byte packet`() {
        assertTrue(AudioPlayer.isNoDataMarker(ByteArray(12)))
    }

    @Test
    fun `isNoDataMarker accepts 16 byte packet with 00 68 34 00 payload`() {
        val pkt = ByteArray(16)
        pkt[12] = 0x00
        pkt[13] = 0x68
        pkt[14] = 0x34
        pkt[15] = 0x00
        assertTrue(AudioPlayer.isNoDataMarker(pkt))
    }

    @Test
    fun `isNoDataMarker rejects 16 byte packet with non-marker payload`() {
        val pkt = ByteArray(16)
        pkt[12] = 0x01  // not the marker
        pkt[13] = 0x68
        pkt[14] = 0x34
        pkt[15] = 0x00
        assertFalse(AudioPlayer.isNoDataMarker(pkt))
    }

    @Test
    fun `isNoDataMarker rejects a real AAC frame size`() {
        // A real Mac AirPlay 2 audio packet from our hex dump was 34 bytes total
        // (12 RTP + 22 payload). Must not be filtered.
        assertFalse(AudioPlayer.isNoDataMarker(ByteArray(34)))
    }

    // ─── RTP sequence number extraction ─────────────────────────────────────

    @Test
    fun `rtpSeqnum reads bytes 2 and 3 as big-endian unsigned`() {
        // Header from our hex dump: 80 60 49 CB BB6D34DE 00000000
        val header = byteArrayOf(
            0x80.toByte(), 0x60, 0x49, 0xCB.toByte(),
            0xBB.toByte(), 0x6D, 0x34, 0xDE.toByte(),
            0x00, 0x00, 0x00, 0x00
        )
        assertEquals(0x49CB, AudioPlayer.rtpSeqnum(header))
    }

    @Test
    fun `rtpSeqnum handles high bit set without sign extension`() {
        val header = byteArrayOf(
            0x80.toByte(), 0x60, 0xFF.toByte(), 0xFE.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0
        )
        assertEquals(0xFFFE, AudioPlayer.rtpSeqnum(header))
    }

    @Test
    fun `rtpTimestamp reads bytes 4-7 as big-endian unsigned 32-bit`() {
        // Same packet header as in our hex dump: timestamp = BB6D34DE.
        val header = byteArrayOf(
            0x80.toByte(), 0x60, 0x49, 0xCB.toByte(),
            0xBB.toByte(), 0x6D, 0x34, 0xDE.toByte(),
            0x00, 0x00, 0x00, 0x00
        )
        assertEquals(0xBB6D34DEL, AudioPlayer.rtpTimestamp(header))
    }

    @Test
    fun `rtpTimestamp does not sign-extend when high bit is set`() {
        val header = byteArrayOf(
            0x80.toByte(), 0x60, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0
        )
        assertEquals(0xFFFFFFFFL, AudioPlayer.rtpTimestamp(header))
    }

    // ─── Triple-send dedup ──────────────────────────────────────────────────

    @Test
    fun `SeqnumDedup accepts a fresh sequence number once`() {
        val dedup = AudioPlayer.SeqnumDedup(windowSize = 4)
        assertTrue(dedup.recordIfNew(100))
        assertFalse(dedup.recordIfNew(100))
        assertFalse(dedup.recordIfNew(100))
    }

    @Test
    fun `SeqnumDedup handles Apple triple-send pattern 0 0 1 0 1 2`() {
        // Pattern: 0 0 1 0 1 2 1 2 3 …  → after dedup must emit 0,1,2,3 in order.
        val dedup = AudioPlayer.SeqnumDedup(windowSize = 8)
        val seen = mutableListOf<Int>()
        val pattern = intArrayOf(0, 0, 1, 0, 1, 2, 1, 2, 3, 2, 3, 4)
        for (s in pattern) if (dedup.recordIfNew(s)) seen.add(s)
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
    }

    @Test
    fun `SeqnumDedup forgets oldest entry when window overflows`() {
        // Window of 2 → after seqnums 1, 2, 3 are recorded, seqnum 1 has been evicted
        // and a repeat is treated as new again. Tradeoff: a tiny window can let a
        // late-arriving duplicate sneak through; we use 32 in production.
        val dedup = AudioPlayer.SeqnumDedup(windowSize = 2)
        assertTrue(dedup.recordIfNew(1))
        assertTrue(dedup.recordIfNew(2))
        assertTrue(dedup.recordIfNew(3))
        assertTrue(dedup.recordIfNew(1))  // 1 was evicted by 3; now considered new
    }

    @Test
    fun `SeqnumDedup clear resets the window`() {
        val dedup = AudioPlayer.SeqnumDedup(windowSize = 8)
        dedup.recordIfNew(42)
        dedup.clear()
        assertTrue(dedup.recordIfNew(42))
    }

    // ─── AES-CBC partial-block decryption ───────────────────────────────────

    @Test
    fun `decryptCbc round-trips full blocks and copies tail verbatim`() {
        // 22 bytes — 16 encrypted + 6 verbatim, matching the Mac hex dump.
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv  = ByteArray(16) { (it + 17).toByte() }
        val plaintext = ByteArray(22) { it.toByte() }

        // Build an "encrypted" packet the way Apple does: encrypt the first
        // (len/16)*16 bytes with CBC; append the tail verbatim.
        val encryptedPart = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plaintext, 0, 16)
        }
        val onTheWire = ByteArray(22)
        System.arraycopy(encryptedPart, 0, onTheWire, 0, 16)
        System.arraycopy(plaintext, 16, onTheWire, 16, 6)

        val player = AudioPlayer()
        try {
            player.initialize(key, iv, sampleRate = 44100, channels = 2)
        } catch (_: Throwable) {
            // AudioTrack / MediaCodec aren't available in the JVM unit test; that's
            // fine — the cipher fields are populated before either is touched, so
            // decryptCbc still works for our purposes.
        }

        assertArrayEquals(plaintext, player.decryptCbc(onTheWire))
    }

    @Test
    fun `decryptCbc returns input verbatim when key is absent`() {
        // initialize() was never called → no key/iv → byte-for-byte passthrough.
        val player = AudioPlayer()
        val payload = ByteArray(20) { it.toByte() }
        assertArrayEquals(payload, player.decryptCbc(payload))
    }

    @Test
    fun `decryptCbc handles sub-block tail-only payload as verbatim copy`() {
        // 4-byte payload — UxPlay treats this as "no encrypted bytes, copy tail".
        // Confirmed by our diagnostic: the 4-byte 00 68 34 00 marker survives
        // CBC unchanged because (4/16)*16 = 0 encrypted bytes.
        val key = ByteArray(16) { 0x11 }
        val iv  = ByteArray(16) { 0x22 }
        val player = AudioPlayer()
        try {
            player.initialize(key, iv, sampleRate = 44100, channels = 2)
        } catch (_: Throwable) { /* see above */ }

        val payload = byteArrayOf(0x00, 0x68, 0x34, 0x00)
        assertArrayEquals(payload, player.decryptCbc(payload))
    }
}
