package zoukan.desktop.secrets

import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the small files that hold sessions and logins (cookies, the preference files with
 * tracker tokens, sync passwords and source logins) with AES-256-GCM. The key is random, made on
 * first start and kept in the OS secret store like the database key, so the files are useless to
 * anyone who copies the data folder (a roaming profile, a backup of the disk).
 *
 * A file is [MAGIC], a 12-byte nonce, then the ciphertext with its tag (the header is also the
 * additional authenticated data).
 */
class FileCipher(private val key: ByteArray) : SettingsFileCodec {
    init {
        require(key.size == KEY_BYTES) { "The file key must be $KEY_BYTES bytes" }
    }

    override fun isEncoded(stored: ByteArray): Boolean =
        stored.size >= MAGIC.size && MAGIC.indices.all { stored[it] == MAGIC[it] }

    override fun encode(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        return MAGIC + nonce + cipher.doFinal(plain)
    }

    override fun decode(stored: ByteArray): ByteArray {
        require(isEncoded(stored) && stored.size >= MAGIC.size + NONCE_BYTES + TAG_BITS / 8) {
            "Not an encrypted Zoukan file"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val nonce = GCMParameterSpec(TAG_BITS, stored, MAGIC.size, NONCE_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), nonce)
        cipher.updateAAD(MAGIC)
        val start = MAGIC.size + NONCE_BYTES
        return cipher.doFinal(stored, start, stored.size - start)
    }

    companion object {
        const val KEY_NAME = "file-key"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = "ZOUKANENC1\n".toByteArray(Charsets.US_ASCII)
        private val random = SecureRandom()

        /** The cipher with the key from [secrets], which gets a new key when it has none. */
        fun fromStore(secrets: SecretStore): FileCipher {
            val stored = secrets.get(KEY_NAME)?.let { runCatching { HexFormat.of().parseHex(it.trim()) }.getOrNull() }
            if (stored != null && stored.size == KEY_BYTES) return FileCipher(stored)
            val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            secrets.set(KEY_NAME, HexFormat.of().formatHex(key))
            return FileCipher(key)
        }
    }
}
