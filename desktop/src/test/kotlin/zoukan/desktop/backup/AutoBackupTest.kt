package zoukan.desktop.backup

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.backup.service.BackupPreferences
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException

class AutoBackupTest {

    private fun <T> pref(value: T) = mockk<Preference<T>>(relaxed = true) { every { get() } returns value }

    @Test
    fun `a failed backup is not retried at every check`() = runBlocking<Unit> {
        val storage = mockk<ZoukanStorage> { every { autoBackups } throws IOException("The folder is not writable") }
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns pref(0L)
        }
        val backup = AutoBackup(storage, File("."), preferences)

        backup.isDue() shouldBe true
        backup.run() shouldBe null
        // The background jobs check every minute: no new attempt (and error notification) each time
        val now = System.currentTimeMillis()
        backup.isDue(now + 60_000) shouldBe false
        backup.isDue(now + AutoBackup.RETRY_AFTER + 1) shouldBe true
    }

    @Test
    fun `the automatic backup waits while a restore is in progress`() = runBlocking<Unit> {
        // Strict mock: the skipped backup must not touch storage at all, let alone rotate backups out
        val storage = mockk<ZoukanStorage>()
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns pref(0L)
        }
        val backup = AutoBackup(storage, File("."), preferences)

        RestoreGate.whileRestoring {
            RestoreGate.isRestoring shouldBe true
            backup.run() shouldBe null
        }
        RestoreGate.isRestoring shouldBe false
        // The skip was not an attempt: the next check still finds the backup due
        backup.isDue() shouldBe true
    }

    @Test
    fun `rotation keeps the newest four of Zoukan's backups and never touches the Android app's`(@TempDir folder: File) {
        // Android names them `<applicationId>_yyyy-MM-dd_HH-mm.tachibk` (BackupCreator.getFilename),
        // and "app.komikku_" sorts before "zoukan_": older, newer and same-day ones, and a debug build's
        val android = listOf(
            "app.komikku_2023-12-31_23-59.tachibk",
            "app.komikku_2024-01-01_09-00.tachibk",
            "app.komikku_2024-01-05_12-00.tachibk",
            "app.komikku_2024-03-01_08-30.tachibk",
            "app.komikku_2025-06-01_00-00.tachibk",
            "app.komikku.dev_2024-02-01_10-00.tachibk",
        ).map { File(folder, it).apply { writeText("android") } }
        val zoukan = listOf(
            "zoukan_2024-01-01_10-00.tachibk",
            "zoukan_2024-01-02_10-00.tachibk",
            "zoukan_2024-01-03_10-00.tachibk",
            "zoukan_2024-01-04_10-00.tachibk",
            "zoukan_2024-01-05_10-00.tachibk",
            "zoukan_2024-01-06_10-00.tachibk",
        ).map { File(folder, it).apply { writeText("zoukan") } }

        // Every run rotates; repeating it must not eat into the Android app's backups either
        repeat(3) { AutoBackup.prune(folder) }

        android.forEach { it.exists() shouldBe true }
        zoukan.take(2).forEach { it.exists() shouldBe false }
        zoukan.drop(2).forEach { it.exists() shouldBe true }
    }

    @Test
    fun `the latest backup is the newest by time whichever app made it`(@TempDir folder: File) {
        File(folder, "zoukan_2024-01-06_10-00.tachibk").writeText("zoukan")
        File(folder, "zoukan_2024-01-05_10-00.tachibk").writeText("zoukan")
        val androidNewest = File(folder, "app.komikku_2024-01-07_08-00.tachibk").apply { writeText("android") }
        // Not a backup name, and a name whose time does not parse: neither is picked
        File(folder, "notes.txt").writeText("")
        File(folder, "zoukan_2099-13-45_99-99.tachibk").writeText("")
        val storage = mockk<ZoukanStorage> { every { autoBackups } returns folder }
        val preferences = mockk<BackupPreferences>()

        AutoBackup(storage, File("."), preferences).latest() shouldBe androidNewest

        // An unparseable name is not rotated out either
        AutoBackup.prune(folder)
        File(folder, "zoukan_2099-13-45_99-99.tachibk").exists() shouldBe true
    }

    @Test
    fun `the restore gate clears when a restore fails`() = runBlocking<Unit> {
        runCatching { RestoreGate.whileRestoring<Unit> { throw IOException("The restore failed") } }
        RestoreGate.isRestoring shouldBe false
    }
}
