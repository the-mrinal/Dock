package com.ambient.tvclock.receiver.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.ambient.tvclock.util.Logger
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioPlayer — Decrypts, decodes, and plays the AirPlay mirror audio stream.
 *
 * AirPlay 2 mirror audio arrives as RTP-framed UDP packets on port 6000:
 *
 * 1. **Receive** (handled by `AirPlayReceiver.startMirrorAudioReceiver`):
 *    raw UDP datagram → byte array passed to [playAudioPacket].
 * 2. **Filter no-data markers**: Apple sends 16-byte keep-alives whose payload
 *    is the literal `00 68 34 00`; UxPlay drops them in `raop_buffer.c` because
 *    they are not audio. We do the same — playing them produces noise.
 * 3. **Deduplicate**: each AAC-ELD frame is sent three times by Apple in the
 *    pattern `0 0 1 0 1 2 1 2 3 …` (resilience-via-redundancy). We dedup by
 *    the 16-bit RTP sequence number to feed each frame to the decoder exactly
 *    once. Empirically confirmed on Mac AirPlay 2 mirror via hex-dump.
 * 4. **Decrypt (AES-CBC)**: only the first `(len/16)*16` bytes of the payload
 *    are encrypted; any trailing `len % 16` bytes are plaintext. UxPlay
 *    resets the CBC cipher between packets — we mirror that by reinitialising
 *    the [Cipher] for each packet with the original SETUP `ekey`/`eiv`.
 * 5. **Decode (MediaCodec)**: the decrypted bytes are one codec frame. We
 *    dispatch by the codec advertised in SETUP (or inferred from SDP for the
 *    legacy AirPlay 1 path):
 *      - AAC-ELD (`ct=8`): hardware AAC decoder configured with the
 *        AudioSpecificConfig derived from sample rate + channels (44.1 kHz
 *        stereo → `F8 E8 50 00`, matching Apple's SDP `config=`). Mac mirror.
 *      - ALAC    (`ct=2`): hardware `audio/alac` decoder configured with the
 *        36-byte ALAC magic cookie. iPhone Apple Music / Spotify / Safari
 *        audio (#14).
 * 6. **Output (AudioTrack)**: drained PCM goes straight to the system audio
 *    output.
 *
 * PCM (`ct=1`) and AAC-LC (`ct=4`) are not yet implemented — they fall
 * through to AAC-ELD with a warning. PCM is rare on AirPlay; AAC-LC has not
 * been observed from any tested sender.
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null

    // Cached SETUP keys + a single Cipher instance we re-init per packet. UxPlay
    // calls `aes_cbc_reset(...)` after every `aes_cbc_decrypt` — we mirror that,
    // but avoid the per-packet `Cipher.getInstance(...)` JCA provider lookup
    // (≈100µs each — at ~90 packets/sec that's enough to nibble into latency on
    // the Fire TV's low-end CPU and was a measurable cause of the audio hiccup).
    private var aesKeySpec: SecretKeySpec? = null
    private var aesIvSpec: IvParameterSpec? = null
    private var cbcCipher: Cipher? = null

    // Sample rate (Hz). RTP audio timestamps tick at this rate, and the decoder
    // outputs PCM at this rate; both are needed for ts→µs conversion.
    private var sampleRate: Int = 44100

    // Hardware audio decoder for the AAC-ELD path. Null when not initialised
    // or when MediaCodec failed to start (the receiver then silently drops
    // audio rather than pumping garbage bytes into AudioTrack).
    private var aacDecoder: MediaCodec? = null
    private val codecBufferInfo = MediaCodec.BufferInfo()

    // Software ALAC decoder (Apple reference codec via JNI). Used on devices
    // whose firmware lacks `audio/alac` in MediaCodec — most notably Fire TV
    // AFTKA, which has no ALAC entry in /vendor/etc/media_codecs*.xml.
    // Per-frame PCM is small (1408 bytes for 352-sample stereo 16-bit) and
    // gets written straight to AudioTrack without queuing.
    private var alacDecoder: AlacSoftwareDecoder? = null
    // Decode scratch buffer — sized to match the JNI side's internal limit
    // so we never short-write. Reused across packets to avoid per-frame GC.
    private val alacPcmScratch = ByteArray(4096)
    // Frame parameters from the magic cookie (352 spf stereo for AirPlay
    // realtime ALAC). Cached at init so the hot path doesn't re-decode them.
    private var alacFrameSamples: Int = 352
    private var alacChannels: Int = 2

    private val seqnumWindow = SeqnumDedup(DEDUP_WINDOW_SIZE)
    private var firstPcmLogged = false
    private var firstDecryptedFrameLogged = false

    // Codec the decoder was configured for. Drives the "what does the first
    // byte of a decrypted frame mean?" diagnostic log so we can spot mismatches
    // between the SETUP-advertised codec and what iOS actually sends.
    private var audioCodec: AudioCodec = AudioCodec.AAC_ELD

    // Diagnostics: count packets in / PCM frames out / total bytes written
    // to AudioTrack, with periodic logs so we can see whether the pipeline
    // actually flows or stalls. "Decoder produced first PCM frame" alone
    // doesn't tell us if frame 2+ ever followed.
    @Volatile private var packetsReceived: Long = 0
    @Volatile private var pcmFramesDrained: Long = 0
    // Peak |sample| of the most recently decoded PCM frame. Surfaced in the
    // periodic diag log so we can tell whether subsequent frames remain
    // silent (peak=0 throughout = decoded silence) or carry actual audio.
    // Without this we only know the first frame's peak — useless for
    // diagnosing senders that go silent mid-session.
    @Volatile private var lastFramePeak: Int = 0
    @Volatile private var bytesWrittenOk: Long = 0
    @Volatile private var writeFailures: Long = 0
    @Volatile private var lastDiagLogPacketCount: Long = 0

    @Volatile
    private var isInitialized = false

    /**
     * Initialises the AudioPlayer for a mirror session.
     *
     * @param aesKey     16-byte AES-128 key from the SETUP `ekey`, or null if unencrypted.
     * @param aesIv      16-byte IV from the SETUP `eiv`, or null if unencrypted.
     * @param sampleRate Audio sample rate in Hz (44100 for Mac AirPlay 2 mirror).
     * @param channels   Channel count (2 = stereo for Mac AirPlay 2 mirror).
     * @param audioCodec Decoder selection. AAC_ELD is the legacy Mac mirror codec
     *                   and the default for older senders. ALAC (ct=2) is what
     *                   Apple Music / Spotify / Safari audio send via the
     *                   `streams=[{type:96}]` path — without this branch we
     *                   silently emit zero-amplitude PCM (see issue #14).
     */
    fun initialize(
        aesKey: ByteArray?,
        aesIv: ByteArray?,
        sampleRate: Int,
        channels: Int,
        audioCodec: AudioCodec = AudioCodec.AAC_ELD
    ) {
        if (isInitialized) {
            Logger.w("AudioPlayer.initialize() called twice — ignoring")
            return
        }

        if (aesKey != null || aesIv != null) {
            require(aesKey != null && aesKey.size == AES_KEY_LENGTH_BYTES) {
                "AES key must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesKey?.size}"
            }
            require(aesIv != null && aesIv.size == AES_KEY_LENGTH_BYTES) {
                "AES IV must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesIv?.size}"
            }
            aesKeySpec = SecretKeySpec(aesKey, "AES")
            aesIvSpec = IvParameterSpec(aesIv)
            cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")
        }

        this.sampleRate = sampleRate
        this.audioCodec = audioCodec
        initializeDecoder(audioCodec, sampleRate, channels)
        initializeAudioTrack(sampleRate, channels)
        isInitialized = true
        Logger.i("AudioPlayer initialized (${sampleRate}Hz, $channels ch, $audioCodec via AES-CBC)")
    }

    /**
     * Filters, dedups, decrypts, decodes, and plays one mirror-audio UDP packet.
     *
     * Safe to call from any thread — MediaCodec and AudioTrack APIs are
     * thread-safe for this single-producer use. The receive loop in
     * [AirPlayReceiver] calls this for every UDP datagram.
     */
    fun playAudioPacket(rtpPacket: ByteArray) {
        if (!isInitialized) return

        packetsReceived++
        if (packetsReceived - lastDiagLogPacketCount >= DIAG_LOG_EVERY) {
            lastDiagLogPacketCount = packetsReceived
            val state = when (audioTrack?.playState) {
                AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
                AudioTrack.PLAYSTATE_PAUSED  -> "PAUSED"
                AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
                null -> "null"
                else -> "?"
            }
            Logger.i("Audio diag: pkts=$packetsReceived pcm=$pcmFramesDrained " +
                     "wroteOK=${bytesWrittenOk}B writeFails=$writeFailures track=$state " +
                     "lastPeak=$lastFramePeak/32767")
        }

        try {
            // Step 2 — drop "no-data" markers.
            if (isNoDataMarker(rtpPacket)) return

            // Need at least 1 byte of payload after the 12-byte RTP header.
            if (rtpPacket.size <= RTP_HEADER_BYTES) return

            // Step 3 — dedup. Apple sends every AAC frame three times.
            val seqnum = rtpSeqnum(rtpPacket)
            if (!seqnumWindow.recordIfNew(seqnum)) return

            // Step 4 — AES-CBC decrypt the payload (key/iv from SETUP).
            val encrypted = rtpPacket.copyOfRange(RTP_HEADER_BYTES, rtpPacket.size)
            val decrypted = decryptCbc(encrypted)

            // Diagnostic: log the first decrypted frame's leading bytes so we can
            // tell which codec iOS is *actually* sending vs. what SETUP advertised.
            // Per UxPlay renderers/audio_renderer.c:325-371:
            //   ALAC    (ct=2) → byte0 = 0x20
            //   AAC-LC  (ct=4) → byte0 = 0xff (ADTS sync)
            //   AAC-ELD (ct=8) → byte0 ∈ {0x80,0x81,0x82,0x8c,0x8d,0x8e}
            // If `codec=$audioCodec` and `byte0` disagree, audio is silent because
            // the decoder treats foreign bytes as a syntactically-valid but
            // semantically-empty frame.
            if (!firstDecryptedFrameLogged && decrypted.isNotEmpty()) {
                val head = decrypted.take(8).joinToString("") { "%02x".format(it) }
                val byte0 = decrypted[0].toInt() and 0xFF
                Logger.i("First decrypted audio frame: codec=$audioCodec " +
                         "byte0=0x%02x len=%d head=%s".format(byte0, decrypted.size, head))
                firstDecryptedFrameLogged = true
            }

            // Step 5/6 — decode + write PCM to AudioTrack. ALAC takes the
            // synchronous software-decoder path (no PTS needed since the
            // decoder isn't queue-backed); AAC-ELD keeps its MediaCodec
            // feed/drain pair with RTP-derived presentation timestamps.
            if (audioCodec == AudioCodec.ALAC) {
                decodeAndPlayAlac(decrypted)
            } else {
                val codec = aacDecoder ?: return
                val rtpTs = rtpTimestamp(rtpPacket)
                val ptsUs = rtpTs * 1_000_000L / sampleRate
                try {
                    feedDecoder(codec, decrypted, ptsUs)
                    drainDecoder(codec)
                } catch (e: IllegalStateException) {
                    // mediaserver crashed (often when the video decoder dies first);
                    // tear down so we go silent instead of looping the same error
                    // per packet for the rest of the session.
                    Logger.e("Audio decoder ($audioCodec) entered error state — disabling for this session", e)
                    try { codec.release() } catch (_: Exception) { /* non-fatal */ }
                    aacDecoder = null
                }
            }
        } catch (e: Exception) {
            Logger.e("Error playing audio packet", e)
        }
    }

    /** Releases all audio resources. Call when streaming ends. */
    fun release() {
        Logger.d("Releasing AudioPlayer")
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing AudioTrack (non-fatal)", e)
        }
        try {
            aacDecoder?.stop()
            aacDecoder?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing AAC decoder (non-fatal)", e)
        }
        try {
            alacDecoder?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing ALAC decoder (non-fatal)", e)
        }
        audioTrack = null
        aacDecoder = null
        alacDecoder = null
        aesKeySpec = null
        aesIvSpec = null
        cbcCipher = null
        firstPcmLogged = false
        firstDecryptedFrameLogged = false
        seqnumWindow.clear()
        isInitialized = false
    }

    /**
     * Decrypts `payload` with AES-128-CBC per UxPlay `raop_buffer.c`:
     * only the first `(len/16)*16` bytes are encrypted; any remainder is
     * carried through verbatim. The cipher is re-initialised for every packet,
     * matching UxPlay's `aes_cbc_reset(...)` after each `aes_cbc_decrypt`.
     */
    internal fun decryptCbc(payload: ByteArray): ByteArray {
        val cipher = cbcCipher ?: return payload
        val keySpec = aesKeySpec ?: return payload
        val ivSpec = aesIvSpec ?: return payload
        val out = ByteArray(payload.size)
        val encryptedLen = (payload.size / AES_BLOCK_BYTES) * AES_BLOCK_BYTES
        if (encryptedLen > 0) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(payload, 0, encryptedLen)
            System.arraycopy(decrypted, 0, out, 0, encryptedLen)
        }
        if (encryptedLen < payload.size) {
            System.arraycopy(payload, encryptedLen, out, encryptedLen, payload.size - encryptedLen)
        }
        return out
    }

    /**
     * Routes decoder setup by codec. On failure we log and leave [aacDecoder]
     * null, which causes [playAudioPacket] to silently drop frames instead of
     * crashing the session.
     *
     * UNKNOWN is treated as AAC-ELD — that's the historical default the
     * receiver shipped with, and senders that don't advertise a codec almost
     * always mean Mac mirror AAC-ELD.
     */
    private fun initializeDecoder(audioCodec: AudioCodec, sampleRate: Int, channels: Int) {
        when (audioCodec) {
            AudioCodec.ALAC -> initializeAlacDecoder(sampleRate, channels)
            AudioCodec.AAC_ELD, AudioCodec.UNKNOWN -> initializeAacEldDecoder(sampleRate, channels)
        }
    }

    private fun initializeAacEldDecoder(sampleRate: Int, channels: Int) {
        try {
            val asc = buildAacEldAsc(sampleRate, channels)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                // Explicitly declare AAC-ELD (object type 39). The default
                // `createDecoderByType(AAC)` picks a decoder that supports
                // generic AAC; on some Fire TV firmware that decoder reads
                // the input as AAC-LC frames (1024 samples) and emits clean
                // silence when the bitstream doesn't make sense — exactly
                // the peak=0 PCM we observed in logcat.
                setInteger(MediaFormat.KEY_AAC_PROFILE, 39)  // AACObjectELD
            }
            // Resolve the actual codec the system would pick for this format so
            // we can see which one is running (vendor / google / c2.android.*).
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val codecName = try { codec.name } catch (e: Exception) { "?" }
            codec.configure(format, null, null, 0)
            codec.start()
            aacDecoder = codec
            Logger.i("AAC-ELD decoder started: codec='$codecName' ASC=${asc.toHex()}")
        } catch (e: Exception) {
            Logger.e("Failed to start AAC-ELD decoder — audio will be silent for this session", e)
            aacDecoder = null
        }
    }

    /**
     * Starts the software ALAC decoder for AirPlay 2 type-96 ALAC streams
     * (Apple Music, Spotify, Safari audio — see issue #14).
     *
     * Fire TV AFTKA's firmware does not ship `audio/alac` in MediaCodec
     * (verified: no entry in /vendor/etc/media_codecs*.xml). We use Apple's
     * reference ALAC decoder (vendored under cpp/alac/, Apache 2.0) via the
     * thin JNI wrapper in [AlacSoftwareDecoder].
     *
     * Frame layout uses the magic cookie's defaults for AirPlay realtime:
     * frameLength=352 spf, bitDepth=16, channels=2, sampleRate=44100. Every
     * canonical AirPlay receiver (UxPlay, shairport-sync, openairplay) uses
     * the same values — they're driven by iOS, not negotiated.
     *
     * On any failure (JNI library missing, cookie malformed, decoder Init
     * returning non-zero) we log and leave [alacDecoder] null. Audio then
     * degrades to silent rather than crashing the session.
     */
    private fun initializeAlacDecoder(sampleRate: Int, channels: Int) {
        try {
            val cookie = buildAlacMagicCookie(sampleRate, channels)
            val decoder = AlacSoftwareDecoder()
            if (!decoder.init(cookie)) {
                Logger.e("ALAC software decoder Init returned failure — audio will be silent")
                alacDecoder = null
                return
            }
            alacDecoder = decoder
            // AirPlay realtime ALAC is always 352 spf stereo; cache locally
            // so the per-packet decode doesn't re-derive them.
            alacFrameSamples = 352
            alacChannels = channels
            Logger.i("ALAC software decoder started (Apple reference, cookie=${cookie.toHex()})")
        } catch (e: Throwable) {
            // UnsatisfiedLinkError or anything else from the JNI layer falls
            // here so we never crash the AirPlay session on a decoder fault.
            Logger.e("Failed to start ALAC software decoder — Apple Music/Spotify audio will be silent.", e)
            alacDecoder = null
        }
    }

    /**
     * Decodes one ALAC packet via the Apple reference decoder and writes
     * the resulting 16-bit interleaved PCM straight to AudioTrack.
     *
     * The native side is synchronous — one call in, one PCM frame out — so
     * there's no buffer-queue plumbing to mirror. The decoder reads frame
     * length, channel count and bit depth from the magic cookie given at
     * [initializeAlacDecoder] time; we just hand it the bytes.
     *
     * On any decode error we drop the frame (audio briefly stalls) rather
     * than disabling the decoder, because AirPlay senders happily recover
     * from missing frames via re-send and the alternative — silence-for-
     * the-rest-of-the-session — is worse.
     */
    private fun decodeAndPlayAlac(payload: ByteArray) {
        val decoder = alacDecoder ?: return
        val track = audioTrack ?: return
        val pcmBytes = decoder.decode(payload, alacFrameSamples, alacChannels, alacPcmScratch)
        if (pcmBytes <= 0) return

        // Sample peak |amplitude| over this frame on every call. Cheap (~700
        // samples for 352-spf stereo) and lets the periodic diag log report
        // whether recent frames are silent or carry audio — critical for
        // diagnosing iOS senders that go silent mid-session (issue #17).
        lastFramePeak = computePeak16BitLe(alacPcmScratch, pcmBytes)
        if (!firstPcmLogged) {
            Logger.i("ALAC decoder produced first PCM frame " +
                     "($pcmBytes bytes, peak=$lastFramePeak / 32767)")
            firstPcmLogged = true
        }

        pcmFramesDrained++
        val wrote = track.write(alacPcmScratch, 0, pcmBytes, AudioTrack.WRITE_NON_BLOCKING)
        if (wrote > 0) {
            bytesWrittenOk += wrote
        } else {
            writeFailures++
            if (writeFailures <= 5 || writeFailures % 100 == 0L) {
                Logger.w("AudioTrack.write returned $wrote (ALAC, failure #$writeFailures)")
            }
        }
    }

    private fun feedDecoder(codec: MediaCodec, payload: ByteArray, ptsUs: Long) {
        val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)
        if (inputBufferIndex < 0) {
            Logger.v("AudioPlayer: no input buffer available, dropping AAC frame")
            return
        }
        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(payload)
        codec.queueInputBuffer(inputBufferIndex, 0, payload.size, ptsUs, 0)
    }

    private fun drainDecoder(codec: MediaCodec) {
        val track = audioTrack ?: return
        var outIndex = codec.dequeueOutputBuffer(codecBufferInfo, 0)
        while (outIndex >= 0) {
            if (codecBufferInfo.size > 0) {
                val outputBuffer = codec.getOutputBuffer(outIndex)
                if (outputBuffer != null) {
                    val pcm = ByteArray(codecBufferInfo.size)
                    outputBuffer.position(codecBufferInfo.offset)
                    outputBuffer.limit(codecBufferInfo.offset + codecBufferInfo.size)
                    outputBuffer.get(pcm, 0, codecBufferInfo.size)
                    // Track peak on every frame; surfaced via the periodic
                    // diag log so we can tell whether subsequent frames stay
                    // silent — the same probe used on the ALAC path.
                    lastFramePeak = computePeak16BitLe(pcm, pcm.size)
                    if (!firstPcmLogged) {
                        Logger.i("AAC-ELD decoder produced first PCM frame " +
                                 "(${pcm.size} bytes, peak=$lastFramePeak / 32767)")
                        firstPcmLogged = true
                    }
                    pcmFramesDrained++
                    val wrote = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (wrote > 0) {
                        bytesWrittenOk += wrote
                    } else {
                        writeFailures++
                        if (writeFailures <= 5 || writeFailures % 100 == 0L) {
                            // WRITE_NON_BLOCKING returns 0 when the AudioTrack
                            // buffer is full (back-pressure) or a negative
                            // AudioTrack error code (e.g. -3 STATE_UNINITIALIZED).
                            // First few + every 100 keeps the log short while
                            // still surfacing patterns.
                            Logger.w("AudioTrack.write returned $wrote (failure #$writeFailures)")
                        }
                    }
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
            outIndex = codec.dequeueOutputBuffer(codecBufferInfo, 0)
        }
    }

    private fun initializeAudioTrack(sampleRate: Int, channels: Int) {
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Logger.w("Unsupported channel count: $channels — defaulting to stereo")
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Buffer = ~500ms of audio. Fire TV's min buffer is small (~25-50ms);
        // a 1-2 frame stall in the AAC decode pipeline (or a brief mediaserver
        // hiccup) shows up as audible drop-outs at that depth. 500ms is enough
        // slack to absorb burst variation from MediaCodec without adding
        // noticeable lip-sync drift — AudioTrack still drains in real time so
        // the buffer fills only on bursts, not steady-state.
        val bytesPerSecond = sampleRate * channels * 2  // 16-bit PCM
        val desiredBufferSize = bytesPerSecond / 2       // 500 ms
        val bufferSize = maxOf(minBufferSize * 2, desiredBufferSize)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack!!.play()
        Logger.d("AudioTrack initialized: ${sampleRate}Hz, $channels ch, buffer=$bufferSize bytes")
    }

    companion object {
        private const val AES_KEY_LENGTH_BYTES = 16
        private const val AES_BLOCK_BYTES = 16
        private const val RTP_HEADER_BYTES = 12
        private const val INPUT_BUFFER_TIMEOUT_US = 10_000L
        private const val DEDUP_WINDOW_SIZE = 32
        /** How often to emit the audio diagnostics line — every 50 packets ≈ once per ~550ms at 90 pkt/s. */
        private const val DIAG_LOG_EVERY = 50L

        // ISO/IEC 14496-3 §1.6.3.4 sample rate index table.
        private val MPEG4_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )

        // Apple's empty-frame keep-alive payload (UxPlay raop_buffer.c).
        private val NO_DATA_MARKER = byteArrayOf(0x00, 0x68, 0x34, 0x00)

        /**
         * Returns the peak absolute 16-bit sample value in a little-endian
         * PCM buffer up to [validBytes]. Used by both ALAC and AAC-ELD paths
         * to surface whether decoded frames carry audio or silence.
         */
        private fun computePeak16BitLe(buffer: ByteArray, validBytes: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < validBytes) {
                val sample = ((buffer[i + 1].toInt() shl 8) or
                              (buffer[i].toInt() and 0xFF)).toShort().toInt()
                val abs = if (sample < 0) -sample else sample
                if (abs > peak) peak = abs
                i += 2
            }
            return peak
        }

        /**
         * Returns the 16-bit RTP sequence number from bytes [2..3] of an RTP packet.
         * Assumes the packet is at least 4 bytes long — callers must check.
         */
        internal fun rtpSeqnum(packet: ByteArray): Int =
            ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

        /**
         * Returns the 32-bit RTP timestamp from bytes [4..7] of an RTP packet.
         * For AirPlay 2 mirror audio, this ticks at the audio sample rate
         * (44.1 kHz on Mac), advancing by [frame size] = 480 between AAC-ELD frames.
         * Assumes the packet is at least 8 bytes long — callers must check.
         */
        internal fun rtpTimestamp(packet: ByteArray): Long =
            ((packet[4].toLong() and 0xFF) shl 24) or
                ((packet[5].toLong() and 0xFF) shl 16) or
                ((packet[6].toLong() and 0xFF) shl 8) or
                (packet[7].toLong() and 0xFF)

        /**
         * AirPlay sends an empty 16-byte packet (12 RTP header + 4 marker bytes
         * `00 68 34 00`) as a keep-alive/sync. UxPlay also accepts length=12 as
         * a no-data marker (header alone). Both must be filtered before decode.
         */
        internal fun isNoDataMarker(packet: ByteArray): Boolean {
            if (packet.size == RTP_HEADER_BYTES) return true
            if (packet.size != RTP_HEADER_BYTES + NO_DATA_MARKER.size) return false
            for (i in NO_DATA_MARKER.indices) {
                if (packet[RTP_HEADER_BYTES + i] != NO_DATA_MARKER[i]) return false
            }
            return true
        }

        /**
         * Builds the 36-byte ALAC magic cookie (csd-0) for AirPlay realtime ALAC.
         *
         * Layout (Apple ALAC bitstream spec / ISO BMFF 'alac' atom):
         *   bytes 0–3   atom size (00 00 00 24 = 36)
         *   bytes 4–7   atom type ('alac')
         *   bytes 8–11  atom version+flags (00 00 00 00)
         *   bytes 12–15 frameLength (uint32 BE) — samples per frame = 352
         *   byte 16     compatibleVersion = 0
         *   byte 17     bitDepth = 16
         *   byte 18     pb (rice history multiplier) = 40 / 0x28
         *   byte 19     mb (initial history) = 10 / 0x0a
         *   byte 20     kb (kmodifier) = 14 / 0x0e
         *   byte 21     numChannels = 2
         *   bytes 22–23 maxRun = 255 (0x00ff)
         *   bytes 24–27 maxFrameBytes = 0 (decoder ignores)
         *   bytes 28–31 avgBitRate = 0 (decoder ignores)
         *   bytes 32–35 sampleRate (uint32 BE) = 44100 (0x0000ac44)
         *
         * For 44100 Hz / 16-bit / stereo this matches the UxPlay byte sequence
         * `00 00 00 24 61 6c 61 63 00 00 00 00 00 00 01 60 00 10 28 0a 0e 02
         *  00 ff 00 00 00 00 00 00 00 00 00 00 ac 44`.
         */
        internal fun buildAlacMagicCookie(sampleRate: Int, channels: Int): ByteArray {
            val cookie = ByteArray(36)
            // atom size = 36
            cookie[0] = 0x00; cookie[1] = 0x00; cookie[2] = 0x00; cookie[3] = 0x24
            // 'alac'
            cookie[4] = 'a'.code.toByte(); cookie[5] = 'l'.code.toByte()
            cookie[6] = 'a'.code.toByte(); cookie[7] = 'c'.code.toByte()
            // version+flags = 0
            // frameLength = 352 = 0x00000160
            cookie[12] = 0x00; cookie[13] = 0x00; cookie[14] = 0x01; cookie[15] = 0x60
            cookie[16] = 0x00                   // compatibleVersion
            cookie[17] = 0x10                   // bitDepth = 16
            cookie[18] = 0x28                   // pb
            cookie[19] = 0x0a                   // mb
            cookie[20] = 0x0e                   // kb
            cookie[21] = (channels and 0xFF).toByte()
            cookie[22] = 0x00; cookie[23] = 0xFF.toByte()  // maxRun
            // maxFrameBytes (24-27) and avgBitRate (28-31) left zero
            // sampleRate big-endian
            cookie[32] = ((sampleRate ushr 24) and 0xFF).toByte()
            cookie[33] = ((sampleRate ushr 16) and 0xFF).toByte()
            cookie[34] = ((sampleRate ushr 8) and 0xFF).toByte()
            cookie[35] = (sampleRate and 0xFF).toByte()
            return cookie
        }

        /**
         * Builds the MPEG-4 AudioSpecificConfig for ER AAC ELD with the given
         * stream parameters — what MediaCodec consumes as CSD-0.
         *
         * Layout (ISO/IEC 14496-3):
         *   5-bit AOT escape = 31
         *   6-bit (AOT - 32) = 7 → 39 = ER AAC ELD
         *   4-bit samplingFrequencyIndex
         *   4-bit channelConfiguration
         *   1-bit frameLengthFlag = 1 (480 samples per frame, AirPlay's choice)
         *   4× 1-bit resilience flags = 0
         *   1-bit ldSbrPresentFlag = 0
         *   4-bit eldExtType = 0 (ELDEXT_TERM)
         *
         * 44.1 kHz stereo → `F8 E8 50 00`, matching Apple's SDP `config=`.
         */
        internal fun buildAacEldAsc(sampleRate: Int, channels: Int): ByteArray {
            val srIndex = MPEG4_SAMPLE_RATES.indexOf(sampleRate).let { idx ->
                if (idx < 0) MPEG4_SAMPLE_RATES.indexOf(44100) else idx
            }
            val w = BitWriter()
            w.write(31, 5)         // AOT escape
            w.write(7, 6)          // 39 - 32 → ER AAC ELD
            w.write(srIndex, 4)
            w.write(channels, 4)
            w.write(1, 1)          // frameLengthFlag = 1 (480 samples)
            w.write(0, 4)          // four resilience flags
            w.write(0, 1)          // ldSbrPresentFlag
            w.write(0, 4)          // eldExtType = ELDEXT_TERM
            return w.toByteArray()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    }

    /**
     * MSB-first bit writer used by [buildAacEldAsc]. Zero-pads to byte boundary.
     */
    internal class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var bitsFilled = 0

        fun write(value: Int, bits: Int) {
            var remaining = bits
            while (remaining > 0) {
                val take = minOf(8 - bitsFilled, remaining)
                val chunk = (value ushr (remaining - take)) and ((1 shl take) - 1)
                current = (current shl take) or chunk
                bitsFilled += take
                remaining -= take
                if (bitsFilled == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    bitsFilled = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitsFilled > 0) {
                bytes.add((current shl (8 - bitsFilled)).toByte())
                current = 0
                bitsFilled = 0
            }
            return bytes.toByteArray()
        }
    }

    /**
     * Sliding-window RTP sequence-number deduplicator. Each AAC-ELD frame is
     * sent three times in Apple's `0 0 1 0 1 2 …` pattern; we feed each
     * frame to MediaCodec exactly once by remembering recently-seen seqnums.
     *
     * 16-bit RTP seqnum wraps every 65 536 packets (~12 minutes at 92 fps);
     * the [windowSize] only needs to be large enough to cover the triple-send
     * dispersion (≤ ~6 packets) — 32 is a comfortable safety margin.
     */
    internal class SeqnumDedup(private val windowSize: Int) {
        private val recent = LinkedHashSet<Int>()

        /** Adds [seq] to the window if new; returns true if this seqnum is fresh. */
        fun recordIfNew(seq: Int): Boolean {
            if (!recent.add(seq)) return false
            if (recent.size > windowSize) {
                val it = recent.iterator()
                it.next()
                it.remove()
            }
            return true
        }

        fun clear() { recent.clear() }
    }
}
