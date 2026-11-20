package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UserMessages
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.restore_failed
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
            if (fail) throw IOException("disk full")
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
        val failing = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped.complete(e) })
        UserMessages().use { messages ->
            val jobs = BackupJobs(failing, { backups }) { true }

            jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.FAILED

            messages.awaitToast { it.startsWith(localized(DMR.strings.restore_failed)) }
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
        UserMessages().use { messages ->
            val jobs = BackupJobs(scope, { backups }) { true }

            jobs.restore(file, noStores)!!.await() shouldBe RestoreOutcome.FAILED

            messages.awaitToast { it.startsWith(localized(DMR.strings.restore_failed)) }
            jobs.status.value shouldBe null
            RestoreGate.isRestoring shouldBe false
        }
    }
}

/** Restores run alone, and the work that reads the library never overlaps one. */
class RestoreGateTest {

    @Test
    fun `two restores never run at the same time`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val most = AtomicInteger()
        withTimeout(10_000) {
            (1..4).map {
                async(Dispatchers.Default) {
                    RestoreGate.whileRestoring {
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(50)
                        // Still up while another restore waits its turn
                        RestoreGate.isRestoring shouldBe true
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        most.get() shouldBe 1
        RestoreGate.isRestoring shouldBe false
    }

    @Test
    fun `background work does not start while a restore runs`() = runBlocking<Unit> {
        RestoreGate.whileRestoring {
            RestoreGate.unlessRestoring { "ran" } shouldBe null
        }
        RestoreGate.unlessRestoring { "ran" } shouldBe "ran"
    }

    @Test
    fun `a restore waits for background work that already started`() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restored = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.Default) {
            RestoreGate.unlessRestoring {
                inside.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { inside.await() }
        val restore = launch(Dispatchers.Default) { RestoreGate.whileRestoring { restored.complete(Unit) } }
        // The restore has taken every permit but the worker's and waits for that one
        Await.untilSuspending("the restore to wait for the work under way") {
            RestoreGate.restores.isLocked && RestoreGate.readers.availablePermits == 0
        }
        // Asked for, so no new work starts, but it waits for the work under way
        RestoreGate.isRestoring shouldBe true
        restored.isCompleted shouldBe false
        RestoreGate.unlessRestoring { "ran" } shouldBe null

        release.complete(Unit)
        withTimeout(10_000) {
            worker.join()
            restore.join()
        }
        restored.isCompleted shouldBe true
        RestoreGate.isRestoring shouldBe false
    }
}

/**
 * A backup restore runs its restorers side by side. Each must write through the database handler,
 * whose single thread holds the one connection: a write from another thread uses that thread's own
 * connection, and a restore transaction that already read then fails its next write with
 * SQLITE_BUSY.
 */
class ConcurrentRestoreTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `restoring extension stores during another restore transaction does not fail it`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        // A single thread, as Executors.newSingleThreadExecutor, whose queue the test can see
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
            Thread(it, "zoukan-database").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        try {
            val read = CompletableFuture<Unit>()
            val write = CompletableFuture<Unit>()
            // Like SavedSearchRestorer: read in a transaction, then write
            val transaction = async {
                handler.await(true) {
                    handler.awaitList { saved_searchQueries.selectAll() }
                    read.complete(Unit)
                    write.get(10, TimeUnit.SECONDS)
                    saved_searchQueries.insert(1, "name", "query", null)
                }
            }
            withTimeout(10_000) { read.await() }
            val store = async {
                ExtensionStoreRestorer(handler)(
                    BackupExtensionStore(
                        indexUrl = "https://example.org/repo.json",
                        name = "Store",
                        badgeLabel = null,
                        signingKey = "key",
                        contactWebsite = "https://example.org",
                        contactDiscord = null,
                        isLegacy = false,
                        extensionListUrl = null,
                    ),
                )
            }
            // The store's write is either queued behind the transaction on the database thread, or
            // (written directly from another thread) done inside the transaction
            Await.untilSuspending("the store's write to be queued or done") {
                executor.queue.isNotEmpty() || store.isCompleted
            }
            write.complete(Unit)

            withTimeout(10_000) {
                transaction.await()
                store.await()
            }
            handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe 1
            handler.awaitList { extension_storeQueries.getAll() }.size shouldBe 1
        } finally {
            driver.close()
            executor.shutdownNow()
        }
    }
}
