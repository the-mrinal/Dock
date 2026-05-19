package com.ambient.tvclock.receiver.airplay

/**
 * JNI bridge to UxPlay playfair_decrypt for SETUP ekey decryption.
 */
object PlayfairDecrypt {

    init {
        System.loadLibrary("receiver_playfair")
    }

    fun decrypt(message3: ByteArray, cipherText: ByteArray): ByteArray {
        require(message3.size >= 164) { "key message must be 164 bytes" }
        require(cipherText.size >= 72) { "ekey must be 72 bytes" }
        val keyOut = ByteArray(16)
        nativeDecrypt(message3, cipherText, keyOut)
        return keyOut
    }

    @JvmStatic
    private external fun nativeDecrypt(message3: ByteArray, cipherText: ByteArray, keyOut: ByteArray)
}
