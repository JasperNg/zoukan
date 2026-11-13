package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import org.junit.jupiter.api.Test
import java.io.IOException

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
}
