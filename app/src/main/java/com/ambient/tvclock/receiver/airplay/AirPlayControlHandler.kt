package com.ambient.tvclock.receiver.airplay

import android.content.Context
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.BinaryPropertyListWriter
import com.ambient.tvclock.util.Logger
import com.ambient.tvclock.util.NetworkUtils
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Handles AirPlay control requests on port 7000 (GET /info, pairing, fp-setup, SETUP).
 *
 * macOS uses RTSP/1.0 or HTTP/1.1 with paths like /info and /pair-setup before media streaming.
 */
class AirPlayControlHandler(
    private val context: Context,
    private val pairing: AirPlayPairing,
    private val deviceName: () -> String,
    private val onSetupComplete: (SetupResult) -> Unit,
    private val onTimingPeer: (InetAddress, Int, Socket?) -> Unit = { _, _, _ -> },
    /**
     * Handles the AirPlay video URL HTTP endpoints (/play, /stop, /scrub,
     * /rate, /playback-info). When null, those routes return 501 — used in
     * unit tests that don't need playback wiring.
     */
    private val playbackHandler: AirPlayPlaybackHandler? = null
) {

    private val pairingSession = pairing.newSession()

    // Cached across master→stream SETUP. macOS sends `ekey`/`eiv` only in the master SETUP;
    // the follow-up stream SETUP (streams=[{type:110}]) carries no keys, so we reuse these.
    @Volatile private var cachedAesKey: ByteArray? = null
    @Volatile private var cachedAesIv: ByteArray? = null

    fun handle(request: RtspRequest, clientAddress: InetAddress? = null, clientSocket: Socket? = null): RtspResponse {
        Logger.i("AirPlay control: ${request.method} ${request.uri} (${request.protocol})")
        // Video URL protocol routes are delegated first — they share port 7000
        // with the mirroring control surface, and we want their dedicated
        // handler to win for /play, /stop, /scrub, /rate, /playback-info.
        playbackHandler?.let { if (it.claims(request)) return it.handle(request) }
        return when {
            request.method == "GET" && request.uri.startsWith("/server-info") ->
                handleServerInfo()
            request.method == "GET" && request.uri.contains("/info") ->
                handleInfo(request)
            request.method == "POST" && request.uri == "/pair-setup" ->
                handlePairSetup(request)
            request.method == "POST" && request.uri == "/pair-verify" ->
                handlePairVerify(request)
            // `/fp-setup` is the legacy FairPlay endpoint; `/fp-setup2` is the
            // AirPlay 2 variant iOS reconnects with after the receiver drops
            // mid-session (e.g. when an oversized SET_PARAMETER body breaks
            // the connection). Both use the same handshake payload shapes;
            // routing them through one handler keeps iOS happy so the
            // session can re-establish without falling back to "no video".
            request.method == "POST" && (request.uri == "/fp-setup" || request.uri == "/fp-setup2") ->
                handleFpSetup(request)
            request.method == "SETUP" ->
                handleSetup(request, clientAddress, clientSocket)
            request.method == "RECORD" ->
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf(
                        "Audio-Latency" to "11025",
                        "Audio-Jack-Status" to "connected; type=analog"
                    )
                )
            request.method == "TEARDOWN" ->
                RtspResponse(200, "OK")
            request.method == "OPTIONS" && request.isAirPlayControl ->
                RtspResponse(200, "OK", mapOf(
                    "Public" to "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, " +
                        "POST, GET"
                ))
            request.method == "SET_PARAMETER" -> {
                // Body logging helps diagnose iOS-specific quirks: YouTube and
                // Music send `volume`, `progress`, `dmap` artwork metadata,
                // `text/parameters` lines, or binary plists here.
                val ct = request.headers["Content-Type"] ?: request.headers["content-type"]
                val bodyPreview = if (request.bodyBytes.isEmpty()) "(empty)"
                else if (request.body.length <= 200) request.body
                else request.body.substring(0, 200) + "..."
                Logger.d("SET_PARAMETER (ct=$ct) body=$bodyPreview")
                RtspResponse(200, "OK")
            }
            request.method == "POST" && request.uri == "/feedback" ->
                RtspResponse(200, "OK", bodyBytes = byteArrayOf())
            // FLUSH arrives on the macOS-style binary-plist control channel
            // (seek/pause). Pre-fix this fell through to 501 and broke YouTube's
            // iOS AirPlay handshake — YouTube treats 501 here as "incompatible
            // receiver" and abandons the session before sending /play.
            request.method == "FLUSH" ->
                RtspResponse(200, "OK")
            // POST /audioMode is YouTube-iOS-specific: it sets the requested
            // audio output mode on the receiver. Returning 501 makes YouTube's
            // client conclude the receiver doesn't meet its capability bar and
            // tear the session down. Acknowledging with 200 lets the handshake
            // continue so /play can actually arrive.
            request.method == "POST" && request.uri == "/audioMode" ->
                RtspResponse(200, "OK", bodyBytes = byteArrayOf())
            else -> {
                Logger.w("Unhandled AirPlay control: ${request.method} ${request.uri}")
                RtspResponse(501, "Not Implemented")
            }
        }
    }

    /**
     * `GET /server-info` is the AirPlay 1 capability-discovery probe iOS makes
     * before sending `POST /play`. If we 501 here the sender concludes the
     * receiver doesn't support video URL playback and aborts the flow before
     * `/play` is ever issued — observed in the YouTube AirPlay trace.
     *
     * The response is an XML plist (NOT binary plist) per the openairplay
     * spec §6.1, with deviceid / features / model / protovers / srcvers.
     */
    private fun handleServerInfo(): RtspResponse {
        val dict = NSDictionary()
        dict.put("deviceid", NSString(NetworkUtils.getMacAddress().uppercase()))
        dict.put("features", NSNumber(FEATURES))
        dict.put("model", NSString(MODEL))
        dict.put("protovers", NSString("1.0"))
        dict.put("srcvers", NSString(SOURCE_VERSION))
        val xml = dict.toXMLPropertyList()
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Content-Type" to "text/x-apple-plist+xml"),
            bodyBytes = xml.toByteArray(Charsets.UTF_8)
        )
    }

    private fun handleInfo(request: RtspRequest): RtspResponse {
        val dict = NSDictionary()
        val contentType = request.headers["Content-Type"] ?: request.headers["content-type"]

        if (contentType?.contains("apple-binary-plist") == true && request.bodyBytes.isNotEmpty()) {
            return handleInfoQualifier(request, dict)
        }

        val mac = NetworkUtils.getMacAddress().uppercase()
        val macNoColon = mac.replace(":", "")

        dict.put("deviceID", NSString(macNoColon))
        dict.put("macAddress", NSString(mac))
        dict.put("pk", NSData(pairing.publicKey()))
        dict.put("features", NSNumber(FEATURES))
        dict.put("name", NSString(deviceName()))
        dict.put("pi", NSString(NetworkUtils.getPersistentUuid(context)))
        dict.put("vv", NSNumber(2))
        dict.put("statusFlags", NSNumber(68))
        dict.put("keepAliveLowPower", NSNumber(1))
        dict.put("sourceVersion", NSString(SOURCE_VERSION))
        dict.put("keepAliveSendStatsAsBody", NSNumber(true))
        dict.put("model", NSString(MODEL))
        dict.put("initialVolume", NSNumber(-30.0))

        addAudioLatencies(dict)
        addAudioFormats(dict)
        addDisplays(dict)

        return plistResponse(dict)
    }

    private fun handleInfoQualifier(request: RtspRequest, dict: NSDictionary): RtspResponse {
        // Initial GET/info with qualifier txtAirPlay — return mDNS TXT blob
        val txt = buildAirPlayTxtRecord().toByteArray(Charsets.UTF_8)
        dict.put("txtAirPlay", NSData(txt))
        return plistResponse(dict)
    }

    private fun handlePairSetup(request: RtspRequest): RtspResponse {
        val body = request.bodyBytes
        if (body.size != AirPlayPairing.ED25519_SIZE) {
            Logger.e("pair-setup bad length: ${body.size}")
            return RtspResponse(400, "Bad Request")
        }
        val response = pairingSession.handlePairSetup(body)
        return binaryResponse(response, "application/octet-stream")
    }

    private fun handlePairVerify(request: RtspRequest): RtspResponse {
        return try {
            val response = pairingSession.handlePairVerify(request.bodyBytes)
            binaryResponse(response ?: byteArrayOf(), "application/octet-stream")
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required")
        }
    }

    private fun handleFpSetup(request: RtspRequest): RtspResponse {
        val body = request.bodyBytes
        val data = when (body.size) {
            16 -> AirPlayFairPlay.handleSetup(body)
            164 -> AirPlayFairPlay.handleHandshake(body)
            else -> {
                Logger.e("fp-setup bad length: ${body.size}")
                return RtspResponse(400, "Bad Request")
            }
        }
        return binaryResponse(data, "application/octet-stream")
    }

    private fun handleSetup(request: RtspRequest, clientAddress: InetAddress?, clientSocket: Socket?): RtspResponse {
        if (request.bodyBytes.isEmpty()) {
            return RtspResponse(400, "Bad Request")
        }
        return try {
            val root = com.dd.plist.PropertyListParser.parse(request.bodyBytes) as NSDictionary
            AirPlaySetupParser.logRequest(root)
            val parsed = AirPlaySetupParser.parse(root, pairingSession)

            if (parsed.aesKey != null && parsed.aesIv != null) {
                cachedAesKey = parsed.aesKey
                cachedAesIv = parsed.aesIv
            }
            val result = parsed.copy(
                aesKey = parsed.aesKey ?: cachedAesKey,
                aesIv = parsed.aesIv ?: cachedAesIv
            )

            if (clientAddress != null && result.clientTimingPort != null && result.clientTimingPort > 0) {
                onTimingPeer(clientAddress, result.clientTimingPort, clientSocket)
            }
            onSetupComplete(result)
            val responseDict = AirPlaySetupParser.buildResponse(result)
            plistResponse(responseDict, contentType = "application/x-apple-binary-plist")
        } catch (e: Exception) {
            Logger.e("SETUP failed", e)
            RtspResponse(500, "Internal Server Error")
        }
    }

    private fun plistResponse(
        dict: NSDictionary,
        contentType: String = "application/x-apple-binary-plist"
    ): RtspResponse {
        val bytes = BinaryPropertyListWriter.writeToArray(dict)
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Content-Type" to contentType),
            body = "",
            bodyBytes = bytes
        )
    }

    private fun binaryResponse(data: ByteArray, contentType: String): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Content-Type" to contentType),
            bodyBytes = data
        )
    }

    private fun buildAirPlayTxtRecord(): String {
        val mac = NetworkUtils.getMacAddress()
        val name = deviceName()
        val uuid = NetworkUtils.getPersistentUuid(context)
        return buildString {
            append("deviceid=$mac,md=0,1,2,am=${MODEL},pk=${pairing.publicKey().joinToString("") { "%02x".format(it) }},")
            append("features=0x5A7FFFF7,0x1E,flags=0x4,model=${MODEL},vv=2,srcvers=$SOURCE_VERSION,")
            append("pi=$uuid,fn=$name")
        }
    }

    private fun addAudioLatencies(dict: NSDictionary) {
        val items = intArrayOf(100, 101).map { type ->
            NSDictionary().apply {
                put("type", NSNumber(type))
                put("audioType", NSString("default"))
                put("inputLatencyMicros", NSNumber(0))
                put("outputLatencyMicros", NSNumber(0))
            }
        }
        dict.put("audioLatencies", NSArray(items.size).apply {
            items.forEachIndexed { i, item -> setValue(i, item) }
        })
    }

    private fun addAudioFormats(dict: NSDictionary) {
        val items = intArrayOf(100, 101).map { type ->
            NSDictionary().apply {
                put("type", NSNumber(type))
                put("audioInputFormats", NSNumber(0x3FFFFFC))
                put("audioOutputFormats", NSNumber(0x3FFFFFC))
            }
        }
        dict.put("audioFormats", NSArray(items.size).apply {
            items.forEachIndexed { i, item -> setValue(i, item) }
        })
    }

    private fun addDisplays(dict: NSDictionary) {
        val display = NSDictionary().apply {
            put("uuid", NSString("e0ff8a27-6738-3d56-8a16-cc53aacee925"))
            put("width", NSNumber(1920))
            put("height", NSNumber(1080))
            put("widthPixels", NSNumber(1920))
            put("heightPixels", NSNumber(1080))
            put("rotation", false)
            put("refreshRate", NSNumber(1.0 / 60.0))
            put("maxFPS", NSNumber(60))
            put("overscanned", false)
            put("features", NSNumber(14))
        }
        dict.put("displays", NSArray(1).apply { setValue(0, display) })
    }

    data class SetupResult(
        val mirrorPort: Int?,
        val streamConnectionId: String?,
        val aesKey: ByteArray?,
        val aesIv: ByteArray?,
        val audioDataPort: Int? = null,
        val audioControlPort: Int? = null,
        val clientTimingPort: Int? = null
    )

    companion object {
        private const val FEATURES = 0x5A7FFFF7L
        private const val MODEL = "AppleTV5,3"
        private const val SOURCE_VERSION = "220.68"
    }
}

