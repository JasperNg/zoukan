package zoukan.desktop.ui.common

import coil3.memory.MemoryCache
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.secrets.EncryptedFiles
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.i18n.desktop.not_an_image
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tests for cover disk encryption, atomic cover file writes, cover cache concurrency,
 * download password caching, and forgetting decrypted state on app lock.
 */
class CoverStorageAndSecurityTest {

    /** Covers on disk are encrypted with the file key; covers from before that are moved over as they are read. */
    @Nested
    inner class CoverEncryption {

        @TempDir
        lateinit var dir: File

        private val cipher = FileCipher(ByteArray(32) { 4 })
        private val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        private lateinit var covers: File

        @BeforeEach
        fun setUp() {
            covers = File(dir, "cache/covers")
            Images.init(covers, File(dir, "cache/images"), cipher)
        }

        @AfterEach
        fun tearDown() {
            Images.codec = null
            CustomCovers.codec = null
        }

        private fun customCover(mangaId: Long) = Cover(CustomCovers.file(mangaId).toURI().toString(), -1)

        @Test
        fun `a custom cover is saved encrypted and read back`() {
            val picked = File(dir, "picked.png").apply { writeBytes(image) }
            CustomCovers.save(1, picked)
            val file = CustomCovers.file(1)
            file.readBytes().contentEquals(image) shouldBe false
            cipher.isEncoded(file.readBytes()) shouldBe true
            runBlocking { Images.coverBytes(customCover(1), covers) } shouldBe image
            // Moving the cover to another entry copies the file as it is, and it still reads
            file.copyTo(CustomCovers.file(2))
            runBlocking { Images.coverBytes(customCover(2), covers) } shouldBe image
        }

        @Test
        fun `a plain custom cover from before encryption is read and rewritten encrypted`() {
            val file = CustomCovers.file(3).apply {
                parentFile.mkdirs()
                writeBytes(image)
            }
            runBlocking { Images.coverBytes(customCover(3), covers) } shouldBe image
            cipher.isEncoded(file.readBytes()) shouldBe true
            EncryptedFiles.read(file, cipher).bytes shouldBe image
        }

        @Test
        fun `a custom cover that cannot be decrypted is kept, never deleted`() {
            val stored = FileCipher(ByteArray(32) { 8 }).encode(image)
            val file = CustomCovers.file(4).apply {
                parentFile.mkdirs()
                writeBytes(stored)
            }
            assertThrows<EncryptedFiles.UnreadableFileException> { runBlocking { Images.coverBytes(customCover(4), covers) } }
            file.readBytes() shouldBe stored
        }

        @Test
        fun `a picked file that is not an image is refused and the custom cover is kept`() {
            CustomCovers.save(7, image)
            val before = CustomCovers.file(7).readBytes()
            val error = assertThrows<java.io.IOException> { CustomCovers.save(7, "<html>not an image</html>".toByteArray()) }
            error.message shouldBe zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.not_an_image)
            CustomCovers.file(7).readBytes() shouldBe before
            CustomCovers.read(CustomCovers.file(7)) shouldBe image
            // None for an entry that had no custom cover either
            assertThrows<java.io.IOException> { CustomCovers.save(8, ByteArray(0)) }
            CustomCovers.file(8).exists() shouldBe false
        }

