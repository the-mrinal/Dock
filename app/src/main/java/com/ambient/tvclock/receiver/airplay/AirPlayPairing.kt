package com.ambient.tvclock.receiver.airplay

import android.content.Context
import com.ambient.tvclock.util.Logger
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.jcajce.spec.EdDSAParameterSpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AirPlay transient pairing (no PIN) — ported from UxPlay pairing.c / raop_handlers.
 *
 * macOS sends POST /pair-setup (32 bytes) → server Ed25519 public key,
 * then POST /pair-verify (two steps) to establish a shared X25519 secret.
 */
class AirPlayPairing(context: Context) {

    private val edPrivate: Ed25519PrivateKeyParameters
    private val edPublic: Ed25519PublicKeyParameters

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPriv = prefs.getString(KEY_ED_PRIVATE, null)
        val storedPub = prefs.getString(KEY_ED_PUBLIC, null)
        if (storedPriv != null && storedPub != null) {
            edPrivate = Ed25519PrivateKeyParameters(hexToBytes(storedPriv), 0)
            edPublic = Ed25519PublicKeyParameters(hexToBytes(storedPub), 0)
        } else {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val kp = gen.generateKeyPair()
            edPrivate = kp.private as Ed25519PrivateKeyParameters
            edPublic = kp.public as Ed25519PublicKeyParameters
            prefs.edit()
                .putString(KEY_ED_PRIVATE, bytesToHex(edPrivate.encoded))
                .putString(KEY_ED_PUBLIC, bytesToHex(edPublic.encoded))
                .apply()
        }
    }

    fun publicKey(): ByteArray = edPublic.encoded.copyOf(ED25519_SIZE)

    fun newSession(): Session = Session(edPrivate, edPublic)

    class Session(
        private val edPrivate: Ed25519PrivateKeyParameters,
        private val edPublic: Ed25519PublicKeyParameters
    ) {
        private var status = Status.INITIAL
        private var ourX25519Private: X25519PrivateKeyParameters? = null
        private var ourX25519Public: X25519PublicKeyParameters? = null
        private var theirX25519Public: X25519PublicKeyParameters? = null
        private var theirEdPublic: Ed25519PublicKeyParameters? = null
        private var ecdhSecret: ByteArray? = null

        fun handlePairSetup(body: ByteArray): ByteArray {
            require(body.size == ED25519_SIZE) { "pair-setup expects 32 bytes, got ${body.size}" }
            status = Status.SETUP
            Logger.i("AirPlay pairing: pair-setup OK")
            return edPublic.encoded.copyOf(ED25519_SIZE)
        }

        fun handlePairVerify(body: ByteArray): ByteArray? {
            require(body.size >= 4) { "pair-verify too short" }
            return when (body[0].toInt()) {
                1 -> handlePairVerifyStep1(body)
                0 -> handlePairVerifyStep2(body)
                else -> throw IllegalArgumentException("Unknown pair-verify step: ${body[0]}")
            }
        }

        private fun handlePairVerifyStep1(body: ByteArray): ByteArray {
            require(body.size == 4 + X25519_SIZE + ED25519_SIZE) {
                "pair-verify step1 size ${body.size}"
            }
            val clientX25519 = body.copyOfRange(4, 4 + X25519_SIZE)
            val clientEd25519 = body.copyOfRange(4 + X25519_SIZE, 4 + X25519_SIZE + ED25519_SIZE)

            theirX25519Public = X25519PublicKeyParameters(clientX25519, 0)
            theirEdPublic = Ed25519PublicKeyParameters(clientEd25519, 0)

            val xGen = X25519KeyPairGenerator()
            xGen.init(org.bouncycastle.crypto.params.X25519KeyGenerationParameters(SecureRandom()))
            val xKp = xGen.generateKeyPair()
            ourX25519Private = xKp.private as X25519PrivateKeyParameters
            ourX25519Public = xKp.public as X25519PublicKeyParameters

            val agreement = X25519Agreement()
            agreement.init(ourX25519Private)
            ecdhSecret = ByteArray(X25519_SIZE)
            agreement.calculateAgreement(theirX25519Public, ecdhSecret, 0)
            status = Status.HANDSHAKE

            val signature = buildEncryptedSignature()
            val response = ByteArray(X25519_SIZE + signature.size)
            System.arraycopy(ourX25519Public!!.encoded, 0, response, 0, X25519_SIZE)
            System.arraycopy(signature, 0, response, X25519_SIZE, signature.size)
            Logger.i("AirPlay pairing: pair-verify step1 OK")
            return response
        }

        private fun handlePairVerifyStep2(body: ByteArray): ByteArray {
            require(body.size == 4 + PAIRING_SIG_SIZE) { "pair-verify step2 size ${body.size}" }
            val encryptedSig = body.copyOfRange(4, 4 + PAIRING_SIG_SIZE)
            verifyClientSignature(encryptedSig)
            status = Status.FINISHED
            Logger.i("AirPlay pairing: pair-verify complete")
            return ByteArray(0)
        }

        fun isPaired(): Boolean = status == Status.FINISHED

        fun ecdhSecret(): ByteArray? = ecdhSecret?.copyOf()

        private fun buildEncryptedSignature(): ByteArray {
            val sigPlain = ByteArray(PAIRING_SIG_SIZE)
            val msg = ByteArray(PAIRING_SIG_SIZE)
            System.arraycopy(ourX25519Public!!.encoded, 0, msg, 0, X25519_SIZE)
            System.arraycopy(theirX25519Public!!.encoded, 0, msg, X25519_SIZE, X25519_SIZE)

            val signer = Ed25519Signer()
            signer.init(true, edPrivate)
            signer.update(msg, 0, msg.size)
            val rawSig = signer.generateSignature()
            System.arraycopy(rawSig, 0, sigPlain, 0, minOf(rawSig.size, PAIRING_SIG_SIZE))

            val key = deriveKey(SALT_KEY, AES_KEY_LEN)
            val iv = deriveKey(SALT_IV, AES_IV_LEN)
            return aesCtrProcess(newAesCtrCipher(key, iv), sigPlain)
        }

        private fun verifyClientSignature(encryptedSig: ByteArray) {
            val key = deriveKey(SALT_KEY, AES_KEY_LEN)
            val iv = deriveKey(SALT_IV, AES_IV_LEN)
            // One CTR context for dummy round + decrypt — keystream must advance (UxPlay pairing_session_finish).
            val cipher = newAesCtrCipher(key, iv)
            aesCtrProcess(cipher, ByteArray(PAIRING_SIG_SIZE))
            val decrypted = aesCtrProcess(cipher, encryptedSig)

            val msg = ByteArray(PAIRING_SIG_SIZE)
            System.arraycopy(theirX25519Public!!.encoded, 0, msg, 0, X25519_SIZE)
            System.arraycopy(ourX25519Public!!.encoded, 0, msg, X25519_SIZE, X25519_SIZE)

            val verifier = Ed25519Signer()
            verifier.init(false, theirEdPublic)
            verifier.update(msg, 0, msg.size)
            if (!verifier.verifySignature(decrypted)) {
                throw SecurityException("pair-verify signature invalid")
            }
        }

        private fun deriveKey(salt: String, keyLen: Int): ByteArray {
            val secret = ecdhSecret ?: throw IllegalStateException("ECDH secret not ready")
            val md = MessageDigest.getInstance("SHA-512")
            md.update(salt.toByteArray(Charsets.UTF_8))
            md.update(secret)
            return md.digest().copyOf(keyLen)
        }

        private fun newAesCtrCipher(key: ByteArray, iv: ByteArray): StreamCipher {
            val cipher: StreamCipher = SICBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(true, org.bouncycastle.crypto.params.ParametersWithIV(KeyParameter(key), iv))
            return cipher
        }

        private fun aesCtrProcess(cipher: StreamCipher, data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            cipher.processBytes(data, 0, data.size, out, 0)
            return out
        }

        private enum class Status { INITIAL, SETUP, HANDSHAKE, FINISHED }
    }

    companion object {
        const val ED25519_SIZE = 32
        const val X25519_SIZE = 32
        const val PAIRING_SIG_SIZE = 64
        private const val AES_KEY_LEN = 16
        private const val AES_IV_LEN = 16
        private const val SALT_KEY = "Pair-Verify-AES-Key"
        private const val SALT_IV = "Pair-Verify-AES-IV"
        private const val PREFS_NAME = "phairplay_pairing"
        private const val KEY_ED_PRIVATE = "ed25519_private"
        private const val KEY_ED_PUBLIC = "ed25519_public"

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
    }
}
