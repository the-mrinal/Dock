package com.ambient.tvclock.receiver.airplay

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirPlaySetupParserTest — Verifies the two-phase SETUP handshake for AirPlay 2 mirror.
 *
 * macOS sends two SETUP requests:
 *  1. Master SETUP (ekey/eiv, no `streams[]`) → response = {timingPort, eventPort}
 *  2. Stream SETUP (`streams=[{type:110, streamConnectionID}]`) → response = {streams:[{type:110, dataPort}]}
 *
 * Regression coverage:
 *  - URI session segment must NOT be treated as a streamConnectionID (previous bug).
 *  - Master SETUP response must NOT include `streams[]` — that made macOS skip the
 *    stream SETUP entirely and never open TCP to port 7100.
 */
class AirPlaySetupParserTest {

    private fun session(): AirPlayPairing.Session = mockk<AirPlayPairing.Session>().apply {
        every { ecdhSecret() } returns null
    }

    @Test
    fun `master SETUP without streams produces no mirror or audio ports`() {
        val root = NSDictionary().apply {
            // ekey would normally be 72+ bytes encrypted; with no ECDH secret and dummy bytes,
            // decryptAesKey returns null → aesKey stays null, which is fine for this assertion.
            put("ekey", NSData(ByteArray(72)))
            put("eiv", NSData(ByteArray(16)))
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertNull("master SETUP must not declare a mirror port", result.mirrorPort)
        assertNull("master SETUP must not declare a streamConnectionId", result.streamConnectionId)
        assertNull("master SETUP must not declare audio dataPort", result.audioDataPort)
        assertNull("master SETUP must not declare audio controlPort", result.audioControlPort)
    }

    @Test
    fun `master SETUP response contains only timingPort and eventPort`() {
        val result = AirPlayControlHandler.SetupResult(
            mirrorPort = null,
            streamConnectionId = null,
            aesKey = null,
            aesIv = null
        )

        val response = AirPlaySetupParser.buildResponse(result)

        assertNotNull("response must carry timingPort", response.objectForKey("timingPort"))
        assertNotNull("response must carry eventPort", response.objectForKey("eventPort"))
        assertNull(
            "master SETUP response must not include streams[] — macOS skips the follow-up SETUP " +
                "if streams are returned here",
            response.objectForKey("streams")
        )
    }

    @Test
    fun `stream SETUP with type 110 reports mirror port and streamConnectionId from plist`() {
        val streamId = "13065137057275399513"  // uint64 value > Long.MAX_VALUE
        val streamDict = NSDictionary().apply {
            put("type", NSNumber(110))
            // dd.plist serializes large uint64s into NSNumber.longValue() that wraps to negative.
            // The parser must produce the unsigned-decimal string form.
            put("streamConnectionID", NSNumber(streamId.toULong().toLong()))
        }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(MirrorTcpReceiver.MIRROR_PORT, result.mirrorPort)
        assertEquals(streamId, result.streamConnectionId)
    }

    @Test
    fun `stream SETUP response carries only streams array — no timingPort`() {
        val result = AirPlayControlHandler.SetupResult(
            mirrorPort = MirrorTcpReceiver.MIRROR_PORT,
            streamConnectionId = "42",
            aesKey = ByteArray(16),
            aesIv = ByteArray(16)
        )

        val response = AirPlaySetupParser.buildResponse(result)

        val streams = response.objectForKey("streams") as? NSArray
        assertNotNull("stream SETUP response must include streams[]", streams)
        assertEquals(1, streams!!.count())
        val first = streams.objectAtIndex(0) as NSDictionary
        assertEquals(110L, (first.objectForKey("type") as NSNumber).longValue())
        assertEquals(
            MirrorTcpReceiver.MIRROR_PORT.toLong(),
            (first.objectForKey("dataPort") as NSNumber).longValue()
        )
        assertNull(
            "stream SETUP response must not duplicate timingPort",
            response.objectForKey("timingPort")
        )
        assertNull(
            "stream SETUP response must not duplicate eventPort",
            response.objectForKey("eventPort")
        )
    }

    @Test
    fun `stream SETUP with type 96 reports audio data and control ports`() {
        val streamDict = NSDictionary().apply { put("type", NSNumber(96)) }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(MirrorAudioPorts.DATA_PORT, result.audioDataPort)
        assertEquals(MirrorAudioPorts.CONTROL_PORT, result.audioControlPort)
        assertNull("audio-only SETUP must not enable mirror", result.mirrorPort)
    }

    @Test
    fun `type 96 with ct=2 selects ALAC codec`() {
        // Apple Music / Spotify / Safari audio path. iOS sends `ct=2` for
        // realtime ALAC over `streams=[{type:96}]`. Pre-fix we ignored ct and
        // ran an AAC-ELD decoder on these bytes → silence (#14).
        val streamDict = NSDictionary().apply {
            put("type", NSNumber(96))
            put("ct", NSNumber(2))
            put("spf", NSNumber(352))
            put("audioFormat", NSNumber(0x40000L))
        }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(AudioCodec.ALAC, result.audioCodec)
    }

    @Test
    fun `type 96 with ct=8 selects AAC-ELD codec`() {
        val streamDict = NSDictionary().apply {
            put("type", NSNumber(96))
            put("ct", NSNumber(8))
        }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(AudioCodec.AAC_ELD, result.audioCodec)
    }

    @Test
    fun `type 96 with no ct defaults to AAC-ELD codec`() {
        // Older senders / Mac mirror don't always include ct in the SETUP
        // plist. Default to AAC-ELD so the mirror-audio path keeps working.
        val streamDict = NSDictionary().apply { put("type", NSNumber(96)) }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(AudioCodec.AAC_ELD, result.audioCodec)
    }

    @Test
    fun `combined stream SETUP returns both mirror and audio entries`() {
        val mirror = NSDictionary().apply {
            put("type", NSNumber(110))
            put("streamConnectionID", NSNumber(123L))
        }
        val audio = NSDictionary().apply { put("type", NSNumber(96)) }
        val root = NSDictionary().apply {
            put("streams", NSArray(2).apply {
                setValue(0, mirror)
                setValue(1, audio)
            })
        }

        val parsed = AirPlaySetupParser.parse(root, session())
        val response = AirPlaySetupParser.buildResponse(parsed)
        val streams = response.objectForKey("streams") as NSArray

        assertEquals(2, streams.count())
        val types = (0 until streams.count()).map {
            ((streams.objectAtIndex(it) as NSDictionary).objectForKey("type") as NSNumber).longValue()
        }
        assertTrue("response should contain mirror stream (110)", 110L in types)
        assertTrue("response should contain audio stream (96)", 96L in types)
    }

    @Test
    fun `large uint64 streamConnectionID is preserved as unsigned decimal string`() {
        val unsignedValue = "16740710917498555881"
        val signedLong = unsignedValue.toULong().toLong()  // wraps negative
        assertTrue("test setup: value must overflow signed long", signedLong < 0)

        val streamDict = NSDictionary().apply {
            put("type", NSNumber(110))
            put("streamConnectionID", NSNumber(signedLong))
        }
        val root = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, streamDict) })
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(unsignedValue, result.streamConnectionId)
    }

    @Test
    fun `master SETUP decodes client timingPort when supplied`() {
        val root = NSDictionary().apply {
            put("timingPort", NSNumber(7011))
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertEquals(7011, result.clientTimingPort)
        assertNull("master SETUP must not declare mirror port", result.mirrorPort)
    }

    @Test
    fun `parse does not invent streamConnectionId without streams plist`() {
        // Regression: previously the RTSP session URL segment (e.g. "/13065…/") was
        // mis-parsed as a streamConnectionID, causing the master SETUP to be treated
        // as a stream SETUP. macOS then never sent the real stream SETUP.
        val root = NSDictionary().apply {
            put("isScreenMirroringSession", NSNumber(true))
        }

        val result = AirPlaySetupParser.parse(root, session())

        assertNull(result.streamConnectionId)
        assertNull(result.mirrorPort)
        val response = AirPlaySetupParser.buildResponse(result)
        assertFalse(
            "no inferred mirror — response must remain master-shaped",
            response.containsKey("streams")
        )
    }
}
