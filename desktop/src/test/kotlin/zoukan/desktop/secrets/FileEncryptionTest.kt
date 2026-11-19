package zoukan.desktop.secrets

import coil3.disk.DiskCache
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import okio.FileNotFoundException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.network.PersistentCookieStore
import zoukan.desktop.testing.fakes.FaultyFileSystem
import zoukan.desktop.testing.fakes.FaultyFileSystem.Op
import zoukan.desktop.ui.common.Images
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

/** Coil's disk cache, encrypted with the file key (Images.encryptedDiskCache). */
class EncryptedFileSystemTest {

    @TempDir
    lateinit var dir: File

    private val cipher = FileCipher(ByteArray(32) { 5 })

    private val image = "PNG image bytes of https://images.example/cover.png".toByteArray()

    private fun cache(codec: FileCipher = cipher): DiskCache = Images.encryptedDiskCache(File(dir, "images"), codec)

    private fun DiskCache.put(key: String, data: ByteArray) {
        val editor = openEditor(key).shouldNotBeNull()
        fileSystem.write(editor.metadata) { writeUtf8("Content-Type: image/png\nSet-Cookie: tracker=1\n") }
        fileSystem.write(editor.data) { write(data) }
        editor.commit()
    }

    private fun DiskCache.get(key: String): ByteArray? = openSnapshot(key)?.use { snapshot ->
        fileSystem.read(snapshot.data) { readByteArray() }
    }

    /** Every file the cache left, as text, to look for what must not be on disk in plain text. */
    private fun everythingOnDisk(): String =
        File(dir, "images").walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Test
    fun `cached images, their headers and the journal are encrypted and read back after a restart`() {
        val first = cache()
        first.put("https://images.example/cover.png", image)
        first.get("https://images.example/cover.png") shouldBe image
        first.shutdown()

        everythingOnDisk().let { disk ->
            disk shouldNotContain "images.example"
            disk shouldNotContain "Set-Cookie"
            disk shouldNotContain "libcore.io.DiskLruCache"
        }
        File(dir, "images").walkTopDown().filter { it.isFile }.forEach { file ->
            // The journal is framed, every other file encrypted whole
            if (file.name == "journal") {
                String(file.readBytes(), Charsets.US_ASCII) shouldStartWith "ZOUKANFRM1\n"
            } else {
                cipher.isEncoded(file.readBytes()) shouldBe true
            }
        }

        val second = cache()
        second.get("https://images.example/cover.png") shouldBe image
        // Appending to the journal of a cache that is already there
        second.put("https://images.example/other.png", image)
        second.shutdown()
        cache().get("https://images.example/other.png") shouldBe image
    }

    @Test
    fun `a cached image that cannot be decrypted is deleted and is a miss`() {
        val cache = cache()
        cache.put("key", image)
        val data = cache.openSnapshot("key").shouldNotBeNull().use { it.data }
        data.toFile().writeBytes(FileCipher(ByteArray(32) { 6 }).encode(image))

        assertThrows<FileNotFoundException> { cache.fileSystem.read(data) { readByteArray() } }
        data.toFile().exists() shouldBe false
        cache.get("key").shouldBeNull()
        // And it can be cached again
        cache.put("key", image)
        cache.get("key") shouldBe image
    }

    @Test
    fun `a cache written with a key that was lost starts empty instead of failing`() {
        cache().apply { put("key", image) }.shutdown()
        val other = cache(FileCipher(ByteArray(32) { 9 }))
        other.get("key").shouldBeNull()
        other.put("key", image)
        other.get("key") shouldBe image
    }

    @Test
    fun `the plain cache from before encryption is deleted`() {
        val images = File(dir, "images").apply { mkdirs() }
        File(images, "journal").writeText("libcore.io.DiskLruCache\n1\n3\n2\n\nCLEAN abc 10 20\n")
        File(images, "abc.1").writeText("https://images.example/cover.png")

        val cache = cache()
        everythingOnDisk() shouldNotContain "images.example"
        cache.put("key", image)
        cache.get("key") shouldBe image
    }