/**
 * Parses SETUP bplist and builds SETUP response for macOS screen mirroring.
 *
 * macOS sends TWO SETUPs for AirPlay 2 mirror (per UxPlay `raop_handlers.h`):
 *  1. **Master SETUP** — body has `ekey`/`eiv`, no `streams[]`.
 *     Response = `{ timingPort, eventPort }`. Decrypts and stashes the audio AES key.
 *  2. **Stream SETUP** — body has `streams=[{type:110, streamConnectionID}]` (mirror)
 *     or `streams=[{type:96}]` (audio). Response = `{ streams: [...] }` with the
 *     real `dataPort` the sender should connect to.
 *
 * The previous implementation incorrectly extracted a "streamConnectionID" from the
 * RTSP session URL segment (e.g. `rtsp://host/13065137057275399513`) and emitted
 * `streams[]` on the master SETUP. macOS treated that as a malformed handshake,
 * never sent the stream SETUP, and never opened TCP to the mirror dataPort.
 */
object AirPlaySetupParser {

    /**
     * Port [AirPlayEventChannel] is currently listening on. Set by
     * [AirPlayReceiver.start] before any SETUP arrives so [buildResponse]
     * reports it as `eventPort` in the master SETUP plist. 0 means the
     * receiver hasn't bound an event channel yet — i.e. fall back to the
     * old "no event channel" behavior.
     */
    @Volatile var eventPort: Int = 0

