package com.ambient.tvclock.receiver.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.ambient.tvclock.util.Logger

/**
 * VideoDecoder — Hardware H.264 video decoder using Android's MediaCodec API.
 *
 * WHY: AirPlay screen mirroring sends video as a stream of H.264-encoded frames.
 * To display these frames on screen, we need to decode the H.264 bitstream.
 * MediaCodec is Android's API to the hardware video decoder (GPU), which is
 * much faster and more power-efficient than any software decoder.
 *
 * HOW: Initialize with a [Surface] (from StreamingScreen) and the codec parameters
 * from the SDP (received in the RTSP ANNOUNCE). Then call [decodeNalUnit] for each
 * video chunk received from the RTP stream. MediaCodec outputs decoded frames directly
 * to the Surface — no intermediate buffer copies.
 *
 * AirPlay video data flow:
 *   RTP packet → RtspHandler strips RTP header → NAL unit bytes → VideoDecoder.decodeNalUnit()
 *   → MediaCodec input buffer → GPU hardware decode → Surface (displayed on TV)
 *
 * Example:
 *   val decoder = VideoDecoder(surface)
 *   decoder.initialize(spsBytes, ppsBytes, width, height)  // call once with SDP params
 *   decoder.decodeNalUnit(nalUnitBytes)                    // call for each video chunk
 *   decoder.release()                                       // call when done
 */
