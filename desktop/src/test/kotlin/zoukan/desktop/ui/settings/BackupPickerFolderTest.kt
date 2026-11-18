package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Where the restore picker opens: the app's backups folder, or the storage folder's automatic backups. */
class BackupPickerFolderTest {

    @TempDir
    lateinit var temp: File

    private val manual get() = File(temp, "data/backups")
    private val automatic get() = File(temp, "storage/autobackup")

    private fun backup(dir: File, name: String) = File(dir.apply { mkdirs() }, name).writeBytes(byteArrayOf(1))

    @Test
    fun `an Android storage folder's automatic backups are offered when the app has made none`() {
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }

    @Test
    fun `the app's own backups folder is kept when it holds backups`() {
        backup(manual, "zoukan_2026-01-02_03-04.tachibk")
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe manual
    }

    @Test
    fun `the app's backups folder stays the start when there are no automatic backups`() {
        backupPickerFolder(manual, automatic) shouldBe manual
        // Other files, or a folder named like a backup, are no backups
        backup(automatic, "notes.txt")
        File(automatic, "old.tachibk").mkdirs()
        backupPickerFolder(manual, automatic) shouldBe manual
        // A missing storage folder (a disconnected drive)
        backupPickerFolder(manual, null) shouldBe manual
    }

    @Test
    fun `old proto backups count as backups`() {
        backup(automatic, "tachiyomi_2020-01-02_03-04.proto.gz")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }
}
