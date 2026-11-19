package zoukan.desktop.download

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayInputStream
import java.io.File

/** One entry of a CBZ, ZIP or EPUB is read whole only up to the size a page may have. */
class CbzReaderLimitTest {
    @TempDir
    lateinit var temp: File

    /** An archive with a small page and a large, highly compressible one (the file stays small). */
    private fun archive(): File = File(temp, "chapter.cbz").also { file ->
        ZipFile(file).use { zip ->
            zip.addStream(ByteArrayInputStream(ByteArray(1_000) { 1 }), ZipParameters().apply { fileNameInZip = "001.jpg" })
            zip.addStream(ByteArrayInputStream(ByteArray(1_000_000)), ZipParameters().apply { fileNameInZip = "002.jpg" })
        }
    }

    @Test
    fun `an entry larger than the page cap fails that page with the reader's message, and the others read`() {
        CbzReader(archive(), { null }, maxEntryBytes = 64 * 1024).use { reader ->
            reader.read(reader.find("001.jpg")!!).size shouldBe 1_000
            val error = shouldThrow<java.io.IOException> { reader.read(reader.find("002.jpg")!!) }
            error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
            // The reader goes on
            reader.read(reader.find("001.jpg")!!).size shouldBe 1_000
        }
    }

    @Test
    fun `an entry at the cap reads whole`() {
        CbzReader(archive(), { null }, maxEntryBytes = 1_000_000).use { reader ->
            reader.read(reader.find("002.jpg")!!).size shouldBe 1_000_000
        }
    }

    @Test
    fun `a stream longer than its header says is cut off at the cap, never read whole`() {
        val endless = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 0)
                served += len
                return len
            }
        }
        CbzReader.readBounded(endless, limit = 10_000) shouldBe null
        (endless.served < 100_000) shouldBe true
        CbzReader.readBounded(ByteArrayInputStream(ByteArray(10_000)), limit = 10_000)!!.size shouldBe 10_000
    }
}