    @Test
    fun `a new file is written whole when its sink closes, and must-create is kept`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "file").toOkioPath()
        fs.write(file) { writeUtf8("hello") }
        fs.read(file) { readUtf8() } shouldBe "hello"
        assertThrows<okio.IOException> { fs.sink(file, mustCreate = true) }
        fs.appendingSink(file).use { sink -> okio.Buffer().writeUtf8(" world").let { sink.write(it, it.size) } }
        fs.read(file) { readUtf8() } shouldBe "hello world"
        dir.list()!!.toList() shouldBe listOf("file")
    }

    private fun EncryptedFileSystem.append(file: Path, text: String) {
        appendingSink(file).use { sink ->
            okio.Buffer().writeUtf8(text).let { sink.write(it, it.size) }
            sink.flush()
        }
    }

    @Test
    fun `an appended file gets a frame per flush and the bytes already there are never rewritten`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "journal")
        fs.append(file.toOkioPath(), "libcore.io.DiskLruCache\n")
        val before = file.readBytes()
        String(before, Charsets.US_ASCII) shouldStartWith "ZOUKANFRM1\n"
        fs.append(file.toOkioPath(), "CLEAN abc 10 20\n")
        val after = file.readBytes()
        after.copyOf(before.size) shouldBe before
        String(after, Charsets.ISO_8859_1) shouldNotContain "CLEAN"
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "libcore.io.DiskLruCache\nCLEAN abc 10 20\n"
    }

    @Test
    fun `each frame has a nonce of its own, so the same line never encrypts the same`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "journal")
        fs.append(file.toOkioPath(), "READ abc\n")
        val one = file.length().toInt()
        fs.append(file.toOkioPath(), "READ abc\n")
        val bytes = file.readBytes()
        val first = bytes.copyOfRange(11, one)
        val second = bytes.copyOfRange(one, bytes.size)
        second.size shouldBe first.size
        second.contentEquals(first) shouldBe false
    }

    @Test
    fun `a torn last frame is dropped, the frames before it are kept and appending goes on`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "journal")
        fs.append(file.toOkioPath(), "first\n")
        fs.append(file.toOkioPath(), "second\n")
        // A crash in the middle of the second frame
        file.writeBytes(file.readBytes().let { it.copyOf(it.size - 7) })

        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "first\n"
        fs.append(file.toOkioPath(), "third\n")
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "first\nthird\n"
        dir.list()!!.toList() shouldBe listOf("journal")
    }

    @Test
    fun `a last frame left as zeros by a power loss is dropped too`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "journal")
        fs.append(file.toOkioPath(), "first\n")
        val good = file.readBytes()
        fs.append(file.toOkioPath(), "second\n")
        // The second frame's length was written, its bytes were not
        val torn = file.readBytes().also { it.fill(0, good.size + 4) }
        file.writeBytes(torn)
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "first\n"
        // Only zeros after the first frame
        file.writeBytes(good + ByteArray(30))
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "first\n"
        fs.append(file.toOkioPath(), "third\n")
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "first\nthird\n"
    }

    @Test
    fun `a journal encrypted whole, from before frames, still reads and is framed at the next append`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "journal").apply { writeBytes(cipher.encode("old\n".toByteArray())) }
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "old\n"
        fs.append(file.toOkioPath(), "new\n")
        String(file.readBytes(), Charsets.US_ASCII) shouldStartWith "ZOUKANFRM1\n"
        fs.read(file.toOkioPath()) { readUtf8() } shouldBe "old\nnew\n"
        dir.list()!!.toList() shouldBe listOf("journal")
    }

    @Test
    fun `a framed file written with another key is deleted and reads as missing`() {
        val file = File(dir, "journal")
        EncryptedFileSystem(FileSystem.SYSTEM, FileCipher(ByteArray(32) { 6 })).append(file.toOkioPath(), "line\n")
        assertThrows<FileNotFoundException> { EncryptedFileSystem(FileSystem.SYSTEM, cipher).read(file.toOkioPath()) { readUtf8() } }
        file.exists() shouldBe false
    }

    @Test
    fun `the cache keeps what its journal held before a crash tore its last line`() {
        val first = cache()
        first.put("one", image)
        first.put("two", image)
        first.shutdown()
        val journal = File(dir, "images/journal")
        journal.writeBytes(journal.readBytes().let { it.copyOf(it.size - 5) })

        val second = cache()
        second.get("one") shouldBe image
        second.put("three", image)
        second.shutdown()
        cache().get("three") shouldBe image
    }

    @Test
    fun `temporary files of writes cut short are deleted when the cache opens, and nothing else`() {
        val images = File(dir, "images").apply { mkdirs() }
        val stale = File(images, "journal.0f8fad5b-d9cb-469f-a165-70867728950e.tmp").apply { writeText("half") }
        val dataTmp = File(images, "abc.1.7c9e6679-7425-40de-944b-e07fc1f90ae7.tmp").apply { writeText("half") }
        val other = File(images, "notes.tmp").apply { writeText("not ours") }

        val cache = cache()
        stale.exists() shouldBe false
        dataTmp.exists() shouldBe false
        other.exists() shouldBe true
        cache.put("key", image)
        cache.get("key") shouldBe image
    }

    @Test
    fun `a move held up for a moment (a virus scanner on Windows) is tried again`() {
        val flaky = FaultyFileSystem().failOn(Op.MOVE, times = 2) { okio.IOException("The file is being used by another process") }
        val fs = EncryptedFileSystem(flaky, cipher)
        val file = File(dir, "file").toOkioPath()
        fs.write(file) { writeUtf8("hello") }
        fs.read(file) { readUtf8() } shouldBe "hello"
        dir.list()!!.toList() shouldBe listOf("file")
    }

    @Test
    fun `a move that keeps failing fails the write, leaves the file as it was and no temporary file`() {
        val fs = EncryptedFileSystem(FileSystem.SYSTEM, cipher)
        val file = File(dir, "file").toOkioPath()
        fs.write(file) { writeUtf8("hello") }
        val locked = FaultyFileSystem().failOn(Op.MOVE, times = Int.MAX_VALUE) { okio.IOException("locked") }
        assertThrows<okio.IOException> { EncryptedFileSystem(locked, cipher).write(file) { writeUtf8("other") } }
        fs.read(file) { readUtf8() } shouldBe "hello"
        dir.list()!!.toList() shouldBe listOf("file")
    }

    @Test
    fun `a disk that fills during a write fails it, leaves the file as it was and no temporary file, and the next write works`() {
        val disk = FaultyFileSystem()
        val fs = EncryptedFileSystem(disk, cipher)
        val file = File(dir, "file").toOkioPath()
        fs.write(file) { writeUtf8("hello") }

        disk.full(freeBytes = 3)
        val error = assertThrows<okio.IOException> { fs.write(file) { writeUtf8("a longer text than fits") } }
        error.message shouldBe FaultyFileSystem.DISK_FULL
        fs.read(file) { readUtf8() } shouldBe "hello"
        dir.list()!!.toList() shouldBe listOf("file")

        disk.heal()
        fs.write(file) { writeUtf8("a longer text than fits") }
        fs.read(file) { readUtf8() } shouldBe "a longer text than fits"
        dir.list()!!.toList() shouldBe listOf("file")
    }

    @Test
    fun `a read-only disk fails an append and keeps the frames already there`() {
        val disk = FaultyFileSystem()
        val fs = EncryptedFileSystem(disk, cipher)
        val journal = File(dir, "journal").toOkioPath()
        fs.append(journal, "CLEAN a 1 2\n")
        val before = File(dir, "journal").readBytes()

        disk.readOnly()
        assertThrows<okio.IOException> { fs.append(journal, "CLEAN b 1 2\n") }
        File(dir, "journal").readBytes() shouldBe before

        disk.heal()
        fs.append(journal, "CLEAN b 1 2\n")
        fs.read(journal) { readUtf8() } shouldBe "CLEAN a 1 2\nCLEAN b 1 2\n"
    }
}
