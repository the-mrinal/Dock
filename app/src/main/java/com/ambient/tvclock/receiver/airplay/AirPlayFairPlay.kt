package com.ambient.tvclock.receiver.airplay

/**
 * FairPlay fp-setup responses — ported from UxPlay fairplay_playfair.c (static replies).
 */
object AirPlayFairPlay {

  private val FP_HEADER = byteArrayOf(
    0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00,
    0x00, 0x00, 0x00, 0x14
  )

  private val REPLY_MESSAGES: Array<ByteArray> = arrayOf(
    hex(
      "46 50 4c 59 03 01 02 00 00 00 00 82 02 00 0f 9f 3f 9e 0a 25 21 db df 31 2a b2 bf b2 9e 8d 23 2b 63 76 a8 c8 18 70 1d 22 ae 93 d8 27 37 fe af 9d b4 fd f4 1c 2d ba 9d 1f 49 ca aa bf 65 91 ac 1f 7b c6 f7 e0 66 3d 21 af e0 15 65 95 3e ab 81 f4 18 ce ed 09 5a db 7c 3d 0e 25 49 09 a7 98 31 d4 9c 39 82 97 34 34 fa cb 42 c6 3a 1c d9 11 a6 fe 94 1a 8a 6d 4a 74 3b 46 c3 a7 64 9e 44 c7 89 55 e4 9d 81 55 00 95 49 c4 e2 f7 a3 f6 d5 ba"
    ),
    hex(
      "46 50 4c 59 03 01 02 00 00 00 00 82 02 01 cf 32 a2 57 14 b2 52 4f 8a a0 ad 7a f1 64 e3 7b cf 44 24 e2 00 04 7e fc 0a d6 7a fc d9 5d ed 1c 27 30 bb 59 1b 96 2e d6 3a 9c 4d ed 88 ba 8f c7 8d e6 4d 91 cc fd 5c 7b 56 da 88 e3 1f 5c ce af c7 43 19 95 a0 16 65 a5 4e 19 39 d2 5b 94 db 64 b9 e4 5d 8d 06 3e 1e 6a f0 7e 96 56 16 2b 0e fa 40 42 75 ea 5a 44 d9 59 1c 72 56 b9 fb e6 51 38 98 b8 02 27 72 19 88 57 16 50 94 2a d9 46 68 8a"
    ),
    hex(
      "46 50 4c 59 03 01 02 00 00 00 00 82 02 02 c1 69 a3 52 ee ed 35 b1 8c dd 9c 58 d6 4f 16 c1 51 9a 89 eb 53 17 bd 0d 43 36 cd 68 f6 38 ff 9d 01 6a 5b 52 b7 fa 92 16 b2 b6 54 82 c7 84 44 11 81 21 a2 c7 fe d8 3b b7 11 9e 91 82 aa d7 d1 8c 70 63 e2 a4 57 55 59 10 af 9e 0e fc 76 34 7d 16 40 43 80 7f 58 1e e4 fb e4 2c a9 de dc 1b 5e b2 a3 aa 3d 2e cd 59 e7 ee e7 0b 36 29 f2 2a fd 16 1d 87 73 53 dd b9 9a dc 8e 07 00 6e 56 f8 50 ce"
    ),
    hex(
      "46 50 4c 59 03 01 02 00 00 00 00 82 02 03 90 01 e1 72 7e 0f 57 f9 f5 88 0d b1 04 a6 25 7a 23 f5 cf ff 1a bb e1 e9 30 45 25 1a fb 97 eb 9f c0 01 1e be 0f 3a 81 df 5b 69 1d 76 ac b2 f7 a5 c7 08 e3 d3 28 f5 6b b3 9d bd e5 f2 9c 8a 17 f4 81 48 7e 3a e8 63 c6 78 32 54 22 e6 f7 8e 16 6d 18 aa 7f d6 36 25 8b ce 28 72 6f 66 1f 73 88 93 ce 44 31 1e 4b e6 c0 53 51 93 e5 ef 72 e8 68 62 33 72 9c 22 7d 82 0c 99 94 45 d8 92 46 c8 c3 59"
    )
  )

  @Volatile
  private var keyMessage: ByteArray? = null

  fun handleSetup(request: ByteArray): ByteArray {
    require(request.size == 16) { "fp-setup step1 expects 16 bytes" }
    require(request[4] == 0x03.toByte()) { "Unsupported FairPlay version" }
    val mode = request[14].toInt() and 0xFF
    require(mode in REPLY_MESSAGES.indices) { "Invalid fp-setup mode $mode" }
    keyMessage = null
    return REPLY_MESSAGES[mode].copyOf()
  }

  fun handleHandshake(request: ByteArray): ByteArray {
    require(request.size == 164) { "fp-setup step2 expects 164 bytes" }
    require(request[4] == 0x03.toByte()) { "Unsupported FairPlay version" }
    keyMessage = request.copyOf()
    val response = ByteArray(32)
    System.arraycopy(FP_HEADER, 0, response, 0, FP_HEADER.size)
    System.arraycopy(request, 144, response, 12, 20)
    return response
  }

  /** Decrypt 72-byte ekey from SETUP into 16-byte AES key (requires prior handshake). */
  fun decryptAesKey(ekey: ByteArray): ByteArray? {
    val msg = keyMessage ?: return null
    if (ekey.size < 72) return null
    return PlayfairDecrypt.decrypt(msg, ekey)
  }

  private fun hex(s: String): ByteArray =
    s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
