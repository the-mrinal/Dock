package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger

/**
 * Software ALAC decoder backed by Apple's reference codec (vendored under
 * `cpp/alac/`, APSL 2.0 / Apache 2.0). Used by [AudioPlayer] on devices
 * whose firmware does not expose `audio/alac` in MediaCodec — most notably
 * Fire TV AFTKA, which only ships AAC/MP3/Vorbis/Opus/FLAC/AC3.
 *
 * The instance is single-frame, single-threaded — the audio UDP receive loop
 * calls [decode] for every packet, allocates the output buffer once, and
 * reuses it across calls. Encryption (AES-CBC of the first `(len/16)*16`
 * bytes) is handled by [AudioPlayer] before bytes reach this class.
 *
 * One frame for AirPlay realtime ALAC is 352 samples × 2 channels × 2 bytes
 * = 1408 bytes of PCM. The decoder reads its frame-length, bit-depth, and
 * channel count from the magic cookie passed to [init], so we don't need to
 * thread those down separately.
 */
class AlacSoftwareDecoder {

    @Volatile
    private var nativeHandle: Long = 0L

    /**
     * Returns true if the decoder is ready to consume frames.
     *
     * Either [init] hasn't been called yet, or it returned a null handle —
     * usually because the magic cookie was malformed or the native library
     * failed to load. AudioPlayer treats this the same way it treats a null
     * MediaCodec: drop the frame, no crash, log on first failure.
     */
    val isReady: Boolean get() = nativeHandle != 0L

    /**
     * Configures the decoder with the 36-byte ALAC magic cookie (see
     * [AudioPlayer.buildAlacMagicCookie]). Subsequent calls before
     * [release] are ignored.
     */
    fun init(magicCookie: ByteArray): Boolean {
        if (nativeHandle != 0L) {
            Logger.w("AlacSoftwareDecoder.init called twice — ignoring")
            return true
        }
        nativeHandle = nativeInit(magicCookie)
        if (nativeHandle == 0L) {
            Logger.e("AlacSoftwareDecoder: native Init failed (cookie len=${magicCookie.size})")
            return false
        }
        return true
    }

    /**
     * Decodes one ALAC frame into 16-bit interleaved PCM.
     *
     * @param input        one decrypted ALAC frame (the payload after the
     *                     12-byte RTP header)
     * @param numSamples   samples-per-frame from the magic cookie (352 for
     *                     AirPlay realtime)
     * @param numChannels  channel count (2 for AirPlay stereo)
     * @param pcmOut       caller-allocated scratch buffer; must be at least
     *                     `numSamples * numChannels * 2` bytes
     * @return number of PCM bytes written, or 0 on decode error
     */
    fun decode(input: ByteArray, numSamples: Int, numChannels: Int, pcmOut: ByteArray): Int {
        val handle = nativeHandle
        if (handle == 0L) return 0
        return nativeDecode(handle, input, numSamples, numChannels, pcmOut)
    }

    fun release() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            try {
                nativeRelease(handle)
            } catch (e: Throwable) {
                Logger.w("AlacSoftwareDecoder: nativeRelease threw (non-fatal): $e")
            }
        }
    }

    companion object {
        init {
            // Same .so that hosts the playfair entry points; one shared lib
            // keeps loader churn down and avoids a second JNI_OnLoad path.
            System.loadLibrary("receiver_playfair")
        }

        @JvmStatic
        private external fun nativeInit(magicCookie: ByteArray): Long

        @JvmStatic
        private external fun nativeDecode(
            handle: Long,
            input: ByteArray,
            numSamples: Int,
            numChannels: Int,
            pcmOut: ByteArray
        ): Int

        @JvmStatic
        private external fun nativeRelease(handle: Long)
    }
}