    fun parse(
        root: NSDictionary,
        session: AirPlayPairing.Session
    ): AirPlayControlHandler.SetupResult {
        var aesKey: ByteArray? = null
        var aesIv: ByteArray? = null
        val clientTimingPort = (root.objectForKey("timingPort") as? NSNumber)?.intValue()

        val ekey = (root.objectForKey("ekey") as? NSData)?.bytes()
        val eiv = (root.objectForKey("eiv") as? NSData)?.bytes()
        if (ekey != null && eiv != null && ekey.size >= 72) {
            aesIv = eiv.copyOf(16)
            var key = AirPlayFairPlay.decryptAesKey(ekey)
            if (key != null) {
                val secret = session.ecdhSecret()
                if (secret != null) {
                    // SHA-512(aeskey || ecdh_secret) → first 16 bytes; matches UxPlay
                    // raop_handlers.h. Without this, audio CBC + mirror CTR keys both
                    // decrypt to garbage. ECDH secret comes from a prior pair-verify.
                    key = hashAesKeyWithEcdh(key, secret)
                } else {
                    Logger.w("SETUP: no ECDH secret available — aeskey NOT hashed")
                }
                aesKey = key
                Logger.i("SETUP: decrypted AES audio/mirror key")
            } else {
                Logger.e("SETUP: FairPlay decryptAesKey returned null")
            }
        }

        var mirrorPort: Int? = null
        var streamConnectionId: String? = null
        var audioDataPort: Int? = null
        var audioControlPort: Int? = null

        val streams = root.objectForKey("streams") as? NSArray
        if (streams == null) {
            Logger.i("SETUP: master phase (no streams[])")
        } else {
            for (i in 0 until streams.count()) {
                val stream = streams.objectAtIndex(i) as? NSDictionary ?: continue
                val type = (stream.objectForKey("type") as? NSNumber)?.longValue() ?: continue
                Logger.d("SETUP stream type=$type")
                when (type) {
                    110L -> {
                        streamConnectionId = parseStreamConnectionId(stream)
                        mirrorPort = MirrorTcpReceiver.MIRROR_PORT
                        Logger.i("SETUP: mirror stream requested, connectionId=$streamConnectionId")
                    }
                    96L -> {
                        audioDataPort = MirrorAudioPorts.DATA_PORT
                        audioControlPort = MirrorAudioPorts.CONTROL_PORT
                        Logger.i("SETUP: audio stream requested (type 96)")
                    }
                    else -> Logger.d("SETUP: ignoring stream type=$type")
                }
            }
        }

        return AirPlayControlHandler.SetupResult(
            mirrorPort,
            streamConnectionId,
            aesKey,
            aesIv,
            audioDataPort,
            audioControlPort,
            clientTimingPort
        )
    }

