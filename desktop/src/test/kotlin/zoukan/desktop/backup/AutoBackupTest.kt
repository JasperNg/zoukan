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
}
