package zoukan.desktop.secrets

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.network.PersistentCookieStore
import java.io.File
import java.net.HttpCookie
import java.net.URI

class FileCipherTest {
    @TempDir
    lateinit var root: File

    private val cipher = FileCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `what is encrypted reads back, and not with another key or after a change`() {
        val stored = cipher.encode("tracker token".toByteArray())
        cipher.isEncoded(stored) shouldBe true
        stored.decodeToString() shouldNotContain "tracker token"
        cipher.decode(stored).decodeToString() shouldBe "tracker token"
        // A fresh nonce each time
        (cipher.encode("tracker token".toByteArray()).contentEquals(stored)) shouldBe false

        shouldThrow<Exception> { FileCipher(ByteArray(32) { 9 }).decode(stored) }
        val changed = stored.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        shouldThrow<Exception> { cipher.decode(changed) }
        cipher.isEncoded("<?xml version=\"1.0\"?>".toByteArray()) shouldBe false
    }

    @Test
    fun `the key is made once and kept in the secret store`() {
        val secrets = FileSecretStore(File(root, "secrets"))
        val stored = FileCipher.fromStore(secrets).encode("x".toByteArray())
        FileCipher.fromStore(secrets).decode(stored).decodeToString() shouldBe "x"
    }

    /** A store the user denied access to, as the Keychain answers after "Deny". */
    private class DeniedStore : SecretStore {
        override val name = "macOS Keychain"
        val written = mutableListOf<String>()
        override fun get(key: String): String? =
            throw IllegalStateException("SecKeychainFindGenericPassword failed with status -25293")
        override fun set(key: String, value: String) {
            written += key
        }
        override fun delete(key: String) {
            written += "-$key"
        }
    }

    @Test
    fun `a secret store that cannot be read stops the start with a clear error and writes no new key`() {
        val store = DeniedStore()
        val error = shouldThrow<SecretStoreUnavailableException> { FileCipher.fromStore(store) }
        error.storeName shouldBe "macOS Keychain"
        error.message shouldContain "macOS Keychain"
        store.written.shouldBeEmpty()
    }

    @Test
    fun `a stored key that cannot be read is kept before a new one replaces it`() {
        val secrets = FileSecretStore(File(root, "secrets"))
        secrets.set(FileCipher.KEY_NAME, "not hex")
        FileCipher.fromStore(secrets)
        secrets.get(FileCipher.UNREADABLE_KEY_NAME) shouldBe "not hex"
        secrets.get(FileCipher.KEY_NAME)!!.length shouldBe 64
    }

    @Test
    fun `preference files are encrypted, and a plain one from before is taken over`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        val file = File(root, "settings/cipher_test.xml")
        try {
            // Written by a version without encryption
            SettingsFileCodec.current = null
            JavaSharedPreferences("cipher_test").edit().putString("token", "s3cret-value").commit()
            file.readText().contains("s3cret-value") shouldBe true

            SettingsFileCodec.current = cipher
            val prefs = JavaSharedPreferences("cipher_test")
            prefs.getString("token", null) shouldBe "s3cret-value"
            // Reading it was enough to encrypt it
            cipher.isEncoded(file.readBytes()) shouldBe true
            file.readBytes().decodeToString() shouldNotContain "s3cret-value"

            prefs.edit().putString("other", "more").commit()
            cipher.isEncoded(file.readBytes()) shouldBe true
            JavaSharedPreferences("cipher_test").all shouldBe mapOf("token" to "s3cret-value", "other" to "more")
        } finally {
            SettingsFileCodec.current = null
        }
    }

    @Test
    fun `a preference file that cannot be decrypted is kept aside`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        try {
            SettingsFileCodec.current = cipher
            JavaSharedPreferences("lost_key").edit().putString("token", "value").commit()
            SettingsFileCodec.current = FileCipher(ByteArray(32) { 7 })
            JavaSharedPreferences("lost_key").all shouldBe emptyMap()
            File(root, "settings/lost_key.xml.unreadable").isFile shouldBe true
        } finally {
            SettingsFileCodec.current = null
        }
    }

    @Test
    fun `a second unreadable load keeps the first unreadable copy`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        try {
            SettingsFileCodec.current = cipher
            JavaSharedPreferences("lost_twice").edit().putString("token", "value").commit()
            val original = File(root, "settings/lost_twice.xml").readBytes()

            // The key is lost: the file is set aside, and a defaults-only file is saved
            SettingsFileCodec.current = FileCipher(ByteArray(32) { 7 })
            JavaSharedPreferences("lost_twice").edit().putString("other", "x").commit()

            // Another key that cannot read that file: a second failed load
            SettingsFileCodec.current = FileCipher(ByteArray(32) { 9 })
            JavaSharedPreferences("lost_twice").all shouldBe emptyMap()

            val copies = File(root, "settings").listFiles { f -> f.name.startsWith("lost_twice.xml.unreadable") }!!
            copies.any { it.readBytes().contentEquals(original) } shouldBe true
        } finally {
            SettingsFileCodec.current = null
        }
    }

    @Test
    fun `an encrypted settings file opened before the codec is set is never saved over`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        try {
            SettingsFileCodec.current = cipher
            JavaSharedPreferences("early_open").edit().putString("token", "value").commit()
            val file = File(root, "settings/early_open.xml")
            val original = file.readBytes()

            // Opened before the host app sets the codec
            SettingsFileCodec.current = null
            JavaSharedPreferences("early_open").edit().putString("other", "x").commit()

            file.readBytes().contentEquals(original) shouldBe true
        } finally {
            SettingsFileCodec.current = null
        }
    }

    @Test
    fun `the cookie file is encrypted, and a plain one from before is taken over`() {
        val file = File(root, "cookies.json")
        val plain = PersistentCookieStore(file)
        plain.add(
            URI("https://example.org/"),
            HttpCookie("session", "s3cret-cookie").apply {
                maxAge = 3600
                path = "/"
            },
        )
        save(plain)
        file.readText().contains("s3cret-cookie") shouldBe true

        val encrypted = PersistentCookieStore(file, cipher)
        encrypted.get(URI("https://example.org/")).single().value shouldBe "s3cret-cookie"
        cipher.isEncoded(file.readBytes()) shouldBe true
        file.readBytes().decodeToString() shouldNotContain "s3cret-cookie"

        PersistentCookieStore(file, cipher).get(URI("https://example.org/")).single().value shouldBe "s3cret-cookie"
    }

    private fun save(store: PersistentCookieStore) {
        PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(store)
    }
}
