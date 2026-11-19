package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okio.Buffer
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.buffer
import okio.gzip
import okio.sink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.runToolsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.random.Random

class BackupCodecTest {

    @Test
    fun `reads gzipped and plain protobuf`() {
        val backup = SyntheticBackup.create()
        val gzipped = Buffer().also { BackupCodec.write(backup, it) }
        gzipped.peek().readShort().toInt() shouldBe 0x1f8b
        BackupCodec.decode(gzipped).backupManga.size shouldBe backup.backupManga.size

        val plain = Buffer().write(BackupCodec.encode(backup))
        BackupCodec.decode(plain).backupManga.size shouldBe backup.backupManga.size
    }

    @Test
    fun `rejects JSON backups`() {
        val e = shouldThrow<BackupCodec.InvalidBackupException> {
            BackupCodec.decode(Buffer().writeUtf8("""{"version":2,"mangas":[]}"""))
        }
        e.error shouldBe BackupCodec.Error.JSON
    }

    @Test
    fun `rejects other files`() {
        val e = shouldThrow<BackupCodec.InvalidBackupException> {
            BackupCodec.decode(Buffer().write(byteArrayOf(0x50, 0x4b, 3, 4, 0x7f, 0x7f, 0x7f, 0x7f)))
        }
        e.error shouldBe BackupCodec.Error.UNKNOWN
    }

    @Test
    fun `an empty file is an error, not an empty backup`() {
        shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(Buffer()) }.error shouldBe BackupCodec.Error.UNKNOWN
        shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(Buffer().writeByte(1)) }
    }

    @Test
    fun `a cut-off backup is an invalid backup`() {
        val gzipped = Buffer().also { BackupCodec.write(SyntheticBackup.create(), it) }.readByteArray()
        shouldThrow<BackupCodec.InvalidBackupException> {
            BackupCodec.decode(Buffer().write(gzipped.copyOf(gzipped.size / 2)))
        }
    }

    @Test
    fun `a read error is passed through, not reported as an invalid backup`() {
        val gzipped = Buffer().also { BackupCodec.write(SyntheticBackup.create(), it) }.readByteArray()
        val readError = IOException("disk gone")
        // Fails after the header, while gzip is reading the body
        val failing: Source = object : ForwardingSource(Buffer().write(gzipped.copyOf(16))) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read == -1L) throw readError
                return read
            }
        }
        val e = shouldThrow<IOException> { BackupCodec.decode(failing) }
        (e is BackupCodec.InvalidBackupException) shouldBe false
        e shouldBe readError

        val failingAtOnce = object : Source by Buffer() {
            override fun read(sink: Buffer, byteCount: Long): Long = throw readError
        }
        shouldThrow<IOException> { BackupCodec.decode(failingAtOnce) } shouldBe readError
    }

    /** Valid protobuf of an unknown field (number 99) repeated: it decodes as an empty backup, so only the cap can refuse it. */
    private fun padding(size: Int) = ByteArray(size) { byteArrayOf(0x98.toByte(), 0x06, 0x01)[it % 3] }

    @Test
    fun `a gzip stream that inflates past the cap is refused as invalid`() {
        val bomb = Buffer()
        (bomb as Sink).gzip().buffer().use { it.write(padding(64 * 1023)) }
        shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(bomb, maxBytes = 1024) }
            .error shouldBe BackupCodec.Error.UNKNOWN
    }

    @Test
    fun `a plain stream past the cap is refused as invalid`() {
        val plain = Buffer().write(padding(4095))
        shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(plain, maxBytes = 1024) }
            .error shouldBe BackupCodec.Error.UNKNOWN
    }

    @Test
    fun `a sync reply past the cap is refused, one at the cap is read`() {
        shouldThrow<BackupCodec.InvalidBackupException> {
            BackupCodec.readBounded(ByteArray(1025).inputStream(), maxBytes = 1024)
        }.error shouldBe BackupCodec.Error.UNKNOWN
        BackupCodec.readBounded(ByteArray(1024) { it.toByte() }.inputStream(), maxBytes = 1024) shouldBe
            ByteArray(1024) { it.toByte() }
    }

    @Test
    fun `a backup under the cap still decodes`() {
        val backup = SyntheticBackup.create()
        val gzipped = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(gzipped).backupManga.size shouldBe backup.backupManga.size
    }
}

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
            temp.sink().use { it.write(Buffer().writeUtf8("backup"), 6) }
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

/** `backup synthetic` never replaces an existing file unless asked, and never through a half-written file. */
class BackupCommandTest {

    @TempDir
    lateinit var dir: File

    private fun run(vararg args: String): Pair<Int, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            return runToolsCommand(arrayOf(*args)) to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    private fun existingBackup(): File = File(dir, "real.tachibk").apply { writeBytes(Random(7).nextBytes(64 * 1024)) }

    private fun decodes(file: File) {
        DesktopBackups(File("."), stubSourceRepository = NoStubSources).read(file)
    }

    @Test
    fun `synthetic refuses an existing file without force`() {
        val file = existingBackup()
        val original = file.readBytes()
        val modified = file.lastModified()

        val (code, err) = run("backup", "synthetic", file.path)

        code shouldBe 1
        err shouldContain file.path
        err shouldContain "--force"
        file.readBytes().contentEquals(original) shouldBe true
        sha256(file.readBytes()) shouldBe sha256(original)
        file.length() shouldBe original.size.toLong()
        file.lastModified() shouldBe modified
        File(dir, "real.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic with force replaces the file through a temporary file`() {
        val file = existingBackup()

        val (code, _) = run("backup", "synthetic", file.path, "--force")

        code shouldBe 0
        decodes(file)
        File(dir, "real.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic writes a new file`() {
        val file = File(dir, "new.tachibk")

        val (code, _) = run("backup", "synthetic", file.path)

        code shouldBe 0
        decodes(file)
        File(dir, "new.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic leaves a file named like its temporary file alone`() {
        val bystander = File(dir, "new.tachibk.tmp").apply { writeBytes(Random(11).nextBytes(4 * 1024)) }
        val original = bystander.readBytes()

        val (code, _) = run("backup", "synthetic", File(dir, "new.tachibk").path)

        code shouldBe 0
        bystander.exists() shouldBe true
        sha256(bystander.readBytes()) shouldBe sha256(original)
        dir.list()!!.sorted() shouldBe listOf("new.tachibk", "new.tachibk.tmp")
    }
}
