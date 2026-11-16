package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File

class BackupJobsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = BackupExtensionStore("https://example.org/repo.json", "Example", null, "a".repeat(64), "https://example.org", null, true, null)
    private val backup = Backup(backupManga = emptyList(), backupExtensionStores = listOf(store))
    private val file = File("backup.tachibk")

    private fun backups(): DesktopBackups {
        val backups = mockk<DesktopBackups>()
        every { backups.read(any()) } returns backup
        coEvery { backups.newExtensionStores(backup, any()) } returns backup.backupExtensionStores
        return backups
    }

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    @Test
    fun `a cancelled store prompt cancels the restore and changes nothing`() = runBlocking {
        val backups = backups()
        val jobs = BackupJobs(scope, { backups }) { null }

        jobs.restore(file, RestoreOptions())!!.await() shouldBe RestoreOutcome.CANCELLED

        coVerify(exactly = 0) { backups.restore(any(), any(), any(), any(), any()) }
        jobs.status.value shouldBe null
    }

    @Test
    fun `a second restore while one runs is refused`() = runBlocking {
        val backups = backups()
        val asking = CompletableDeferred<Boolean?>()
        val reached = CompletableDeferred<Unit>()
        val jobs = BackupJobs(scope, { backups }) {
            reached.complete(Unit)
            runBlocking { asking.await() }
        }

        val first = jobs.restore(file, RestoreOptions())!!
        reached.await()

        jobs.restore(file, RestoreOptions()) shouldBe null
        (jobs.status.value != null) shouldBe true

        asking.complete(null)
        first.await() shouldBe RestoreOutcome.CANCELLED
        jobs.status.value shouldBe null
    }
}
