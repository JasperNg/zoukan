package zoukan.desktop.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * A cover write that was killed half way leaves its hidden temporary file (`.cover.<digits>.tmp`)
 * in the entry's folder, which may have come from the Android app. The next cover write there
 * removes the app's own stale ones, and nothing else.
 */
class LocalCoverTempFilesTest {

    @TempDir
    lateinit var temp: File

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 0)

    private fun File.aged(hours: Long) = apply {
        Files.setLastModifiedTime(toPath(), FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours)))
    }

    @Test
    fun `a cover write removes the app's stale cover temporary files and keeps everything else`() {
        val entry = File(temp, "local/Entry").apply { mkdirs() }
        val stale = File(entry, ".cover.1234567890.tmp").apply { writeText("half a cover") }.aged(48)
        // Just written, perhaps by another cover write right now
        val fresh = File(entry, ".cover.987.tmp").apply { writeText("being written") }.aged(0)
        // Not the app's pattern, or not a file the app wrote
        val other = listOf(".cover.abc.tmp", "cover.123.tmp", ".cover.123.tmp.bak", ".Cover.123.tmp")
            .map { File(entry, it).apply { writeText("not ours") }.aged(48) }
        val folder = File(entry, ".cover.555.tmp").apply { mkdirs() }.aged(48)
        val chapter = File(entry, "Chapter 1.cbz").apply { writeText("chapter") }.aged(48)

        LocalSource(folder = { File(temp, "local") }).writeCover(entry, png, encrypted = false)

        stale.exists() shouldBe false
        fresh.exists() shouldBe true
        other.forEach { it.exists() shouldBe true }
        folder.isDirectory shouldBe true
        chapter.readText() shouldBe "chapter"
        File(entry, "cover.png").exists() shouldBe true
    }

    @Test
    fun `a stale-looking link is never followed or removed`() {
        val entry = File(temp, "local/Entry").apply { mkdirs() }
        val target = File(temp, "elsewhere.txt").apply { writeText("the user's file") }.aged(48)
        val link = try {
            Files.createSymbolicLink(File(entry, ".cover.42.tmp").toPath(), target.toPath())
        } catch (e: Exception) {
            null
        }
        assumeTrue(link != null, "symbolic links cannot be made here")
        // The link itself as old as a stale file, where the OS lets its time be set
        runCatching {
            val old = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48))
            Files.getFileAttributeView(link!!, java.nio.file.attribute.BasicFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .setTimes(old, null, null)
        }

        LocalSource(folder = { File(temp, "local") }).writeCover(entry, png, encrypted = false)

        Files.isSymbolicLink(link!!) shouldBe true
        target.readText() shouldBe "the user's file"
    }
}
