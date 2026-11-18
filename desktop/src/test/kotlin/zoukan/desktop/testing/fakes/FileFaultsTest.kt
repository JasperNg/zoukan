package zoukan.desktop.testing.fakes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileFaultsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a file in the way stops a folder and anything under it, and deleting it heals`() {
        val folder = File(dir, "downloads/Source")
        val blocker = FileFaults.fileInTheWay(folder)

        folder.mkdirs() shouldBe false
        shouldThrow<IOException> { File(folder, "Manga/001.png").also { it.parentFile.mkdirs() }.writeText("x") }
        shouldThrow<IOException> { Files.createDirectories(File(folder, "Manga").toPath()) }

        blocker.delete() shouldBe true
        File(folder, "Manga").mkdirs() shouldBe true
    }

    @Test
    fun `a read-only folder refuses new files where the OS enforces it, and closing heals`() {
        val folder = File(dir, "backups").apply { mkdirs() }
        File(folder, "old.tachibk").writeText("old")
        val fault = FileFaults.readOnly(folder)
        assumeTrue(fault != null, "This OS or user can write in a read-only folder")
        fault!!.use {
            shouldThrow<IOException> { File(folder, "new.tachibk").writeText("new") }
            File(folder, "old.tachibk").delete() shouldBe false
        }
        File(folder, "new.tachibk").writeText("new")
        folder.list()!!.sorted() shouldBe listOf("new.tachibk", "old.tachibk")
    }
}
