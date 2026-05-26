// JNI bridge for Apple's reference ALAC decoder.
//
// Fire TV firmware does not ship `audio/alac` in MediaCodec — `audio/alac`
// `createDecoderByType` fails with `IllegalArgumentException: Failed to
// initialize audio/alac, error 0xfffffffe`. iOS AirPlay senders (Apple Music,
// Spotify, Safari audio) all transmit ALAC frames on the `streams=[{type:96}]`
// channel, so without a software decoder these flows are silent (#14).
//
// This file wraps `ALACDecoder` (vendored under cpp/alac/, Apache 2.0) in a
// minimal JNI surface that mirrors the MediaCodec API just enough for
// AudioPlayer.kt to swap one for the other.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <android/log.h>
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"
#include "alac/ALACAudioTypes.h"

#define ALAC_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "AlacJni", __VA_ARGS__)
#define ALAC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AlacJni", __VA_ARGS__)

namespace {

// ALAC realtime audio over AirPlay is 16-bit stereo @ 44.1 kHz, 352 spf.
// The output buffer for one decode is at most:
//   numSamples * numChannels * bytesPerSample = 352 * 2 * 2 = 1408 bytes
// Round up to 4 KB to absorb the occasional larger frame the decoder may
// emit when the bitstream signals a non-default frame length.
constexpr size_t kPcmScratchBytes = 4096;

struct AlacContext {
    ALACDecoder decoder;
    uint8_t pcm[kPcmScratchBytes];
};

inline AlacContext * fromHandle(jlong handle) {
    return reinterpret_cast<AlacContext *>(static_cast<uintptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ambient_tvclock_receiver_airplay_AlacSoftwareDecoder_nativeInit(
        JNIEnv * env,
        jclass /* clazz */,
        jbyteArray magicCookie) {
    if (magicCookie == nullptr) return 0;
    jsize cookieLen = env->GetArrayLength(magicCookie);
    jbyte * cookieBytes = env->GetByteArrayElements(magicCookie, nullptr);
    if (cookieBytes == nullptr) return 0;

    AlacContext * ctx = new AlacContext();
    int32_t err = ctx->decoder.Init(cookieBytes, static_cast<uint32_t>(cookieLen));
    env->ReleaseByteArrayElements(magicCookie, cookieBytes, JNI_ABORT);

    if (err != 0) {
        // -108 (kALAC_MemFullError) on Init usually means the magic cookie
        // bytes were parsed with the wrong endian and frameLength ended up
        // as a multi-GB allocation; -50 (kALAC_ParamError) usually means a
        // truncated cookie. Log the raw error code so future regressions
        // are easy to triage from logcat alone.
        ALAC_LOGE("nativeInit: ALACDecoder::Init returned %d (cookieLen=%d)",
                  err, cookieLen);
        delete ctx;
        return 0;
    }
    ALAC_LOGI("nativeInit: ok frameLen=%u bitDepth=%u channels=%u sampleRate=%u",
              ctx->decoder.mConfig.frameLength,
              ctx->decoder.mConfig.bitDepth,
              ctx->decoder.mConfig.numChannels,
              ctx->decoder.mConfig.sampleRate);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

// Decodes one ALAC frame.
//
//   handle      — opaque pointer from nativeInit
//   input       — encrypted-then-AES-CBC-decrypted ALAC frame bytes (one packet)
//   numSamples  — samples-per-frame from the magic cookie (352 for AirPlay realtime)
//   numChannels — channel count (2 for AirPlay stereo)
//   pcmOut      — caller-allocated output array; must be at least
//                 numSamples * numChannels * 2 bytes for 16-bit PCM
//
// Returns the number of PCM bytes written, or 0 on decode error.
JNIEXPORT jint JNICALL
Java_com_ambient_tvclock_receiver_airplay_AlacSoftwareDecoder_nativeDecode(
        JNIEnv * env,
        jclass /* clazz */,
        jlong handle,
        jbyteArray input,
        jint numSamples,
        jint numChannels,
        jbyteArray pcmOut) {
    AlacContext * ctx = fromHandle(handle);
    if (ctx == nullptr || input == nullptr || pcmOut == nullptr) return 0;

    jsize inputLen = env->GetArrayLength(input);
    jbyte * inputBytes = env->GetByteArrayElements(input, nullptr);
    if (inputBytes == nullptr) return 0;

    BitBuffer bits;
    BitBufferInit(&bits, reinterpret_cast<uint8_t *>(inputBytes),
                  static_cast<uint32_t>(inputLen));

    uint32_t outNumSamples = 0;
    int32_t err = ctx->decoder.Decode(
            &bits,
            ctx->pcm,
            static_cast<uint32_t>(numSamples),
            static_cast<uint32_t>(numChannels),
            &outNumSamples);

    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);

    if (err != 0 || outNumSamples == 0) return 0;

    // ALAC for AirPlay is fixed at 16-bit; bitDepth is in the magic cookie
    // and parsed into mConfig. Bytes per sample = bitDepth/8 = 2.
    uint32_t bytesPerSample = (ctx->decoder.mConfig.bitDepth + 7) / 8;
    uint32_t outBytes = outNumSamples * numChannels * bytesPerSample;

    jsize outCap = env->GetArrayLength(pcmOut);
    if (outBytes > static_cast<uint32_t>(outCap) || outBytes > kPcmScratchBytes) {
        return 0;  // caller's buffer can't hold the frame
    }

    env->SetByteArrayRegion(pcmOut, 0, static_cast<jsize>(outBytes),
                            reinterpret_cast<jbyte *>(ctx->pcm));
    return static_cast<jint>(outBytes);
}

JNIEXPORT void JNICALL
Java_com_ambient_tvclock_receiver_airplay_AlacSoftwareDecoder_nativeRelease(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong handle) {
    AlacContext * ctx = fromHandle(handle);
    if (ctx != nullptr) delete ctx;
}

}  // extern "C"