class VideoDecoder(
    private val outputSurface: Surface,
    /**
     * Called whenever the source video dimensions become known or change.
     * Fires on the IO thread once after [initialize] (using SPS-parsed size)
     * and again from [releaseOutputBuffers] each time MediaCodec reports
     * `INFO_OUTPUT_FORMAT_CHANGED` (using the cropped output rect — the
     * authoritative size that survives mid-stream resolution swaps).
     */
    private val onVideoSize: ((width: Int, height: Int) -> Unit)? = null
) {

    // The underlying hardware decoder — null until initialize() is called
    private var mediaCodec: MediaCodec? = null

    // Track whether the decoder has been initialized (to prevent double-init)
    @Volatile
    private var isInitialized = false

    // Last (width, height) reported via onVideoSize. Suppresses duplicate
    // emissions when MediaCodec's INFO_OUTPUT_FORMAT_CHANGED fires repeatedly
    // with the same crop rect.
    private var lastReportedWidth = 0
    private var lastReportedHeight = 0

    /**
     * Initializes the MediaCodec decoder with the video stream parameters from the SDP.
     *
     * This must be called ONCE before any calls to [decodeNalUnit].
     * The parameters (SPS, PPS, width, height) come from the SDP body of the
     * RTSP ANNOUNCE message.
     *
     * What is SPS/PPS?
     *   H.264 requires two special "configuration" NAL units before the first frame:
     *   - SPS (Sequence Parameter Set): describes the video resolution, profile, level
     *   - PPS (Picture Parameter Set): describes encoding parameters for each frame
     *   MediaCodec needs these to configure the hardware decoder correctly.
     *
     * RULE 5: If initialization fails, the exception propagates to the caller
     * (RtspHandler) which will handle it gracefully (log + return to WAITING state).
     *
     * @param spsBytes  The SPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param ppsBytes  The PPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param width     Video width in pixels (from SDP)
     * @param height    Video height in pixels (from SDP)
     */
    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // Try to extract actual resolution from the SPS NAL unit.
        // If parsing fails (e.g., unsupported profile), fall back to the hint dimensions.
        val (actualWidth, actualHeight) = Companion.parseSpsResolution(spsBytes) ?: run {
            Logger.d("SPS parsing failed or skipped — using hint: ${width}x${height}")
            Pair(width, height)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x${actualHeight} " +
                 "(hint was ${width}x${height})")

        // Create the MediaFormat that describes the H.264 stream to the hardware decoder
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,  // AVC = Advanced Video Coding = H.264
            actualWidth,
            actualHeight
        ).apply {
            // Provide SPS and PPS so MediaCodec can configure the hardware decoder.
            // These are wrapped in ByteBuffers as required by the MediaCodec API.
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))  // SPS
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))  // PPS
        }

        // Create the hardware H.264 decoder.
        // "video/avc" is the MIME type for H.264. Android will pick the best
        // available hardware decoder for this format.
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        // Configure the decoder:
        // - format: what the input will look like (H.264, width, height, SPS/PPS)
        // - outputSurface: where decoded frames go (directly to screen — no intermediate copy)
        // - crypto: null (we handle decryption before this point, if needed)
        // - flags: 0 (0 = decoder mode; CONFIGURE_FLAG_ENCODE would be for encoding)
        mediaCodec!!.configure(format, outputSurface, null, 0)
        mediaCodec!!.start()

        isInitialized = true
        reportVideoSize(actualWidth, actualHeight)
        Logger.i("H.264 decoder initialized successfully")
    }

    /** Emits a video-size update through [onVideoSize] if the size actually changed. */
    private fun reportVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == lastReportedWidth && height == lastReportedHeight) return
        lastReportedWidth = width
        lastReportedHeight = height
        onVideoSize?.invoke(width, height)
    }

    /**
     * Decodes a single H.264 NAL unit and sends it to the display.
     *
     * A NAL unit (Network Abstraction Layer Unit) is the basic building block of H.264.
     * Each RTP packet from AirPlay contains one or more NAL units.
     * Some NAL units are full frames (IDR frames), others are partial updates.
     *
     * PERFORMANCE: This method runs on the IO coroutine dispatcher (network thread).
     * MediaCodec handles the actual decoding on its own internal thread.
     * The decoded frame appears on the Surface without any UI thread involvement.
     *
     * SECURITY: The caller (RtspHandler) is responsible for validating the byte array
     * length before passing it here.
     *
     * @param nalUnit The raw NAL unit bytes (without the RTP header).
     * @param presentationTimeUs Presentation timestamp in microseconds (for A/V sync).
     */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return
        }

        try {
            // Request an input buffer from MediaCodec.
            // timeout = 10ms: if no buffer is available (decoder is full), we wait briefly.
            // In a healthy system, buffers are always available; the timeout prevents hangs.
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)

            if (inputBufferIndex >= 0) {
                // We got an input buffer — fill it with the NAL unit bytes
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(nalUnit)

                // Tell MediaCodec: "input buffer [index] is filled with [size] bytes
                // of data with timestamp [presentationTimeUs] — please decode it"
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,                 // offset: start from beginning of buffer
                    nalUnit.size,      // size: how many bytes to decode
                    presentationTimeUs,
                    0                  // flags: 0 = normal frame (not end-of-stream)
                )
            } else {
                // No input buffer available — the decoder is catching up.
                // Drop this NAL unit to avoid building up backlog (prefer low latency).
                Logger.v("VideoDecoder: no input buffer available, dropping NAL unit")
            }

            // Release any output buffers that MediaCodec has finished decoding.
            // render=true means the frame goes to the Surface immediately.
            releaseOutputBuffers(codec)

        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
        }
    }

    /**
     * Releases any decoded output buffers back to MediaCodec and renders them to the Surface.
     *
     * MediaCodec works asynchronously: we put encoded data in input buffers,
     * and decoded frames appear in output buffers. We must release each output
     * buffer back to MediaCodec after rendering, or we'll run out of buffers.
     *
     * render=true: the frame is rendered to the Surface (displayed on TV).
     * render=false: the frame is discarded (used to flush without displaying).
     *
     * @param codec The active MediaCodec instance.
     */
    private fun releaseOutputBuffers(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (true) {
            if (outputBufferIndex >= 0) {
                // render=true: display this decoded frame on the Surface
                codec.releaseOutputBuffer(outputBufferIndex, true)
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // First buffer is preceded by a format-changed event that carries
                // the cropped output rectangle. iOS senders also re-emit this when
                // the user rotates their phone mid-mirror — propagating the new
                // dimensions is what lets the overlay re-letterbox correctly.
                handleOutputFormatChanged(codec.outputFormat)
            } else {
                // INFO_TRY_AGAIN_LATER or any other negative value → nothing more
                // to drain this iteration.
                break
            }
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    /**
     * Extracts the visible (cropped) dimensions from a MediaCodec output format
     * and forwards them to [onVideoSize]. The crop keys aren't documented in the
     * public API surface but are emitted by every Android H.264 decoder we care
     * about; we fall back to the uncropped width/height when they're absent.
     */
    private fun handleOutputFormatChanged(format: MediaFormat) {
        val cropLeft   = if (format.containsKey(KEY_CROP_LEFT))   format.getInteger(KEY_CROP_LEFT)   else 0
        val cropRight  = if (format.containsKey(KEY_CROP_RIGHT))  format.getInteger(KEY_CROP_RIGHT)  else -1
        val cropTop    = if (format.containsKey(KEY_CROP_TOP))    format.getInteger(KEY_CROP_TOP)    else 0
        val cropBottom = if (format.containsKey(KEY_CROP_BOTTOM)) format.getInteger(KEY_CROP_BOTTOM) else -1

        val codedW = if (format.containsKey(MediaFormat.KEY_WIDTH))  format.getInteger(MediaFormat.KEY_WIDTH)  else 0
        val codedH = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0

        val width = if (cropRight >= cropLeft) cropRight - cropLeft + 1 else codedW
        val height = if (cropBottom >= cropTop) cropBottom - cropTop + 1 else codedH
        Logger.i("Decoder output format changed: ${width}x${height} " +
                 "(coded=${codedW}x${codedH}, crop=[$cropLeft,$cropTop,$cropRight,$cropBottom])")
        reportVideoSize(width, height)
    }

    /**
     * Rebinds the decoder's output to a new [Surface] without re-creating the
     * codec. Required when [com.ambient.tvclock.receiver.ui.StreamingOverlay]'s
     * SurfaceView gets resized for aspect-ratio correction — that resize
     * destroys the original Surface and the codec would otherwise keep writing
     * into a dead buffer, which on iPhone manifests as corrupted AES-decrypted
     * NAL payloads downstream. A no-op if the codec hasn't been initialized.
     *
     * The new surface should be format/size-compatible with the original
     * configure() call; modern Android H.264 decoders tolerate buffer-size
     * changes via this path.
     */
    fun setOutputSurface(surface: Surface) {
        val codec = mediaCodec ?: return
        try {
            codec.setOutputSurface(surface)
            Logger.i("VideoDecoder: rebound MediaCodec onto new output surface")
        } catch (e: Exception) {
            Logger.e("VideoDecoder: setOutputSurface failed (non-fatal)", e)
        }
    }

    /**
     * Releases all MediaCodec resources.
     *
     * MUST be called when streaming ends (from AirPlayReceiver.onStreamingStopped()
     * or from onDestroy()). Failing to release MediaCodec causes:
     * - Memory leaks (codec buffers are GPU memory, a scarce resource)
     * - The hardware decoder being unavailable to other apps
     *
     * After release(), call initialize() again before using the decoder.
     */
    fun release() {
        Logger.d("Releasing VideoDecoder")
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            mediaCodec = null
            isInitialized = false
            lastReportedWidth = 0
            lastReportedHeight = 0
        }
    }

    companion object {
        // How long to wait for an input buffer before giving up (microseconds)
        // 10ms = 10,000µs. This is a short wait to keep latency low.
        private const val INPUT_BUFFER_TIMEOUT_US = 10_000L

        // MediaFormat crop-rect keys. Declared as constants in MediaFormat
        // starting on API 29 (KEY_CROP_RIGHT etc.) but the underlying string
        // keys are emitted by every Android H.264 decoder on the platforms
        // we ship to, so we use the string form to keep the minSdk path clean.
        private const val KEY_CROP_LEFT   = "crop-left"
        private const val KEY_CROP_RIGHT  = "crop-right"
        private const val KEY_CROP_TOP    = "crop-top"
        private const val KEY_CROP_BOTTOM = "crop-bottom"

        /**
         * Parses the H.264 SPS NAL unit to extract the actual video resolution.
         *
         * WHY: AirPlay SDP does not include explicit width/height fields.
         * The true resolution is encoded inside the SPS (Sequence Parameter Set) NAL unit
         * as `pic_width_in_mbs_minus1` and `pic_height_in_map_units_minus1`.
         * Each macroblock (MB) is 16×16 pixels.
         *
         * Formula:
         *   width  = (pic_width_in_mbs_minus1  + 1) × 16
         *   height = (pic_height_in_map_units_minus1 + 1) × 16
         *
         * Handles Baseline (66), Main (77), and High profile (100+) SPS formats.
         * Returns null (graceful fallback) if the SPS is too short, malformed, or uses
         * an unsupported bitstream feature.
         *
         * Exposed as `internal` for unit testing without an Android runtime.
         *
         * @param sps The raw SPS NAL unit bytes (first byte is the NAL type, 0x67).
         * @return (width, height) in pixels, or null if parsing fails.
         */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null
                val reader = SpsBitReader(sps, startOffset = 1)  // skip NAL type byte (0x67)

                val profileIdc = reader.readBits(8)
                reader.readBits(8)   // constraint flags + 2 reserved zeros
                reader.readBits(8)   // level_idc
                reader.readUe()      // seq_parameter_set_id

                // Default for Baseline/Main profiles per H.264 spec.
                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0

                // High-profile and variants have extra fields before the common fields.
                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)  // separate_colour_plane_flag
                    }
                    reader.readUe()     // bit_depth_luma_minus8
                    reader.readUe()     // bit_depth_chroma_minus8
                    reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                    if (reader.readBits(1) == 1) {  // seq_scaling_matrix_present_flag
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) {  // seq_scaling_list_present_flag[i]
                                reader.skipScalingList(if (it < 6) 16 else 64)
                            }
                        }
                    }
                }

                reader.readUe()   // log2_max_frame_num_minus4
                val picOrderCntType = reader.readUe()
                when (picOrderCntType) {
                    0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                    1 -> {
                        reader.readBits(1)  // delta_pic_order_always_zero_flag
                        reader.readSe()     // offset_for_non_ref_pic
                        reader.readSe()     // offset_for_top_to_bottom_field
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                }

                reader.readUe()     // max_num_ref_frames
                reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) {
                    reader.readBits(1)  // mb_adaptive_frame_field_flag
                }
                reader.readBits(1)  // direct_8x8_inference_flag

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {  // frame_cropping_flag
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

                val subWidthC = when (chromaArrayType) {
                    0 -> 1
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = when (chromaArrayType) {
                    1 -> 2
                    else -> 1
                }

                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
                return Pair(width, height)

            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        /**
         * Bitstream reader for H.264 NAL units.
         *
         * Reads bits MSB-first and implements unsigned/signed Exp-Golomb coding.
         * Exposed as `internal` for use in tests.
         *
         * @param data        The raw SPS byte array (including NAL type header).
         * @param startOffset Byte offset to start reading from (typically 1 to skip NAL type).
         */
        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos  = 7  // MSB first (bit 7 = most significant)

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) { bitPos = 7; bytePos++ }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            /** Reads an unsigned Exp-Golomb code ue(v). */
            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0
                       else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            /** Reads a signed Exp-Golomb code se(v). */
            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            /** Skips a scaling list of [size] entries (High Profile SPS §7.3.2.1.1.1). */
            fun skipScalingList(size: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(size) {
                    if (nextScale != 0) {
                        val deltaScale = readSe()
                        nextScale = (lastScale + deltaScale + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }
        }
    }
}
