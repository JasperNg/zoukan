package zoukan.desktop.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okio.sink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class BackupPermissionsTest {
    @TempDir
    lateinit var temp: File

    private fun posix() = assumeTrue("posix" in temp.toPath().fileSystem.supportedFileAttributeViews())

    private fun permissions(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    /** The steps DesktopBackups.create takes: an owner-only temp file, written, then moved into place. */
    private fun write(file: File, replaceExisting: Boolean) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            DesktopBackups.createOwnerOnly(temp)
            temp.sink().use { it.write(okio.Buffer().writeUtf8("backup"), 6) }
            DesktopBackups.moveIntoPlace(temp, file, replaceExisting)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `a new backup is readable by its owner only`() {
        posix()
        val file = File(temp, "zoukan_2026-01-01_10-00.tachibk")
        write(file, replaceExisting = false)
        permissions(file) shouldBe "rw-------"
        file.readText() shouldBe "backup"
    }

    @Test
    fun `a temp file left by a failed write is replaced, owner only`() {
        posix()
        val temp = File(temp, "b.tachibk.tmp").apply { writeText("half") }
        Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        DesktopBackups.createOwnerOnly(temp)

        permissions(temp) shouldBe "rw-------"
        temp.length() shouldBe 0L
    }

    @Test
    fun `an existing backup, such as the Android app's, keeps its permissions`() {
        posix()
        val android = File(temp, "app.komikku_2026-01-01_10-00.tachibk").apply { writeText("android") }
        Files.setPosixFilePermissions(android.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        shouldThrow<FileAlreadyExistsException> { write(android, replaceExisting = false) }

        permissions(android) shouldBe "rw-r--r--"
        android.readText() shouldBe "android"
    }

    @Test
    fun `without POSIX permissions nothing is created ahead`() {
        assumeTrue("posix" !in temp.toPath().fileSystem.supportedFileAttributeViews())
        val temp = File(temp, "b.tachibk.tmp")
        DesktopBackups.createOwnerOnly(temp)
        temp.exists() shouldBe false
    }
}