    fun logRequest(root: NSDictionary) {
        val keys = (0 until root.count()).mapNotNull { root.allKeys()[it] as? String }
        Logger.i("SETUP request keys: ${keys.joinToString()}")
        (root.objectForKey("timingPort") as? NSNumber)?.let {
            Logger.i("SETUP client timingPort=${it.intValue()}")
        }
        (root.objectForKey("timingProtocol") as? NSString)?.let {
            Logger.i("SETUP timingProtocol=${it.toString()}")
        }
    }

    private fun parseStreamConnectionId(stream: NSDictionary): String? {
        (stream.objectForKey("streamConnectionID") as? NSNumber)?.let { num ->
            return unsignedDecimalString(num.longValue())
        }
        val data = (stream.objectForKey("streamConnectionID") as? NSData)?.bytes() ?: return null
        if (data.size == 8) {
            var id = 0uL
            for (b in data) {
                id = (id shl 8) or (b.toUByte().toULong())
            }
            return id.toString()
        }
        return null
    }

    /** streamConnectionID is uint64 — values above Long.MAX_VALUE must not use signed long. */
    private fun unsignedDecimalString(signedLong: Long): String =
        if (signedLong >= 0) signedLong.toString() else (signedLong.toULong()).toString()

    fun buildResponse(result: AirPlayControlHandler.SetupResult): NSDictionary {
        val dict = NSDictionary()
        val hasStreams = result.mirrorPort != null ||
            (result.audioDataPort != null && result.audioControlPort != null)

        if (!hasStreams) {
            // Master SETUP — sender expects only timingPort/eventPort here.
            dict.put("timingPort", NSNumber(TimingHandler.TIMING_PORT))
            dict.put("eventPort", NSNumber(eventPort))
            Logger.i("SETUP response (master): timingPort=${TimingHandler.TIMING_PORT}, eventPort=$eventPort")
            return dict
        }

        // Stream SETUP — response is a streams[] array with the dataPort the sender will connect to.
        val streams = mutableListOf<NSDictionary>()
        if (result.mirrorPort != null) {
            streams.add(NSDictionary().apply {
                put("type", NSNumber(110))
                put("dataPort", NSNumber(result.mirrorPort))
            })
        }
        if (result.audioDataPort != null && result.audioControlPort != null) {
            streams.add(NSDictionary().apply {
                put("type", NSNumber(96))
                put("dataPort", NSNumber(result.audioDataPort))
                put("controlPort", NSNumber(result.audioControlPort))
            })
        }
        dict.put("streams", NSArray(streams.size).apply {
            streams.forEachIndexed { i, stream -> setValue(i, stream) }
        })
        Logger.i(
            "SETUP response (stream): mirror dataPort=${result.mirrorPort}, " +
                "audio dataPort=${result.audioDataPort} controlPort=${result.audioControlPort}"
        )
        return dict
    }

    private fun hashAesKeyWithEcdh(aesKey: ByteArray, ecdhSecret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(aesKey)
        md.update(ecdhSecret)
        return md.digest().copyOf(16)
    }
}
