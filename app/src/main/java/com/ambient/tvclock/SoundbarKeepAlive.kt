package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.ambient.tvclock.receiver.ReceiverStateBus
import com.ambient.tvclock.util.Logger
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Keeps an HDMI soundbar from auto-standbying while the dashboard idles.
 *
 * Many soundbars power down after a few minutes without an audio signal, then
 * take seconds to wake (or need the remote) when playback finally starts. When
 * nothing is playing, this component periodically plays a short burst of a
 * 40 Hz sine at -50 dBFS — inaudible at normal listening volume, but enough
 * signal for the soundbar to reset its silent-input timer.
 *
 * Deliberately never requests audio focus: if real playback starts while a
 * burst is running, the -50 dBFS tone simply mixes inaudibly underneath it
 * until the burst's finite loop count runs out.
 *
 * Owned by MainActivity (start/stop from onStart/onStop, same as the pollers)
 * and additionally stopped while a mirroring session owns the screen.
 */
class SoundbarKeepAlive(
    context: Context,
    private val isSomethingPlaying: (Context) -> Boolean = ::defaultPlaybackCheck,
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var burstTrack: AudioTrack? = null

    private val loopBuffer: ShortArray by lazy {
        KeepAliveTone.generateLoopBuffer(SAMPLE_RATE, TONE_HZ, TONE_DB_FS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isSomethingPlaying(appContext)) {
                startBurst()
            }
            handler.postDelayed(this, SoundbarPreferences.getKeepAliveIntervalMs(appContext))
        }
    }

    // Releases the track shortly after its finite loop count runs out. No
    // playback polling during the burst: isMusicActive would see our own tone,
    // and a real track starting mid-burst just mixes over an inaudible signal.
    private val burstReleaseRunnable = Runnable { stopBurst() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SoundbarPreferences.KEY_KEEPALIVE_ENABLED ||
            key == SoundbarPreferences.KEY_KEEPALIVE_INTERVAL_MS
        ) {
            reschedule()
        }
    }

    /** Safe to call repeatedly; resets the tick schedule. No-ops when disabled. */
    fun start() {
        if (!started) {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .registerOnSharedPreferenceChangeListener(prefsListener)
            started = true
        }
        reschedule()
    }

    fun stop() {
        if (started) {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
            started = false
        }
        handler.removeCallbacks(tickRunnable)
        stopBurst()
    }

    private fun reschedule() {
        handler.removeCallbacks(tickRunnable)
        if (!SoundbarPreferences.isKeepAliveEnabled(appContext)) {
            stopBurst()
            return
        }
        handler.postDelayed(tickRunnable, SoundbarPreferences.getKeepAliveIntervalMs(appContext))
    }

    private fun startBurst() {
        if (burstTrack != null) return
        val buffer = loopBuffer
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buffer.size * 2)
                .build()
        } catch (e: Exception) {
            Logger.w("Soundbar keep-alive: AudioTrack creation failed: ${e.message}")
            return
        }

        val written = track.write(buffer, 0, buffer.size)
        if (written != buffer.size || track.state != AudioTrack.STATE_INITIALIZED) {
            Logger.w("Soundbar keep-alive: static buffer write failed (written=$written, state=${track.state})")
            track.release()
            return
        }
        // Loop one 40 Hz period for the burst duration; the finite loop count
        // means the track goes silent on its own even if the guard tick stalls.
        track.setLoopPoints(0, buffer.size, KeepAliveTone.loopCount(BURST_DURATION_MS, TONE_HZ))
        track.play()

        burstTrack = track
        handler.postDelayed(burstReleaseRunnable, BURST_DURATION_MS + BURST_RELEASE_SLACK_MS)
        Logger.d("Soundbar keep-alive: burst started (${BURST_DURATION_MS / 1000}s of $TONE_HZ Hz at $TONE_DB_FS dBFS)")
    }

    private fun stopBurst() {
        handler.removeCallbacks(burstReleaseRunnable)
        burstTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // Track was never started or already released — release below regardless.
            }
            track.release()
        }
        burstTrack = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val TONE_HZ = 40
        const val TONE_DB_FS = -50.0
        const val BURST_DURATION_MS = 25_000L
        const val BURST_RELEASE_SLACK_MS = 500L

        /**
         * True when audio is actually reaching the soundbar already.
         *
         * Deliberately does NOT consult media sessions: Spotify's TV app is
         * known to leave a zombie session in STATE_PLAYING (frozen position,
         * no audio) after a Connect handoff ends, which would make a
         * session-based check skip forever — the exact silence that puts the
         * soundbar into standby. [AudioManager.isMusicActive] instead reports
         * whether the mixer is rendering STREAM_MUSIC right now, covers every
         * app (including sessionless ones), and needs no permission. The
         * receiver check covers mirroring sessions during the brief window
         * before their audio starts flowing.
         */
        fun defaultPlaybackCheck(context: Context): Boolean {
            if (ReceiverStateBus.activeConnection.value != null) return true
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audioManager.isMusicActive
        }
    }
}

/**
 * Pure tone math for [SoundbarKeepAlive], extracted for unit testing.
 *
 * 40 Hz divides 48 kHz exactly (1200 frames per period), so a single-period
 * buffer loops seamlessly: it starts and ends at a zero crossing, producing no
 * clicks and no phase drift however many times it repeats.
 */
object KeepAliveTone {

    /** Frames in one full sine period. Only exact divisors loop seamlessly. */
    fun periodFrames(sampleRate: Int, frequencyHz: Int): Int = sampleRate / frequencyHz

    /** One period of a sine at [dbFs] below full scale, PCM 16-bit mono. */
    fun generateLoopBuffer(sampleRate: Int, frequencyHz: Int, dbFs: Double): ShortArray {
        val frames = periodFrames(sampleRate, frequencyHz)
        val peak = Short.MAX_VALUE * 10.0.pow(dbFs / 20.0)
        return ShortArray(frames) { i ->
            (peak * sin(2.0 * PI * i / frames)).roundToInt().toShort()
        }
    }

    /** AudioTrack.setLoopPoints count: additional repeats after the first play. */
    fun loopCount(durationMs: Long, frequencyHz: Int): Int =
        (durationMs * frequencyHz / 1000L).toInt() - 1
}
