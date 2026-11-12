package zoukan.desktop.backup

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
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
    fun `the restore gate clears when a restore fails`() {
        runCatching { RestoreGate.whileRestoring<Unit> { throw IOException("The restore failed") } }
        RestoreGate.isRestoring shouldBe false
    }
}
