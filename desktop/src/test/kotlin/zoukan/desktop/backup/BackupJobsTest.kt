package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import java.io.File
import java.io.IOException

class BackupJobsTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

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

    @Test
    fun `a restore that ends with a fatal error tells the user it failed`() = runBlocking<Unit> {
        val backups = mockk<DesktopBackups>()
        every { backups.read(any()) } throws InternalError("test")
        val escaped = CompletableDeferred<Throwable>()
        val failing = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> escaped.complete(e) })
        zoukan.desktop.testing.UserMessages().use { messages ->
            val jobs = BackupJobs(failing, { backups }) { true }

            jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.FAILED

            messages.awaitToast { it.startsWith(zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.restore_failed)) }
            // The error still goes on to the crash report
            (escaped.await() is InternalError) shouldBe true
            jobs.status.value shouldBe null
        }
        failing.cancel()
    }

    @Test
    fun `background jobs are not stalled while interactive prompts are open`(@TempDir dir: File) = runBlocking<Unit> {
        Injekt.addSingleton(mockk<GetExtensionStores>(relaxed = true))
        val sensitive = BackupPreference("doh_provider", IntPreferenceValue(-1))
        val richBackup = Backup(
            backupManga = emptyList(),
            backupExtensionStores = listOf(store),
            backupPreferences = listOf(sensitive),
        )
        val backups = mockk<DesktopBackups>()
        every { backups.read(any()) } returns richBackup
        coEvery { backups.newExtensionStores(richBackup, any()) } returns richBackup.backupExtensionStores
        var restoringUnderGate = false
        coEvery { backups.restore(any(), any(), any(), any(), any()) } answers {
            restoringUnderGate = RestoreGate.isRestoring
            emptyList()
        }

        val storeReached = CompletableDeferred<Unit>()
        val storeAnswer = CompletableDeferred<Boolean?>()
        val sensitiveReached = CompletableDeferred<Unit>()
        val sensitiveAnswer = CompletableDeferred<Boolean?>()
        val snapshotReached = CompletableDeferred<Unit>()
        val snapshotAnswer = CompletableDeferred<Boolean>()

        val jobs = BackupJobs(
            scope = scope,
            backups = { backups },
            sensitivePrompt = {
                sensitiveReached.complete(Unit)
                runBlocking { sensitiveAnswer.await() }
            },
            snapshot = snapshot(dir, fail = true),
            snapshotPrompt = {
                snapshotReached.complete(Unit)
                runBlocking { snapshotAnswer.await() }
            },
            storePrompt = {
                storeReached.complete(Unit)
                runBlocking { storeAnswer.await() }
            },
        )

        val outcome = jobs.restore(file, RestoreOptions())!!
        try {
            withTimeout(10_000) { storeReached.await() }
            RestoreGate.unlessRestoring { "ran" } shouldBe "ran"
            storeAnswer.complete(true)

            withTimeout(10_000) { sensitiveReached.await() }
            RestoreGate.unlessRestoring { "ran" } shouldBe "ran"
            sensitiveAnswer.complete(false)

            withTimeout(10_000) { snapshotReached.await() }
            RestoreGate.unlessRestoring { "ran" } shouldBe "ran"
            snapshotAnswer.complete(true)

            withTimeout(10_000) { outcome.await() } shouldBe RestoreOutcome.OK
            restoringUnderGate shouldBe true
            jobs.status.value shouldBe null
        } finally {
            storeAnswer.complete(null)
            sensitiveAnswer.complete(null)
            snapshotAnswer.complete(false)
        }
    }

    @Test
    fun `cancelling a restore while waiting on RestoreGate resets status and allows a subsequent restore`(@TempDir dir: File) = runBlocking<Unit> {
        val backups = restorable()
        val jobs = BackupJobs(scope, { backups }, snapshot = snapshot(dir, fail = false)) { true }

        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            RestoreGate.whileRestoring {
                gateHeld.complete(Unit)
                releaseGate.await()
            }
        }
        try {
            withTimeout(10_000) { gateHeld.await() }

            val first = jobs.restore(file, noStores)!!
            Await.untilSuspending("restore to wait on RestoreGate") {
                RestoreGate.pending.get() >= 2
            }

            scope.coroutineContext[Job]!!.cancelChildren()
            withTimeout(10_000) { first.await() }
            jobs.status.value shouldBe null

            releaseGate.complete(Unit)
            withTimeout(10_000) { holder.join() }

            val second = jobs.restore(file, noStores)!!
            withTimeout(10_000) { second.await() } shouldBe RestoreOutcome.OK
            jobs.status.value shouldBe null
        } finally {
            releaseGate.complete(Unit)
        }
    }

    @Test
    fun `an exception from backups read resets status and completes with FAILED`() = runBlocking<Unit> {
        val backups = mockk<DesktopBackups>()
        every { backups.read(any()) } throws IOException("corrupt")
        zoukan.desktop.testing.UserMessages().use { messages ->
            val jobs = BackupJobs(scope, { backups }) { true }

            jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.FAILED

            messages.awaitToast { it.startsWith(zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.restore_failed)) }
            jobs.status.value shouldBe null
            RestoreGate.isRestoring shouldBe false
        }
    }
}
