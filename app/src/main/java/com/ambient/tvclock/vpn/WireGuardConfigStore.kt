package com.ambient.tvclock.vpn

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

// BadConfigException is referenced from KDoc only.
@Suppress("unused") private val _badConfigImport: Class<BadConfigException> = BadConfigException::class.java

/**
 * On-disk persistence for the user's WireGuard config. The raw `.conf` bytes
 * are encrypted at rest via Jetpack Security's [EncryptedFile] (Tink AES-256-GCM)
 * because they contain the user's private key.
 *
 * Parsing is delegated to [Config.parse] from `com.wireguard:config` so we get
 * exactly the same `wg-quick` semantics the official WireGuard app uses.
 */
class WireGuardConfigStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "wireguard")
    private val file: File = File(dir, "wg0.conf.enc")

    /**
     * Parse [raw] as a wg-quick config and, on success, persist it encrypted.
     * Returns the parsed [Config] so callers can read it back without
     * re-decrypting immediately. Throws [BadConfigException] on parse failure.
     *
     * Note: we write directly to `wg0.conf.enc`, not via a temp+rename. Jetpack
     * Security's [EncryptedFile] uses the destination file name as Tink AAD, so
     * encrypting against a temp path and renaming would produce a ciphertext that
     * fails to decrypt against the renamed path. A crash mid-write leaves a corrupt
     * file; the user just re-imports, which is acceptable for a config-blob.
     */
    fun save(raw: String): Config {
        val parsed = Config.parse(BufferedReader(StringReader(raw)))
        dir.mkdirs()
        if (file.exists() && !file.delete()) {
            Timber.w("WireGuardConfigStore: could not delete existing config; overwrite may fail")
        }
        encryptedFile(file).openFileOutput().use { out ->
            out.write(raw.toByteArray(Charsets.UTF_8))
        }
        VpnPreferences.setConfigPresent(context, true)
        return parsed
    }

    /** Returns the persisted config, or null if absent / unreadable. */
    fun load(): Config? {
        if (!file.exists()) return null
        return try {
            val raw = encryptedFile(file).openFileInput().use { it.readBytes() }
                .toString(Charsets.UTF_8)
            Config.parse(BufferedReader(StringReader(raw)))
        } catch (e: BadConfigException) {
            Timber.w(e, "WireGuardConfigStore: stored config does not parse")
            null
        } catch (e: Exception) {
            Timber.w(e, "WireGuardConfigStore: failed to read encrypted config")
            null
        }
    }

    /** Returns the raw decrypted .conf text, or null if absent. Used by the controller's LAN-bypass rewriter. */
    fun loadRaw(): String? {
        if (!file.exists()) return null
        return try {
            encryptedFile(file).openFileInput().use { it.readBytes() }
                .toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "WireGuardConfigStore: failed to read raw config")
            null
        }
    }

    fun clear() {
        if (file.exists() && !file.delete()) {
            Timber.w("WireGuardConfigStore: could not delete wg0.conf.enc")
        }
        VpnPreferences.setConfigPresent(context, false)
    }

    private fun encryptedFile(target: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            target,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }
}
