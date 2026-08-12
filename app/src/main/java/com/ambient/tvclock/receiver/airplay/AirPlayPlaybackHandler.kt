package com.ambient.tvclock.receiver.airplay

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.ambient.tvclock.util.Logger
import java.net.InetAddress

/**
 * AirPlayPlaybackHandler — Routes the AirPlay video URL HTTP endpoints.
 *
 * Endpoints (per openairplay spec §6.1):
 *   POST /play           — start playing a URL (HLS or progressive MP4)
 *   POST /stop           — stop playback
 *   POST /rate?value=N   — N=0 pauses, N=1 plays
 *   POST /scrub?position=S — seek to S seconds
 *   GET  /scrub          — text/parameters with duration + position
 *   GET  /playback-info  — XML plist with full state (poll target)
 *
 * iOS uses this protocol whenever the user taps the AirPlay icon next to a
 * specific video (YouTube, Photos, Safari, Netflix, etc.) — distinct from full
 * screen mirroring. When the receiver answers these correctly, iOS streams the
 * video URL directly to ExoPlayer at native quality instead of falling back to
 * a lossy mirror re-encode (or failing outright).
 *
 * This class is stateless w.r.t. the request stream — all playback state lives
 * in the injected [AirPlayVideoPlayer]. Parsing and response shaping live here
 * so they can be unit-tested without ExoPlayer on the classpath.
 */
