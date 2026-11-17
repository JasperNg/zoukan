package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
import org.junit.jupiter.api.io.TempDir
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

    // A backup without extension stores, so the restore asks nothing about them
    private val noStores = RestoreOptions(extensionStores = false)

    private fun restorable(): DesktopBackups {
        val backups = mockk<DesktopBackups>()
        val plain = Backup(backupManga = emptyList())
        every { backups.read(any()) } returns plain
        coEvery { backups.restore(any(), any(), any(), any(), any()) } returns emptyList()
        return backups
    }

    private fun snapshot(folder: File, fail: Boolean, order: MutableList<String>? = null) =
        SafetySnapshot(folder = { folder }, write = { file ->
            if (fail) throw java.io.IOException("disk full")
            order?.add("snapshot")
            file.writeText("before")
        })

    @Test
    fun `a restore saves a safety backup of the library first`(@TempDir dir: File) = runBlocking<Unit> {
        val backups = restorable()
        val order = mutableListOf<String>()
        coEvery { backups.restore(any(), any(), any(), any(), any()) } answers {
            order += "restore"
            emptyList()
        }
        val jobs = BackupJobs(scope, { backups }, snapshot = snapshot(dir, fail = false, order)) { true }

        jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.OK

        order shouldBe listOf("snapshot", "restore")
        dir.listFiles()!!.single().name shouldStartWith "safety_restore_"
    }

    @Test
    fun `a failed safety backup cancels the restore when the user says so`(@TempDir dir: File) = runBlocking<Unit> {
        val backups = restorable()
        var asked = false
        val jobs = BackupJobs(scope, { backups }, snapshot = snapshot(dir, fail = true), snapshotPrompt = {
            asked = true
            false
        }) { true }

        jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.CANCELLED

        asked shouldBe true
        coVerify(exactly = 0) { backups.restore(any(), any(), any(), any(), any()) }
        jobs.status.value shouldBe null
    }

    @Test
    fun `a failed safety backup restores anyway when the user continues`(@TempDir dir: File) = runBlocking<Unit> {
        val backups = restorable()
        val jobs = BackupJobs(scope, { backups }, snapshot = snapshot(dir, fail = true), snapshotPrompt = { true }) { true }

        jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.OK

        coVerify(exactly = 1) { backups.restore(any(), any(), any(), any(), any()) }
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
