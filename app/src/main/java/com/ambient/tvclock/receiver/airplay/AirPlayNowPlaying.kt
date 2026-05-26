package com.ambient.tvclock.receiver.airplay

/**
 * State surfaced to the UI for AirPlay audio sessions.
 *
 * iOS sends three pieces of information over the RTSP control channel via
 * `SET_PARAMETER` while audio is playing:
 *
 *  - **Track metadata** — `Content-Type: application/x-dmap-tagged`, a binary
 *    DAAP (Digital Audio Access Protocol) blob carrying `minm` (title),
 *    `asar` (artist), `asal` (album) and friends. See [AirPlayDaapParser].
 *  - **Album artwork** — `Content-Type: image/jpeg`, raw JPEG bytes.
 *  - **Playback progress** — `Content-Type: text/parameters`, a `progress:`
 *    line carrying three RTP timestamps that tick at the audio sample rate
 *    (`progress: rtpStart/rtpCurrent/rtpEnd`). See [parseProgress].
 *
 * The receiver merges those three streams into a single immutable snapshot
 * the UI can render. Any field can be null when iOS hasn't sent it yet —
 * the activity should render placeholders rather than wait for everything
 * to arrive before showing the screen.
 *
 * Held by [com.ambient.tvclock.receiver.ReceiverStateBus.airPlayNowPlaying];
 * cleared when the session ends.
 */
data class AirPlayNowPlayingState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Raw JPEG bytes. UI decodes into a Bitmap on demand. */
    val artworkJpeg: ByteArray? = null,
    val progress: AirPlayProgress? = null
) {
    fun withMetadata(meta: AirPlayNowPlayingMetadata): AirPlayNowPlayingState = copy(
        title = meta.title ?: title,
        artist = meta.artist ?: artist,
        album = meta.album ?: album
    )

    // ByteArray equals/hashCode override — `data class` generates `==` which
    // would compare ByteArray references rather than contents, so two
    // identical artwork payloads would look distinct and re-render. The
    // StateFlow's setter uses this `equals` to dedupe — if it recurses (as a
    // previous draft did via a self-referencing extension) every publish
    // blows the stack and silently drops updates.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AirPlayNowPlayingState) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artworkJpeg.contentEquals(other.artworkJpeg) &&
            progress == other.progress
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artworkJpeg?.contentHashCode() ?: 0)
        result = 31 * result + (progress?.hashCode() ?: 0)
        return result
    }
}

/** Metadata-only subset — what [AirPlayDaapParser.parse] returns. */
data class AirPlayNowPlayingMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
)

/**
 * Playback progress from the iOS sender. RTP timestamps tick at [sampleRate]
 * (44.1 kHz for AirPlay realtime audio); [updatedAtMs] is captured at
 * `SystemClock.elapsedRealtime()` so the UI can extrapolate the current
 * position between updates without waiting for the next progress message.
 */
data class AirPlayProgress(
    val rtpStart: Long,
    val rtpCurrent: Long,
    val rtpEnd: Long,
    val sampleRate: Int,
    val updatedAtMs: Long
) {
    /** Total track duration in seconds. */
    fun totalSeconds(): Double {
        if (sampleRate <= 0) return 0.0
        return (rtpEnd - rtpStart).toDouble() / sampleRate
    }

    /**
     * Current playback position in seconds, extrapolated from the last
     * `progress:` update via wall-clock delta. Clamped to the track length
     * so the bar can never overshoot when an update is late.
     */
    fun elapsedSeconds(nowMs: Long): Double {
        if (sampleRate <= 0) return 0.0
        val baseElapsed = (rtpCurrent - rtpStart).toDouble() / sampleRate
        val sinceUpdate = (nowMs - updatedAtMs).coerceAtLeast(0) / 1000.0
        val total = totalSeconds()
        return (baseElapsed + sinceUpdate).coerceIn(0.0, total)
    }
}