class AirPlayPlaybackHandler(
    private val videoPlayer: AirPlayVideoPlayer,
    /**
     * Fires on the IO thread (the RTSP handler thread) just before
     * [AirPlayVideoPlayer.play] is dispatched to the main thread.
     *
     * WHY: ExoPlayer binds the Surface as soon as it's allocated, and a still-
     * running mirror [VideoDecoder] / [MirrorTcpReceiver] is also writing to
     * the same Surface. The two MediaCodec instances racing for the same
     * output surface produces a black/flashing frame buffer. [AirPlayReceiver]
     * uses this hook to synchronously release the mirror pipeline before
     * ExoPlayer ever sees the Surface.
     */
    private val onPlayRequested: (senderName: String, peer: InetAddress?, headers: Map<String, String>) -> Unit = { _, _, _ -> }
) {

    /**
     * Returns true if this handler claims [request]. The receiver dispatches on
     * this so we don't have to enumerate the routes in two places.
     */
    fun claims(request: RtspRequest): Boolean {
        val path = pathOf(request.uri)
        return path in CLAIMED_PATHS
    }

    fun handle(request: RtspRequest, peer: InetAddress? = null): RtspResponse {
        val path = pathOf(request.uri)
        Logger.i("AirPlay video: ${request.method} $path (uri=${request.uri})")
        return when {
            request.method == "POST" && path == "/play"       -> handlePlay(request, peer)
            request.method == "POST" && path == "/stop"       -> handleStop()
            request.method == "POST" && path == "/rate"       -> handleRate(request)
            request.method == "POST" && path == "/scrub"      -> handleScrub(request)
            request.method == "GET"  && path == "/scrub"      -> handleScrubGet()
            request.method == "GET"  && path == "/playback-info" -> handlePlaybackInfo()
            else -> RtspResponse(501, "Not Implemented")
        }
    }

    // ─── /play ───────────────────────────────────────────────────────────────

    private fun handlePlay(request: RtspRequest, peer: InetAddress?): RtspResponse {
        val parsed = parsePlayBody(request)
        if (parsed == null || parsed.contentLocation.isBlank()) {
            Logger.w("/play: missing Content-Location in body (size=${request.bodyBytes.size})")
            return RtspResponse(400, "Bad Request")
        }
        Logger.i("/play url='${parsed.contentLocation}' start=${parsed.startPosition}")
        SenderIdentity.logIdentityHeaders("/play", request.headers)
        onPlayRequested(SenderIdentity.label(request.headers, peer), peer, request.headers)
        videoPlayer.play(parsed.contentLocation, parsed.startPosition)
        return RtspResponse(200, "OK")
    }

    /**
     * Parses a POST /play body in either of the two formats iOS senders use:
     *
     *   text/parameters (older + most common):
     *     Content-Location: http://stream.example.com/movie.m3u8
     *     Start-Position: 0.0
     *
     *   application/x-apple-binary-plist (newer iOS):
     *     NSDictionary { Content-Location -> NSString, Start-Position -> NSNumber }
     *
     * The content-type header is unreliable in practice, so we sniff: a body
     * starting with the ASCII "bplist" magic is a binary plist; everything else
     * is text/parameters.
     */
    internal fun parsePlayBody(request: RtspRequest): PlayParams? {
        if (request.bodyBytes.isEmpty()) return null
        return if (isBinaryPlist(request.bodyBytes)) {
            parsePlayPlist(request.bodyBytes)
        } else {
            parsePlayTextParameters(request.body)
        }
    }

    private fun parsePlayPlist(body: ByteArray): PlayParams? {
        return try {
            val root = PropertyListParser.parse(body) as? NSDictionary ?: return null
            val url = (root.objectForKey("Content-Location") as? NSString)?.toString().orEmpty()
            val start = (root.objectForKey("Start-Position") as? NSNumber)?.doubleValue() ?: 0.0
            PlayParams(url, start)
        } catch (e: Exception) {
            Logger.e("/play: failed to parse binary plist body", e)
            null
        }
    }

    private fun parsePlayTextParameters(body: String): PlayParams? {
        var url = ""
        var start = 0.0
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // split only on the FIRST colon — URL values contain colons too
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "Content-Location" -> url = value
                "Start-Position"   -> start = value.toDoubleOrNull() ?: 0.0
            }
        }
        return if (url.isEmpty()) null else PlayParams(url, start)
    }

    // ─── /stop ───────────────────────────────────────────────────────────────

    private fun handleStop(): RtspResponse {
        videoPlayer.stop()
        return RtspResponse(200, "OK")
    }

    // ─── /rate?value= ────────────────────────────────────────────────────────

    private fun handleRate(request: RtspRequest): RtspResponse {
        val value = queryParam(request.uri, "value")?.toFloatOrNull()
        if (value == null) {
            Logger.w("/rate: missing or non-numeric value param (uri=${request.uri})")
            return RtspResponse(400, "Bad Request")
        }
        videoPlayer.setRate(value)
        return RtspResponse(200, "OK")
    }

    // ─── /scrub (POST seek) ──────────────────────────────────────────────────

    private fun handleScrub(request: RtspRequest): RtspResponse {
        val position = queryParam(request.uri, "position")?.toDoubleOrNull()
        if (position == null) {
            Logger.w("/scrub: missing or non-numeric position param (uri=${request.uri})")
            return RtspResponse(400, "Bad Request")
        }
        videoPlayer.scrub(position)
        return RtspResponse(200, "OK")
    }

    // ─── /scrub (GET query) ──────────────────────────────────────────────────

    private fun handleScrubGet(): RtspResponse {
        val durationSec = videoPlayer.durationMs / 1000.0
        val positionSec = videoPlayer.positionMs / 1000.0
        val body = "duration: ${"%.6f".format(durationSec)}\r\n" +
                   "position: ${"%.6f".format(positionSec)}\r\n"
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Content-Type" to "text/parameters"),
            bodyBytes = body.toByteArray(Charsets.UTF_8)
        )
    }

    // ─── /playback-info ──────────────────────────────────────────────────────

    private fun handlePlaybackInfo(): RtspResponse {
        val xml = buildPlaybackInfoPlist()
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Content-Type" to "text/x-apple-plist+xml"),
            bodyBytes = xml.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Builds the XML plist iOS expects from /playback-info. iOS polls this every
     * 0.5–1.0s during playback to drive its progress UI, so the response must
     * report duration / position / rate accurately or the on-iPhone scrubber
     * snaps back to 0 and the user thinks playback froze.
     *
     * `loadedTimeRanges` and `seekableTimeRanges` are arrays of `{start, duration}`
     * pairs (both floats in seconds). We report a single seekable range covering
     * [0, duration] and a single loaded range covering [0, bufferedPosition].
     */
    internal fun buildPlaybackInfoPlist(): String {
        val dict = NSDictionary()
        val durationSec = videoPlayer.durationMs / 1000.0
        val positionSec = videoPlayer.positionMs / 1000.0
        val bufferedSec = videoPlayer.bufferedPositionMs / 1000.0
        val ratePlaying = videoPlayer.ratePlaying
        val ready = videoPlayer.readyToPlay && videoPlayer.isActive()
        val buffering = videoPlayer.buffering

        dict.put("duration", NSNumber(durationSec))
        dict.put("position", NSNumber(positionSec))
        dict.put("rate", NSNumber(if (ratePlaying) 1.0 else 0.0))
        dict.put("readyToPlay", NSNumber(ready))
        dict.put("playbackBufferEmpty", NSNumber(buffering))
        dict.put("playbackBufferFull", NSNumber(false))
        dict.put("playbackLikelyToKeepUp", NSNumber(ready && !buffering))

        dict.put("loadedTimeRanges", timeRanges(0.0, bufferedSec.coerceAtLeast(0.0)))
        dict.put("seekableTimeRanges", timeRanges(0.0, durationSec.coerceAtLeast(0.0)))

        return dict.toXMLPropertyList()
    }

    private fun timeRanges(start: Double, duration: Double): NSArray {
        val range = NSDictionary().apply {
            put("start", NSNumber(start))
            put("duration", NSNumber(duration))
        }
        return NSArray(1).apply { setValue(0, range) }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Strips any query string from [uri], returning just the path (e.g. "/rate"). */
    private fun pathOf(uri: String): String {
        val q = uri.indexOf('?')
        return if (q >= 0) uri.substring(0, q) else uri
    }

    /** Returns the value of the first occurrence of query param [name] in [uri]. */
    private fun queryParam(uri: String, name: String): String? {
        val q = uri.indexOf('?')
        if (q < 0 || q == uri.length - 1) return null
        val query = uri.substring(q + 1)
        for (segment in query.split('&')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            if (segment.substring(0, eq) == name) return segment.substring(eq + 1)
        }
        return null
    }

    private fun isBinaryPlist(body: ByteArray): Boolean =
        body.size >= 6 && body[0] == 'b'.code.toByte() &&
            String(body, 0, 6, Charsets.US_ASCII) == "bplist"

    data class PlayParams(val contentLocation: String, val startPosition: Double)

    companion object {
        private val CLAIMED_PATHS = setOf(
            "/play", "/stop", "/rate", "/scrub", "/playback-info"
        )
    }
}
