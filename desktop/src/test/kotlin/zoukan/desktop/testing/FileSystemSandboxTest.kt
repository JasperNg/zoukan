package zoukan.desktop.testing

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserDefinedFileAttributeView

/** The sandbox's own checks: it sees every kind of change outside the app's folders, and only those. */
class FileSystemSandboxTest {
    @TempDir
    lateinit var temp: File

    private val sandbox by lazy { FileSystemSandbox.create(temp) }

    @AfterEach
    fun close() = sandbox.close()

    private fun failure(): String = assertThrows<IllegalStateException> { sandbox.assertNothingOutsideAppChanged() }.message!!

    private fun posix() = "posix" in sandbox.home.toPath().fileSystem.supportedFileAttributeViews()

    @Test
    fun `a new sandbox has every kind of file and reports no change`() {
        sandbox.dataFolder.isDirectory shouldBe true
        sandbox.storageZoukanFolders.forEach { it.isDirectory shouldBe true }
        File(sandbox.userFolder, "notes.txt").isFile shouldBe true
        File(sandbox.downloads, "invoice.pdf").isFile shouldBe true
        sandbox.otherProgramCrashFile.readText() shouldContain "org.example.OtherApp"
        File(sandbox.readOnlyFolder, FileSystemSandbox.READ_ONLY_FILE).isFile shouldBe true
        sandbox.linkFromApp?.let { Files.readSymbolicLink(it.toPath()).toFile() shouldBe sandbox.linkTarget }
        sandbox.home.startsWith(temp.canonicalFile) shouldBe true
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `the read-only folder cannot be written`() {
        assumeTrue(posix())
        // A user that ignores permissions (root) would see no difference
        assumeTrue(System.getProperty("user.name") != "root")
        sandbox.readOnlyFolder.canWrite() shouldBe false
        runCatching { File(sandbox.readOnlyFolder, "new").createNewFile() }.getOrDefault(false) shouldBe false
        File(sandbox.readOnlyFolder, FileSystemSandbox.READ_ONLY_FILE).canWrite() shouldBe false
    }

    @Test
    fun `changes in the app's folders are allowed`() {
        File(sandbox.dataFolder, "zoukan.db").writeText("changed")
        File(sandbox.dataFolder, "logs/new.log").apply { parentFile.mkdirs() }.writeText("log")
        File(sandbox.storageFolder, "downloads/Source/Manga/Chapter.cbz").delete()
        File(sandbox.storageFolder, "autobackup/backup.tachibk").writeText("backup")
        sandbox.linkFromApp?.delete()
        if (posix()) Files.setPosixFilePermissions(sandbox.dataFolder.toPath(), PosixFilePermissions.fromString("rwx------"))

        sandbox.changesOutsideApp().shouldBeEmpty()
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `a changed, deleted or new file outside the app's folders fails with each path listed`() {
        File(sandbox.userFolder, "notes.txt").writeText("overwritten")
        sandbox.otherProgramCrashFile.delete()
        File(sandbox.downloads, "Zoukan").mkdirs()

        val message = failure()
        message shouldContain "changed content: Documents/Chosen/notes.txt"
        message shouldContain "deleted file tmp/hs_err_pid${FileSystemSandbox.OTHER_PID}.log"
        message shouldContain "created folder Downloads/Zoukan"
        sandbox.changesOutsideApp().size shouldBe 3
    }

    @Test
    fun `a new file in the storage folder itself fails, as it is not the app's`() {
        // Not a name of the app's folders in any case, so not one of them even on a case-insensitive volume
        File(sandbox.storageFolder, "Photos").mkdirs()
        failure() shouldContain "created folder Documents/Zoukan/Photos"
    }

    @Test
    fun `following the app's link out of its folder fails`() {
        val link = sandbox.linkFromApp
        assumeTrue(link != null, "symbolic links cannot be made here")
        File(link, "keep.txt").delete()
        File(link, "written-through-link").writeText("x")

        val message = failure()
        message shouldContain "deleted file Documents/Chosen/linked-from-app/keep.txt"
        message shouldContain "created file Documents/Chosen/linked-from-app/written-through-link"
    }

    @Test
    fun `a permission change outside the app's folders fails`() {
        if (posix()) {
            Files.setPosixFilePermissions(sandbox.userFolder.toPath(), PosixFilePermissions.fromString("rwx------"))
            failure() shouldContain "-> rwx------: Documents/Chosen"
        } else {
            File(sandbox.userFolder, "notes.txt").setReadOnly() shouldBe true
            failure() shouldContain "readOnly=true"
        }
    }

    @Test
    fun `a write that makes the read-only folder writable fails`() {
        assumeTrue(posix())
        Files.setPosixFilePermissions(sandbox.readOnlyFolder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        failure() shouldContain "permissions r-xr-xr-x -> rwxr-xr-x: read-only"
    }

    @Test
    fun `an extended attribute added, changed or removed outside the app's folders fails`() {
        assumeTrue(sandbox.xattrsSupported, "no extended attributes on this file system")
        val view = Files.getFileAttributeView(sandbox.userFolder.toPath(), UserDefinedFileAttributeView::class.java)
        view.write(ATTRIBUTE, ByteBuffer.wrap("one".toByteArray()))
        failure() shouldContain "$ATTRIBUTE added: Documents/Chosen"

        sandbox.record()
        view.write(ATTRIBUTE, ByteBuffer.wrap("two".toByteArray()))
        failure() shouldContain "$ATTRIBUTE changed: Documents/Chosen"

        sandbox.record()
        view.delete(ATTRIBUTE)
        failure() shouldContain "$ATTRIBUTE removed: Documents/Chosen"

        // In the app's folders it is allowed
        sandbox.record()
        Files.getFileAttributeView(sandbox.dataFolder.toPath(), UserDefinedFileAttributeView::class.java)
            .write(ATTRIBUTE, ByteBuffer.wrap("ok".toByteArray()))
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `the Time Machine mark as tmutil sets it is seen on macOS`() {
        val xattr = File("/usr/bin/xattr")
        assumeTrue(xattr.canExecute() && sandbox.xattrsSupported)
        val process = ProcessBuilder(xattr.path, "-w", "com.apple.metadata:com_apple_backup_excludeItem", "com.apple.backupd", sandbox.downloads.path)
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() shouldBe 0
        failure() shouldContain "extended attribute com.apple.metadata:com_apple_backup_excludeItem added: Downloads"
    }

    @Test
    fun `a link outside the app's folders that points elsewhere fails`() {
        val link = sandbox.link(File(sandbox.userFolder, "shortcut"), sandbox.downloads)
        assumeTrue(link != null, "symbolic links cannot be made here")
        sandbox.record()
        link!!.delete()
        sandbox.link(link, sandbox.otherProgramFolder)

        failure() shouldContain "link target ${sandbox.downloads.path} -> ${sandbox.otherProgramFolder.path}: Documents/Chosen/shortcut"
    }

    @Test
    fun `a folder added as the app's may change`() {
        val extra = File(sandbox.userFolder, "photos")
        sandbox.addAppFolder(extra)
        File(extra, "holiday.jpg").delete()
        sandbox.assertNothingOutsideAppChanged()
        assertThrows<IllegalArgumentException> { sandbox.addAppFolder(temp.parentFile) }
    }

    @Test
    fun `closing deletes the sandbox, read-only folder too, and never what a link points to`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        val kept = File(outside, "kept.txt").apply { writeText("kept") }
        val link = sandbox.link(File(sandbox.dataFolder, "to-outside"), outside)
        val home = sandbox.home

        sandbox.close()

        home.exists() shouldBe false
        kept.readText() shouldBe "kept"
        if (link != null) outside.list()!!.toList() shouldContain "kept.txt"
    }

    private companion object {
        /** A name the JDK accepts on every system (it adds the `user.` namespace on Linux and macOS). */
        const val ATTRIBUTE = "zoukan.test"
    }
}