/**
 * Parses iOS's `application/x-dmap-tagged` SET_PARAMETER body.
 *
 * DAAP wire format (Apple iTunes/AirPlay metadata, see
 * https://github.com/owntone/owntone-server `src/dmap.c` for the
 * authoritative tag table):
 *
 *   record = 4-byte ASCII tag + 4-byte uint32 BE length + `length` bytes
 *
 * The receiver only needs a handful of tags:
 *
 * | Tag    | Meaning                              | Type      |
 * |--------|--------------------------------------|-----------|
 * | `mlit` | list item — container for one track  | container |
 * | `minm` | track title                          | string    |
 * | `asar` | artist                               | string    |
 * | `asal` | album                                | string    |
 *
 * Unknown tags are skipped. Containers are parsed recursively so we don't
 * depend on whether a particular sender wraps its tags in `mlit`. Malformed
 * blobs (truncated length, negative length, overflowing payload) abort the
 * parse at the offending tag and return whatever was collected before it —
 * silent partial success is better than crashing the audio session over a
 * one-off bad packet.
 */
object AirPlayDaapParser {

    fun parse(body: ByteArray): AirPlayNowPlayingMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        var i = 0
        while (i + 8 <= body.size) {
            val tag = String(body, i, 4, Charsets.US_ASCII)
            val len = ((body[i + 4].toInt() and 0xFF) shl 24) or
                      ((body[i + 5].toInt() and 0xFF) shl 16) or
                      ((body[i + 6].toInt() and 0xFF) shl 8) or
                      (body[i + 7].toInt() and 0xFF)
            i += 8
            // Length can't be negative (would wrap on the parse above) or
            // overflow the buffer. Either condition means we lost framing —
            // bail rather than read garbage as the next tag.
            if (len < 0 || i + len > body.size) break

            when (tag) {
                "minm" -> title  = decodeString(body, i, len) ?: title
                "asar" -> artist = decodeString(body, i, len) ?: artist
                "asal" -> album  = decodeString(body, i, len) ?: album
                "mlit" -> {
                    // List-item container — recurse into nested tags so we
                    // pick up `minm/asar/asal` whether the sender wraps the
                    // record or sends fields at the top level.
                    val nested = parse(body.copyOfRange(i, i + len))
                    title  = nested.title  ?: title
                    artist = nested.artist ?: artist
                    album  = nested.album  ?: album
                }
                // Any other tag is ignored.
            }
            i += len
        }

        return AirPlayNowPlayingMetadata(title, artist, album)
    }

    private fun decodeString(body: ByteArray, offset: Int, len: Int): String? {
        if (len == 0) return null
        return try {
            String(body, offset, len, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Parses `text/parameters` SET_PARAMETER bodies. Today we only care about
 * the `progress:` key, which iOS emits every ~1s with three RTP timestamps:
 *
 *   `progress: 1234567/2345678/3456789\r\n`
 *
 * The values are RTP ticks at the audio sample rate (44.1 kHz). When no
 * `progress` line is present (e.g. the sender is announcing volume), the
 * function returns null and the caller leaves the existing progress
 * snapshot alone — partial updates must not wipe useful state.
 *
 * @param sampleRate audio sample rate from SETUP (used to convert ticks
 *                   into seconds); 44100 for realtime ALAC, varies for
 *                   buffered AirPlay 2.
 * @param nowMs      `SystemClock.elapsedRealtime()` at parse time; stored
 *                   so the UI can extrapolate position between updates.
 */
fun parseAirPlayProgress(body: String, sampleRate: Int, nowMs: Long): AirPlayProgress? {
    for (line in body.split('\n', '\r')) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("progress:", ignoreCase = true)) continue
        val value = trimmed.substringAfter(':').trim()
        val parts = value.split('/')
        if (parts.size != 3) return null
        val a = parts[0].trim().toLongOrNull() ?: return null
        val b = parts[1].trim().toLongOrNull() ?: return null
        val c = parts[2].trim().toLongOrNull() ?: return null
        return AirPlayProgress(a, b, c, sampleRate, nowMs)
    }
    return null
}
