package zoukan.desktop.local

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Chapter URLs come from the library DB and are restored verbatim from (possibly hostile) backups,
 * so [LocalSource.chapterFile] and [LocalSource.mangaDir] must never resolve outside the local folder.
 */
class LocalSourcePathTest {

    @TempDir
    lateinit var temp: File

    private fun local(): File = File(temp, "local").apply { File(this, "Alpha/Chapter 1").mkdirs() }

    private fun source(): LocalSource = LocalSource(folder = { local() })

    private fun secret(): File = File(temp, "secret.cbz").apply { writeBytes(ByteArray(0)) }

    @Test
    fun `resolves a chapter url inside the local folder`() {
        val source = source()
        source.chapterFile("Alpha/Chapter 1").canonicalPath shouldBe File(local(), "Alpha/Chapter 1").canonicalPath
        source.mangaDir("Alpha")!!.canonicalPath shouldBe File(local(), "Alpha").canonicalPath
    }

    @Test
    fun `rejects a chapter url escaping the folder with dot-dot`() {
        val outside = secret()
        val source = source()
        val e = shouldThrow<IllegalStateException> { source.chapterFile("Alpha/../../secret.cbz") }
        e.message shouldContain "Invalid chapter path"
        outside.isFile shouldBe true
    }

    @Test
    fun `an absolute-path chapter url never reaches the file outside the folder`() {
        // "Alpha/<absolute path>". On macOS and Linux File(parent, child) keeps the parent, so the path stays inside
        // the folder and simply does not exist; on Windows the embedded drive makes it invalid. Either way it fails,
        // and never resolves to the file outside.
        val absolute = secret().absolutePath.replace(File.separatorChar, '/')
        val source = source()
        val e = shouldThrow<IllegalStateException> { source.chapterFile("Alpha/$absolute") }
        (e.message!!.startsWith("Invalid chapter path") || e.message!!.startsWith("Chapter not found")) shouldBe true
    }

    @Test
    fun `rejects a sibling folder sharing only the folder name prefix`() {
        File(temp, "localX/steal.cbz").apply { parentFile.mkdirs() }.writeBytes(ByteArray(0))
        val source = source()
        source.mangaDir("../localX") shouldBe null
    }
}
