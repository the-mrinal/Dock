package com.ambient.tvclock.receiver.airplay

import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest

/**
 * AES-CTR decrypt for AirPlay mirror video — ported from UxPlay mirror_buffer.c.
 *
 * Both the video key and video IV are derived from the audio AES key (FairPlay-
 * decrypted then SHA-512'd with the ECDH shared secret); see UxPlay
 * `mirror_buffer_init_aes` — the IV salt input is `aeskey_audio`, NOT the SETUP
 * `eiv`. The raw `eiv` is only used for AES-CBC audio decryption.
 */
class MirrorBuffer(aesKeyAudio: ByteArray) {

    private val aesKeyAudio = aesKeyAudio.copyOf(16)
    private var aesCtr: StreamCipher? = null
    private var nextDecryptCount = 0
    private val og = ByteArray(16)

    fun initAes(streamConnectionId: String) {
        val keyMaterial = "AirPlayStreamKey$streamConnectionId"
        val ivMaterial = "AirPlayStreamIV$streamConnectionId"
        val md = MessageDigest.getInstance("SHA-512")
        md.update(keyMaterial.toByteArray())
        md.update(aesKeyAudio)
        val videoKey = md.digest().copyOf(16)
        md.reset()
        md.update(ivMaterial.toByteArray())
        md.update(aesKeyAudio)
        val videoIv = md.digest().copyOf(16)
        aesCtr = SICBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(false, ParametersWithIV(KeyParameter(videoKey), videoIv))
        }
        nextDecryptCount = 0
    }

    fun decrypt(input: ByteArray, output: ByteArray, inputLen: Int) {
        val ctr: StreamCipher = aesCtr ?: throw IllegalStateException("Mirror AES not initialized")
        if (nextDecryptCount > 0) {
            // og[restLen..16] contains the unused keystream bytes from the previous packet's
            // trailing partial block — XOR them against the first nextDecryptCount input bytes.
            for (i in 0 until nextDecryptCount) {
                output[i] = (input[i].toInt() xor og[16 - nextDecryptCount + i].toInt()).toByte()
            }
        }
        val encryptLen = ((inputLen - nextDecryptCount) / 16) * 16
        if (encryptLen > 0) {
            ctr.processBytes(input, nextDecryptCount, encryptLen, output, nextDecryptCount)
        }
        val restLen = (inputLen - nextDecryptCount) % 16
        val restStart = inputLen - restLen
        nextDecryptCount = 0
        if (restLen > 0) {
            // Pad the trailing partial-block input with zeros to 16, then CTR-decrypt og IN PLACE.
            // og[0..restLen]  = input ^ keystream → real decrypted bytes (copied to output below).
            // og[restLen..16] = 0     ^ keystream → keystream bytes saved for the next packet
            //                                       (see the `nextDecryptCount > 0` branch above).
            // Decrypting a *copy* of og here would discard the keystream carryover and produce
            // garbage at the start of every subsequent packet (UxPlay mirror_buffer.c parity).
            og.fill(0)
            System.arraycopy(input, restStart, og, 0, restLen)
            ctr.processBytes(og, 0, 16, og, 0)
            for (j in 0 until restLen) {
                output[restStart + j] = og[j]
            }
            nextDecryptCount = 16 - restLen
        }
    }
}
