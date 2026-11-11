package zoukan.desktop.secrets

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
