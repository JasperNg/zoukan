package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * What counts as a link when deleting: a folder named in another letter case than on disk, or a
 * Windows reparse point that is not a link, is the folder itself. Taken for a link, an interrupted
 * download's folder was refused on every retry, and trash and deletes refused it too.
 */
class SafeDeleteLinkDetectionTest {
    @TempDir
    lateinit var temp: File

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Whether [dir]'s file system finds a name in any letter case (macOS's and Windows' default). */
    private fun ignoresCase(dir: File): Boolean {
        val probe = File(dir, "CaseProbe").apply { mkdirs() }
        return File(dir, "caseprobe").exists().also { probe.delete() }
    }

    @Test
    fun `a folder named in another letter case than on disk is no link`() {
        assumeTrue(ignoresCase(temp), "the file system tells names apart by letter case")
        val onDisk = File(temp, "Entry/Chapter 1").apply { mkdirs() }
        File(onDisk, "001.png").writeText("page")
        // A source renamed the chapter by letter case only after an interrupted download
        val asListed = File(temp, "Entry/CHAPTER 1")

        SafeDelete.isLink(asListed.toPath()) shouldBe false
        SafeDelete.delete(temp, asListed) shouldBe true
        onDisk.exists() shouldBe false
    }

    @Test
    fun `a parent named in another letter case than on disk is no link either`() {
        assumeTrue(ignoresCase(temp), "the file system tells names apart by letter case")
        File(temp, "Source (EN)/Entry/Chapter 1").mkdirs()
        SafeDelete.isLink(File(temp, "source (en)/ENTRY/chapter 1").toPath()) shouldBe false
    }

    @Test
    fun `a symbolic link to a folder is still a link, also named in another letter case`() {
        assumeFalse(isWindows, "symbolic links need rights on Windows; the junction test covers it")
        val target = File(temp, "outside").apply { mkdirs() }
        File(temp, "Entry").mkdirs()
        val link = File(temp, "Entry/Linked")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        SafeDelete.isLink(link.toPath()) shouldBe true
        if (ignoresCase(temp)) SafeDelete.isLink(File(temp, "Entry/LINKED").toPath()) shouldBe true
    }

    @Test
    fun `a folder listed through a linked parent is still seen through the link`() {
        assumeFalse(isWindows, "symbolic links need rights on Windows")
        val real = File(temp, "real/Chapter 1").apply { mkdirs() }
        val linkedParent = File(temp, "linked")
        Files.createSymbolicLink(linkedParent.toPath(), real.parentFile.toPath())
        // The folder itself is not a link: only its parent is, which the callers check on their own
        SafeDelete.isLink(File(linkedParent, "Chapter 1").toPath()) shouldBe false
        real.exists() shouldBe true
    }

    @Test
    fun `a Windows junction is still a link, also named in another letter case`() {
        assumeTrue(isWindows, "junctions are Windows only")
        val target = File(temp, "outside").apply { mkdirs() }
        val junction = File(temp, "Junction")
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, target.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        assumeTrue(made == 0 && junction.exists(), "mklink /J failed")

        SafeDelete.isLink(junction.toPath()) shouldBe true
        SafeDelete.isLink(File(temp, "JUNCTION").toPath()) shouldBe true
    }
}
