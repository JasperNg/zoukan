package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.secrets.EncryptedFiles
import zoukan.desktop.secrets.FileCipher
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * [Images.cachedCover], [Images.writeCoverFile], and [Images.forget] synchronize on the same
 * cover lock so reads/rewrites never race with deletes, replacements, or other reads.
 */
class ImagesCachedCoverRaceTest {

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
        CustomCovers.codec = null
    }

    @Test
    fun `a plain cached cover forgotten while it is read and rewritten is never put back`() {
        val url = "https://covers.example/forget-race.png"
        val manga = Manga.create().copy(source = 42, thumbnailUrl = url)
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