        @Test
        fun `an image type Java reads besides the cover types is a custom cover too`() {
            val bmp = java.io.ByteArrayOutputStream().also {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "bmp", it)
            }.toByteArray()
            CustomCovers.save(9, bmp)
            CustomCovers.read(CustomCovers.file(9)) shouldBe bmp
        }

        @Test
        fun `without the file key a custom cover is not saved`() {
            CustomCovers.codec = null
            val picked = File(dir, "picked.png").apply { writeBytes(image) }
            assertThrows<java.io.IOException> { CustomCovers.save(5, picked) }
            CustomCovers.file(5).exists() shouldBe false
        }

        @Test
        fun `a local source's cover is read as it is and never rewritten`() {
            val local = File(dir, "storage/local/Title/cover.png").apply {
                parentFile.mkdirs()
                writeBytes(image)
            }
            runBlocking { Images.coverBytes(Cover(local.toURI().toString(), 0), covers) } shouldBe image
            local.readBytes() shouldBe image
        }

        @Test
        fun `a local source's encrypted cover is read with the download password, in memory only`() {
            val entry = File(dir, "storage/local/Title").apply { mkdirs() }
            val archive = File(entry, "cover.cbi")
            zoukan.desktop.download.CbzWriter(
                archive,
                zoukan.desktop.download.CbzEncryption("secret".toCharArray(), SecurityPreferences.EncryptionType.AES_256),
            ).use { it.write("cover.jpg", image) }
            val stored = archive.readBytes()
            val cover = Cover(archive.toURI().toString(), 0)

            Images.init(covers, File(dir, "cache/images"), cipher, coverPassword = { "secret".toCharArray() })
            runBlocking { Images.coverBytes(cover, covers) } shouldBe image
            // Nothing decrypted is written: not beside the archive, not in the covers folder
            archive.readBytes() shouldBe stored
            entry.listFiles()!!.map { it.name } shouldBe listOf("cover.cbi")
            covers.listFiles().orEmpty().toList() shouldBe emptyList()

            // Without the password or with a wrong one the cover fails (the loader shows a broken cover)
            Images.init(covers, File(dir, "cache/images"), cipher, coverPassword = { null })
            assertThrows<IllegalStateException> { runBlocking { Images.coverBytes(cover, covers) } }
            Images.init(covers, File(dir, "cache/images"), cipher, coverPassword = { "wrong".toCharArray() })
            assertThrows<java.io.IOException> { runBlocking { Images.coverBytes(cover, covers) } }
            archive.readBytes() shouldBe stored
        }

        @Test
        fun `a cached source cover from before encryption is read and rewritten encrypted`() {
            val cover = Cover("https://covers.example/1.png", 42)
            val file = File(covers, Images.hash("42:https://covers.example/1.png")).apply { writeBytes(image) }
            runBlocking { Images.coverBytes(cover, covers) } shouldBe image
            cipher.isEncoded(file.readBytes()) shouldBe true
            EncryptedFiles.read(file, cipher).bytes shouldBe image
        }

        @Test
        fun `a cached source cover that cannot be decrypted is deleted, so it downloads again`() {
            val file = File(covers, "unreadable").apply { writeBytes(FileCipher(ByteArray(32) { 8 }).encode(image)) }
            Images.cachedCover(file) shouldBe null
            file.exists() shouldBe false
        }

        @Test
        fun `images fetched by address send cookies but keep none`() {
            val saved = mutableListOf<Cookie>()
            val url = "https://images.example/icon.png".toHttpUrl()
            val cookie = Cookie.parse(url, "cf_clearance=abc")!!
            val jar = object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = listOf(cookie)
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    saved += cookies
                }
            }
            val client = Images.imageClient(OkHttpClient.Builder().cookieJar(jar).build())
            client.cookieJar.loadForRequest(url) shouldBe listOf(cookie)
            client.cookieJar.saveFromResponse(url, listOf(Cookie.parse(url, "tracker=1")!!))
            saved shouldBe emptyList()
        }

        @Test
        fun `a cached source cover that cannot be read for now is kept, not deleted`() {
            val stored = cipher.encode(image)
            val file = File(covers, "locked").apply { writeBytes(stored) }
            // Stands in for a file another program holds open on Windows
            assumeTrue(file.setReadable(false) && !file.canRead(), "the file cannot be made unreadable here")
            try {
                Images.cachedCover(file) shouldBe null
                file.exists() shouldBe true
            } finally {
                file.setReadable(true)
            }
            file.readBytes() shouldBe stored
            Images.cachedCover(file) shouldBe image
        }

        @Test
        fun `temporary files of cover writes cut short are deleted at start, and never a cover`() {
            CustomCovers.save(12, File(dir, "picked.png").apply { writeBytes(image) })
            val custom = CustomCovers.file(12)
            val sourceCover = File(covers, Images.hash("42:https://covers.example/1.png")).apply { writeBytes(cipher.encode(image)) }
            // As EncryptedFiles.replace names them
            val customTmp = File.createTempFile("${custom.name}.", ".tmp", custom.parentFile).apply { writeText("half") }
            val coverTmp = File.createTempFile("${sourceCover.name}.", ".tmp", covers).apply { writeText("half") }
            val notOurs = listOf(
                File(custom.parentFile, "notes.tmp"),
                File(custom.parentFile, "${custom.name}.backup.tmp"),
                File(covers, "something.123.tmp"),
            ).onEach { it.writeText("not ours") }

            Images.init(covers, File(dir, "cache/images"), cipher)

            customTmp.exists() shouldBe false
            coverTmp.exists() shouldBe false
            notOurs.forEach { it.exists() shouldBe true }
            EncryptedFiles.read(custom, cipher).bytes shouldBe image
            EncryptedFiles.read(sourceCover, cipher).bytes shouldBe image
        }

        @Test
        fun `while incognito is on, Coil reads its disk cache but writes nothing new to it`() {
            var incognito = true
            val interceptor = IncognitoDiskCacheInterceptor { incognito }
            fun policyAfter(policy: coil3.request.CachePolicy): coil3.request.CachePolicy {
                val request = coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                    .data("https://images.example/icon.png")
                    .diskCachePolicy(policy)
                    .build()
                var seen: coil3.request.ImageRequest? = null
                val chain = object : coil3.intercept.Interceptor.Chain {
                    private var current = request
                    override val request get() = current
                    override val size get() = coil3.size.Size.ORIGINAL
                    override fun withRequest(request: coil3.request.ImageRequest) = apply { current = request }
                    override fun withSize(size: coil3.size.Size) = this
                    override suspend fun proceed(): coil3.request.ImageResult {
                        seen = current
                        return coil3.request.ErrorResult(null, current, IllegalStateException("not loaded"))
                    }
                }
                runBlocking { interceptor.intercept(chain) }
                return seen!!.diskCachePolicy
            }
            policyAfter(coil3.request.CachePolicy.ENABLED) shouldBe coil3.request.CachePolicy.READ_ONLY
            policyAfter(coil3.request.CachePolicy.WRITE_ONLY) shouldBe coil3.request.CachePolicy.DISABLED
            policyAfter(coil3.request.CachePolicy.READ_ONLY) shouldBe coil3.request.CachePolicy.READ_ONLY
            incognito = false
            policyAfter(coil3.request.CachePolicy.ENABLED) shouldBe coil3.request.CachePolicy.ENABLED
        }

        @Test
        fun `deleting a custom cover removes it`() {
            CustomCovers.save(6, File(dir, "picked.png").apply { writeBytes(image) })
            CustomCovers.delete(6) shouldBe true
            CustomCovers.file(6).exists() shouldBe false
            // Deleting one that is not there is no error
            CustomCovers.delete(6) shouldBe true
        }

        @Test
        fun `a plain custom cover deleted while it is read and rewritten is never put back`() {
            val file = CustomCovers.file(7)
            // Whichever runs first, the lazy rewrite or the delete, the cover ends up deleted
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                var deleted = false
                val reader = thread {
                    start.await()
                    runCatching { CustomCovers.read(file) }
                }
                val deleter = thread {
                    start.await()
                    deleted = CustomCovers.delete(7)
                }
                start.countDown()
                reader.join()
                deleter.join()
                deleted shouldBe true
                file.exists() shouldBe false
            }
        }

        @Test
        fun `a plain custom cover replaced while it is read and rewritten keeps the new cover`() {
            val file = CustomCovers.file(13)
            val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9)
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                var readResult: Result<ByteArray>? = null
                var saveResult: Result<Unit>? = null
                val reader = thread {
                    start.await()
                    readResult = runCatching { CustomCovers.read(file) }
                }
                val saver = thread {
                    start.await()
                    saveResult = runCatching { CustomCovers.save(13, other) }
                }
                start.countDown()
                reader.join()
                saver.join()
                saveResult!!.getOrThrow()
                val readBytes = readResult!!.getOrThrow()
                (readBytes.contentEquals(image) || readBytes.contentEquals(other)) shouldBe true
                cipher.isEncoded(file.readBytes()) shouldBe true
                CustomCovers.read(file) shouldBe other
            }
        }

        @Test
        fun `copying a custom cover to another entry replaces its cover and keeps the original`() {
            CustomCovers.save(8, File(dir, "first.png").apply { writeBytes(image) })
            // Another image (a JPEG's signature): a custom cover must be an image
            val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9)
            CustomCovers.save(9, File(dir, "second.png").apply { writeBytes(other) })

            CustomCovers.copy(8, 9) shouldBe true
            runBlocking { Images.coverBytes(customCover(9), covers) } shouldBe image
            runBlocking { Images.coverBytes(customCover(8), covers) } shouldBe image
            CustomCovers.file(9).parentFile.list()!!.sorted() shouldBe listOf(CustomCovers.file(8).name, CustomCovers.file(9).name).sorted()
        }

        @Test
        fun `copying from an entry with no custom cover keeps the other entry's cover`() {
            CustomCovers.save(11, File(dir, "picked.png").apply { writeBytes(image) })
            val stored = CustomCovers.file(11).readBytes()
            CustomCovers.copy(10, 11) shouldBe false
            CustomCovers.file(11).readBytes() shouldBe stored
        }
    }

    /** Two downloads of one cover each write their own temporary file (ui.md, Images.kt:109). */
    @Nested
    inner class CoverFileWrite {

        @TempDir
        lateinit var dir: File

        private val cipher = FileCipher(ByteArray(32) { 3 })

        @Test
        fun `two writes at once never mix their bytes and leave no temporary files`() {
            val target = File(dir, "cover")
            val bothWriting = CountDownLatch(2)
            val writers = listOf('a', 'b').map { c ->
                thread {
                    Images.writeCoverFile(target, cipher) { out ->
                        out.write(ByteArray(50_000) { c.code.toByte() })
                        bothWriting.countDown()
                        bothWriting.await()
                        out.write(ByteArray(50_000) { c.code.toByte() })
                    }
                }
            }
            writers.forEach { it.join() }
            val bytes = EncryptedFiles.read(target, cipher).bytes
            bytes.size shouldBe 100_000
            bytes.toSet().size shouldBe 1
            dir.list()!!.toList() shouldBe listOf("cover")
        }

        @Test
        fun `a failed write keeps no partial cover`() {
            val target = File(dir, "cover")
            runCatching { Images.writeCoverFile(target, cipher) { error("network dropped") } }
            target.exists() shouldBe false
            dir.list()!!.toList() shouldBe emptyList()
        }

        @Test
        fun `a cover that is an HTML page is not cached`() {
            val target = File(dir, "cover")
            val result = runCatching {
                Images.writeCoverFile(target, cipher, validate = Images::requireImage) { out ->
                    out.write("<!DOCTYPE html><html><body>403 Forbidden</body></html>".toByteArray())
                }
            }
            result.isFailure shouldBe true
            target.exists() shouldBe false
            dir.list()!!.toList() shouldBe emptyList()

            val png = java.io.ByteArrayOutputStream().also {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            Images.writeCoverFile(target, cipher, validate = Images::requireImage) { out -> out.write(png) } shouldBe png
            // Kept encrypted: the file on disk is not the image
            target.readBytes().contentEquals(png) shouldBe false
            EncryptedFiles.read(target, cipher).bytes.contentEquals(png) shouldBe true
        }
    }

    /**
     * [Images.cachedCover], [Images.writeCoverFile], [Images.forget], and [CustomCovers.clearCache]
     * synchronize on the same cover lock so reads/rewrites never race with deletes, replacements,
     * or other reads.
     */
    @Nested
    inner class CachedCoverRace {

        @TempDir
        lateinit var dir: File

        private val cipher = FileCipher(ByteArray(32) { 4 })
        private val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        private val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9)
        private lateinit var covers: File

        @BeforeEach
        fun setUp() {
            covers = File(dir, "cache/covers")
            Images.init(covers, File(dir, "cache/images"), cipher)
        }

        @AfterEach
        fun tearDown() {
            Images.codec = null
            CustomCovers.codec = null
        }

        @Test
        fun `a plain cached cover cleared by clearCache while it is read and rewritten is never put back`() {
            val file = File(covers, Images.hash("42:https://covers.example/clear-cache-race.png"))
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                var readResult: Result<ByteArray?>? = null
                var cleared = 0
                val reader = thread {
                    start.await()
                    readResult = runCatching { Images.cachedCover(file) }
                }
                val clearer = thread {
                    start.await()
                    cleared = CustomCovers.clearCache(covers)
                }
                start.countDown()
                reader.join()
                clearer.join()
                val bytes = readResult!!.getOrThrow()
                (bytes == null || bytes.contentEquals(image)) shouldBe true
                cleared shouldBe 1
                file.exists() shouldBe false
            }
        }

        @Test
        fun `a plain cached cover forgotten while it is read and rewritten is never put back`() {
            val url = "https://covers.example/forget-race.png"
            val manga = Manga.create().copy(source = 42, ogThumbnailUrl = url)
            val file = File(covers, Images.hash("42:$url"))
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                var readResult: Result<ByteArray?>? = null
                val reader = thread {
                    start.await()
                    readResult = runCatching { Images.cachedCover(file) }
                }
                val deleter = thread {
                    start.await()
                    Images.forget(manga)
                }
                start.countDown()
                reader.join()
                deleter.join()
                val bytes = readResult!!.getOrThrow()
                (bytes == null || bytes.contentEquals(image)) shouldBe true
                file.exists() shouldBe false
            }
        }

        @Test
        fun `a plain cached cover replaced while it is read and rewritten keeps the new cover`() {
            val file = File(covers, Images.hash("42:https://covers.example/replace-race.png"))
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                var readResult: Result<ByteArray?>? = null
                var writeResult: Result<ByteArray>? = null
                val reader = thread {
                    start.await()
                    readResult = runCatching { Images.cachedCover(file) }
                }
                val writer = thread {
                    start.await()
                    writeResult = runCatching {
                        Images.writeCoverFile(file, cipher) { out -> out.write(other) }
                    }
                }
                start.countDown()
                reader.join()
                writer.join()
                writeResult!!.getOrThrow() shouldBe other
                val readBytes = readResult!!.getOrThrow()!!
                (readBytes.contentEquals(image) || readBytes.contentEquals(other)) shouldBe true
                cipher.isEncoded(file.readBytes()) shouldBe true
                Images.cachedCover(file) shouldBe other
            }
        }

        @Test
        fun `an unreadable cached cover deleted by cachedCover while replaced keeps the new cover`() {
            val file = File(covers, Images.hash("42:https://covers.example/corrupt-race.png"))
            val unreadable = FileCipher(ByteArray(32) { 8 }).encode(image)
            repeat(200) {
                file.parentFile.mkdirs()
                file.writeBytes(unreadable)
                val start = CountDownLatch(1)
                var readResult: Result<ByteArray?>? = null
                var writeResult: Result<ByteArray>? = null
                val reader = thread {
                    start.await()
                    readResult = runCatching { Images.cachedCover(file) }
                }
                val writer = thread {
                    start.await()
                    writeResult = runCatching {
                        Images.writeCoverFile(file, cipher) { out -> out.write(other) }
                    }
                }
                start.countDown()
                reader.join()
                writer.join()
                writeResult!!.getOrThrow() shouldBe other
                val readBytes = readResult!!.getOrThrow()
                (readBytes == null || readBytes.contentEquals(other)) shouldBe true
                file.exists() shouldBe true
                Images.cachedCover(file) shouldBe other
            }
        }

        @Test
        fun `concurrent cachedCover reads on a plain cover all succeed and encrypt it`() {
            val file = File(covers, Images.hash("42:https://covers.example/readers-race.png"))
            repeat(100) {
                file.parentFile.mkdirs()
                file.writeBytes(image)
                val start = CountDownLatch(1)
                val results = arrayOfNulls<Result<ByteArray?>>(4)
                val readers = List(4) { index ->
                    thread {
                        start.await()
                        results[index] = runCatching { Images.cachedCover(file) }
                    }
                }
                start.countDown()
                readers.forEach { it.join() }
                results.forEach { result ->
                    result!!.getOrThrow() shouldBe image
                }
                cipher.isEncoded(file.readBytes()) shouldBe true
                EncryptedFiles.read(file, cipher).bytes shouldBe image
            }
        }
    }

    /**
     * The download password for encrypted covers is read from the OS secret store once a session, not
     * for every `cover.cbi` in a grid: a denied Keychain prompt would come back once per cover.
     */
    @Nested
    inner class PasswordCache {

        private inner class Counting(private val store: SecretStore) : SecretStore by store {
            val reads = AtomicInteger()

            override fun get(key: String): String? {
                reads.incrementAndGet()
                return store.get(key)
            }
        }

        private val preferences = mockk<SecurityPreferences>(relaxed = true)

        @Test
        fun `the password is read once for many covers`() {
            val store = Counting(MemorySecretStore())
            val password = CbzPassword(store, preferences).apply { set("secret") }
            val cache = CoverPasswordCache { password }

            repeat(20) { String(cache.get()!!) shouldBe "secret" }
            store.reads.get() shouldBe 1
        }

        @Test
        fun `a store that fails is asked once, not once per cover`() {
            val failing = FailingSecretStore().failAll(FailingSecretStore.Failure.DENIED)
            val store = Counting(failing)
            val cache = CoverPasswordCache { CbzPassword(store, preferences) }

            repeat(20) { cache.get() shouldBe null }
            store.reads.get() shouldBe 1
        }

        @Test
        fun `changing the password in settings reads it again`() {
            val store = Counting(MemorySecretStore())
            val password = CbzPassword(store, preferences).apply { set("old") }
            val cache = CoverPasswordCache { password }
            String(cache.get()!!) shouldBe "old"

            password.set("new")
            String(cache.get()!!) shouldBe "new"
            password.set("")
            cache.get() shouldBe null
            store.reads.get() shouldBe 3
        }

        @Test
        fun `a caller wiping its copy does not wipe the kept one`() {
            val password = CbzPassword(MemorySecretStore(), preferences).apply { set("secret") }
            val cache = CoverPasswordCache { password }
            cache.get()!!.fill('\u0000')
            String(cache.get()!!) shouldBe "secret"
        }

        @Test
        fun `a failed read is tried again after a minute, so covers come back once the store is unlocked`() {
            val failing = FailingSecretStore()
            failing.backing.set(CbzPassword.KEY, "secret")
            failing.failAll(FailingSecretStore.Failure.DENIED)
            val store = Counting(failing)
            var now = 0L
            val cache = CoverPasswordCache(now = { now }) { CbzPassword(store, preferences) }

            repeat(20) { cache.get() shouldBe null }
            store.reads.get() shouldBe 1

            // Unlocked: within the minute the failure is still kept, after it the password is read
            failing.heal()
            now += 30_000
            cache.get() shouldBe null
            now += 31_000
            String(cache.get()!!) shouldBe "secret"
            store.reads.get() shouldBe 2

            // A password read is kept for the session
            now += 3_600_000
            String(cache.get()!!) shouldBe "secret"
            store.reads.get() shouldBe 2
        }

        @Test
        fun `a failed read is not kept longer when the clock goes back`() {
            val failing = FailingSecretStore()
            failing.backing.set(CbzPassword.KEY, "secret")
            failing.failAll(FailingSecretStore.Failure.DENIED)
            val store = Counting(failing)
            var now = 10_000_000L
            val cache = CoverPasswordCache(now = { now }) { CbzPassword(store, preferences) }
            cache.get() shouldBe null

            failing.heal()
            // A clock set back an hour: the failure would otherwise be kept until it catches up
            now -= 3_600_000
            String(cache.get()!!) shouldBe "secret"
            store.reads.get() shouldBe 2
        }
    }

    /**
     * Locking the app forgets what the lock should hide: the download password kept for encrypted
     * covers and the decrypted images in Coil's memory cache. After the unlock they are read again.
     */
    @Nested
    inner class AppLockForgetsDecrypted {

        private inner class Counting(private val store: SecretStore) : SecretStore by store {
            val reads = AtomicInteger()

            override fun get(key: String): String? {
                reads.incrementAndGet()
                return store.get(key)
            }
        }

        private inner class FakeImage : coil3.Image {
            override val size = 100L
            override val width = 10
            override val height = 10
            override val shareable = true
            override fun draw(canvas: org.jetbrains.skia.Canvas) = Unit
        }

        @Test
        fun `clearing the cover password cache reads the password again`() {
            val store = Counting(MemorySecretStore())
            val password = CbzPassword(store, mockk<SecurityPreferences>(relaxed = true)).apply { set("secret") }
            val cache = CoverPasswordCache { password }
            String(cache.get()!!) shouldBe "secret"
            store.reads.get() shouldBe 1

            cache.clear()

            String(cache.get()!!) shouldBe "secret"
            store.reads.get() shouldBe 2
        }

        @Test
        fun `forgetting empties the image memory cache and the password cache`() {
            val memory = MemoryCache.Builder().maxSizeBytes(1024 * 1024).build()
            memory[MemoryCache.Key("-1:file:/a/cover.cbi:0")] = MemoryCache.Value(FakeImage())
            val store = Counting(MemorySecretStore())
            val password = CbzPassword(store, mockk<SecurityPreferences>(relaxed = true)).apply { set("secret") }
            val cache = CoverPasswordCache { password }
            cache.get()

            Images.forgetDecrypted(memory, cache)

            memory.size shouldBe 0L
            cache.get()
            store.reads.get() shouldBe 2
        }

        @Test
        fun `each lock forgets once, an unlock or the unlocked start does not`() = runTest {
            val locked = MutableStateFlow(false)
            val forgets = AtomicInteger()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            Images.followAppLock(locked, scope) { forgets.incrementAndGet() }
            forgets.get() shouldBe 0

            locked.value = true
            forgets.get() shouldBe 1
            locked.value = false
            forgets.get() shouldBe 1
            locked.value = true
            forgets.get() shouldBe 2
            scope.cancel()
        }

        @Test
        fun `a forget that fails does not stop the next lock from forgetting`() = runTest {
            val locked = MutableStateFlow(false)
            val forgets = AtomicInteger()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            Images.followAppLock(locked, scope) {
                if (forgets.incrementAndGet() == 1) throw IllegalStateException("cache gone")
            }
            locked.value = true
            locked.value = false
            locked.value = true
            forgets.get() shouldBe 2
            scope.cancel()
        }
    }
}
