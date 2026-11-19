package zoukan.desktop.app

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.network.PersistentCookieStore
import zoukan.desktop.ui.reader.ProgressWriter
import java.io.File
import java.io.IOException
import java.net.HttpCookie
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The window's start, away and back events (AppLifecycle) with the real SyncManager: sync on start
 * and on resume, as Android's App.onCreate and App.onStart, and the app lock's background and
 * foreground. A sync that starts reads the database first, so the handler's calls count the syncs.
 */
class AppLifecycleTest {
    // Not @TempDir: the preferences file is written in the background and may outlive the test
    private val root = Files.createTempDirectory("zoukan-lifecycle").toFile()
    private val preferences by lazy {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        SyncPreferences(DesktopPreferenceStore(JavaSharedPreferences("lifecycle_test_${System.nanoTime()}")))
    }
    private val handler = mockk<DatabaseHandler>()
    private val sync by lazy {
        SyncManager(
            context = mockk<Application>(relaxed = true),
            handler = handler,
            syncPreferences = preferences,
            getCategories = mockk(),
            dirs = ZoukanDirs(root),
        )
    }
    private val lockEvents = mutableListOf<String>()
    private val lifecycle by lazy {
        AppLifecycle(sync::syncOn, onBackground = { lockEvents += "background" }, onForeground = { lockEvents += "foreground" })
    }

    private fun setUp(service: SyncManager.SyncService, triggers: SyncTriggerOptions) {
        // A server the sync may reach: an empty address is refused before the database is read
        preferences.webDavUrl().set("https://dav.example.com")
        preferences.syncService().set(service.value)
        preferences.setSyncTriggerOptions(triggers)
    }

    /** The coroutines on AppScope (where SyncManager.syncOn starts a sync) that this test has seen already. */
    private val seen = AppScope.coroutineContext.job.children.toMutableSet()

    /**
     * Waits until every coroutine started on AppScope since the last check has ended. syncOn
     * decides and launches before it returns, so a sync that should not start has by then either
     * read the database or not started at all.
     */
    private fun awaitStartedSyncs() {
        val started = AppScope.coroutineContext.job.children.filter { it !in seen }.toList()
        seen += started
        runBlocking { withTimeout(10_000) { started.joinAll() } }
    }

    private fun syncsStarted(count: Int) {
        if (count == 0) {
            awaitStartedSyncs()
            coVerify(exactly = 0) { handler.await<Any>(any(), any()) }
        } else {
            coVerify(timeout = 10_000, exactly = count) { handler.await<Any>(any(), any()) }
            // A sync that is still running is not waited for by a later check
            seen += AppScope.coroutineContext.job.children
        }
    }

    @Test
    fun `the window opening syncs only with a service set up and sync on start on`() {
        coEvery { handler.await<Any>(any(), any()) } throws IOException("offline")
        // No service: nothing, though the trigger is on
        setUp(SyncManager.SyncService.NONE, SyncTriggerOptions(syncOnAppStart = true))
        lifecycle.onStart()
        // A service set up, but only sync on resume is on
        setUp(SyncManager.SyncService.WEB_DAV, SyncTriggerOptions(syncOnAppResume = true))
        lifecycle.onStart()
        syncsStarted(0)

        setUp(SyncManager.SyncService.WEB_DAV, SyncTriggerOptions(syncOnAppStart = true))
        lifecycle.onStart()
        syncsStarted(1)
    }

    @Test
    fun `only a window that was hidden syncs when it is shown, and the lock follows every away and back`() {
        coEvery { handler.await<Any>(any(), any()) } throws IOException("offline")
        setUp(SyncManager.SyncService.WEB_DAV, SyncTriggerOptions(syncOnAppResume = true))

        // The window opens shown and in front: no resume
        lifecycle.onAwayChanged(false)
        lifecycle.onHiddenChanged(false)
        // Another app in front and back: the lock hears it, the sync does not
        lifecycle.onAwayChanged(true)
        lifecycle.onAwayChanged(false)
        syncsStarted(0)
        lockEvents shouldBe listOf("foreground", "background", "foreground")

        // Minimized (or in the tray), then shown again: a resume
        lifecycle.onAwayChanged(true)
        lifecycle.onHiddenChanged(true)
        lifecycle.onHiddenChanged(false)
        lifecycle.onAwayChanged(false)
        syncsStarted(1)
        lockEvents shouldBe listOf("foreground", "background", "foreground", "background", "foreground")
    }

    @Test
    fun `a resume with sync on resume off, or no service, syncs nothing`() {
        coEvery { handler.await<Any>(any(), any()) } throws IOException("offline")
        setUp(SyncManager.SyncService.WEB_DAV, SyncTriggerOptions(syncOnAppStart = true))
        lifecycle.onHiddenChanged(true)
        lifecycle.onHiddenChanged(false)
        setUp(SyncManager.SyncService.NONE, SyncTriggerOptions(syncOnAppResume = true))
        lifecycle.onHiddenChanged(true)
        lifecycle.onHiddenChanged(false)
        syncsStarted(0)
    }

    @Test
    fun `a start or resume while a sync runs starts no second one, as Android's SyncDataJob startNow`() {
        val release = CompletableDeferred<Unit>()
        coEvery { handler.await<Any>(any(), any()) } coAnswers {
            release.await()
            throw IOException("offline")
        }
        setUp(SyncManager.SyncService.WEB_DAV, SyncTriggerOptions(syncOnAppStart = true, syncOnAppResume = true))
        lifecycle.onStart()
        syncsStarted(1)
        // Hidden and shown again while the first sync waits on the server
        lifecycle.onHiddenChanged(true)
        lifecycle.onHiddenChanged(false)
        lifecycle.onStart()
        // The start and the resume each launched a sync that found the first one running
        awaitStartedSyncs()
        syncsStarted(1)
        release.complete(Unit)
        runBlocking { withTimeoutOrNull(10_000) { sync.running.first { !it } } }
        sync.running.value shouldBe false
        syncsStarted(1)
    }
}

class ZoukanLifecycleTest {

    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun tearDown() {
        ZoukanLifecycle.reset()
    }

    @Test
    fun `registers at most one JVM shutdown hook across multiple registrations and resets`() {
        val registeredHooks = mutableListOf<Thread>()
        val coordinator = LifecycleCoordinator(registerJvmHook = { registeredHooks += it })

        coordinator.hasRegisteredJvmHook() shouldBe false
        registeredHooks.size shouldBe 0

        val first = coordinator.registerShutdownHook("first") {}
        val second = coordinator.registerShutdownHook("second") {}
        coordinator.hasRegisteredJvmHook() shouldBe true
        registeredHooks.size shouldBe 1

        first.close()
        second.close()
        coordinator.reset()
        coordinator.registerShutdownHook("third") {}

        registeredHooks.size shouldBe 1
        val hookRan = AtomicInteger(0)
        coordinator.registerShutdownHook("fourth") { hookRan.incrementAndGet() }
        registeredHooks.single().run()
        hookRan.get() shouldBe 1
        coordinator.isShutDown shouldBe true
    }

    @Test
    fun `handles IllegalStateException when JVM shutdown is already in progress`() {
        val attempts = AtomicInteger(0)
        val coordinator = LifecycleCoordinator(
            registerJvmHook = {
                attempts.incrementAndGet()
                throw IllegalStateException("Shutdown in progress")
            },
        )

        val ran = AtomicInteger(0)
        val handle = coordinator.registerShutdownHook("late") { ran.incrementAndGet() }
        attempts.get() shouldBe 1
        coordinator.hasRegisteredJvmHook() shouldBe false
        coordinator.activeHookCount() shouldBe 1

        coordinator.shutdown()
        ran.get() shouldBe 1
        handle.close()
    }

    @Test
    fun `executes shutdown hooks in reverse registration order and only once on repeated shutdown or close`() {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val order = mutableListOf<String>()

        coordinator.registerShutdownHook("first") { order += "first" }
        coordinator.registerShutdownHook("second") { order += "second" }
        coordinator.registerShutdownHook("third") { order += "third" }
        coordinator.activeHookCount() shouldBe 3

        coordinator.shutdown()
        coordinator.shutdown()
        coordinator.close()

        order shouldContainExactly listOf("third", "second", "first")
        coordinator.activeHookCount() shouldBe 0
        coordinator.isShutDown shouldBe true
    }

    @Test
    fun `concurrent shutdown calls execute each hook at most once`() {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val executions = AtomicInteger(0)
        coordinator.registerShutdownHook("counted") { executions.incrementAndGet() }

        val threadsCount = 4
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadsCount)
        val threads = List(threadsCount) { i ->
            Thread({
                startGate.await()
                try {
                    coordinator.shutdown()
                } finally {
                    doneGate.countDown()
                }
            }, "shutdown-race-$i").also { it.start() }
        }

        startGate.countDown()
        doneGate.await(10, TimeUnit.SECONDS) shouldBe true
        threads.forEach { it.join(5_000) }
        executions.get() shouldBe 1
    }

    @Test
    fun `isolates exceptions thrown by shutdown hooks so remaining hooks still execute`() {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val order = mutableListOf<String>()

        coordinator.registerShutdownHook("clean-exit") { order += "clean-exit" }
        coordinator.registerShutdownHook("failing-runtime") {
            order += "failing-runtime"
            error("simulated hook failure")
        }
        coordinator.registerShutdownHook("failing-assertion") {
            order += "failing-assertion"
            throw AssertionError("non-fatal error in hook")
        }
        coordinator.registerShutdownHook("cookie-flush") { order += "cookie-flush" }

        coordinator.shutdown()

        order shouldContainExactly listOf(
            "cookie-flush",
            "failing-assertion",
            "failing-runtime",
            "clean-exit",
        )
        coordinator.isShutDown shouldBe true
        coordinator.serviceScope.isActive shouldBe false
        coordinator.appScope.isActive shouldBe false
    }

    @Test
    fun `unregistered hooks do not run and handle close is idempotent even during shutdown`() {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val ran = mutableListOf<String>()

        val cancelledBefore = coordinator.registerShutdownHook("cancelled-before") { ran += "cancelled-before" }
        lateinit var cancelledDuring: AutoCloseable
        cancelledDuring = coordinator.registerShutdownHook("cancelled-during") { ran += "cancelled-during" }
        coordinator.registerShutdownHook("canceller") {
            ran += "canceller"
            cancelledDuring.close()
        }

        cancelledBefore.close()
        cancelledBefore.close()
        coordinator.activeHookCount() shouldBe 2

        coordinator.shutdown()

        ran shouldContainExactly listOf("canceller")
        cancelledDuring.close()
        coordinator.activeHookCount() shouldBe 0
    }

    @Test
    fun `registering a hook after shutdown returns a no-op handle and does not retain it`() {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        coordinator.shutdown()

        val ran = AtomicInteger(0)
        val handle = coordinator.registerShutdownHook("after-shutdown") { ran.incrementAndGet() }
        coordinator.activeHookCount() shouldBe 0
        handle.close()
        ran.get() shouldBe 0
    }

    @Test
    fun `shutdown cancels appScope, serviceScope, and all child scopes while keeping service children off appScope`() =
        runBlocking<Unit> {
            val coordinator = LifecycleCoordinator(registerJvmHook = {})
            val appChild = coordinator.newAppScope(name = "app-child")
            val serviceChild = coordinator.newServiceScope(
                name = "service-child",
                context = Dispatchers.Default,
            )

            // Service children live under serviceScope so AppShutdown does not block on them.
            coordinator.appScope.coroutineContext.job.children.toList() shouldBe listOf(appChild.coroutineContext.job)
            coordinator.serviceScope.coroutineContext.job.children.toList() shouldBe listOf(serviceChild.coroutineContext.job)

            val started = CountDownLatch(2)
            val appCancelled = CompletableDeferred<Unit>()
            val serviceCancelled = CompletableDeferred<Unit>()

            val appJob = appChild.launch {
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    appCancelled.complete(Unit)
                }
            }
            val serviceJob = serviceChild.launch {
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    serviceCancelled.complete(Unit)
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBe true

            coordinator.shutdown()

            withTimeout(10_000) {
                appCancelled.await()
                serviceCancelled.await()
                appJob.join()
                serviceJob.join()
            }
            appChild.isActive shouldBe false
            serviceChild.isActive shouldBe false
            coordinator.appScope.isActive shouldBe false
            coordinator.serviceScope.isActive shouldBe false
        }

    @Test
    fun `child scope cancellation and sibling exception isolation do not cancel parent scopes`() = runBlocking<Unit> {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val failingChild = coordinator.newServiceScope(
            name = "isolated-child",
            context = CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        val siblingChild = coordinator.newServiceScope(name = "sibling-child")

        val failureHandled = CountDownLatch(1)
        failingChild.launch {
            try {
                throw IllegalStateException("boom")
            } finally {
                failureHandled.countDown()
            }
        }.join()
        failureHandled.await(10, TimeUnit.SECONDS) shouldBe true

        val siblingRan = CompletableDeferred<Unit>()
        siblingChild.launch { siblingRan.complete(Unit) }.join()
        withTimeout(10_000) { siblingRan.await() }

        uncaught.size shouldBe 1
        failingChild.isActive shouldBe true
        siblingChild.isActive shouldBe true
        coordinator.serviceScope.isActive shouldBe true

        failingChild.cancel()
        failingChild.isActive shouldBe false
        siblingChild.isActive shouldBe true
        coordinator.serviceScope.isActive shouldBe true

        shouldThrow<IllegalArgumentException> {
            coordinator.newServiceScope(context = Job())
        }
    }

    @Test
    fun `reset cancels previous scopes, clears hooks, and exposes fresh active scopes`() = runBlocking<Unit> {
        val coordinator = LifecycleCoordinator(registerJvmHook = {})
        val oldApp = coordinator.appScope
        val oldService = coordinator.serviceScope
        val staleHookRan = AtomicInteger(0)
        coordinator.registerShutdownHook("stale") { staleHookRan.incrementAndGet() }

        val started = CountDownLatch(1)
        val cancelled = CompletableDeferred<Unit>()
        oldService.launch {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        started.await(10, TimeUnit.SECONDS) shouldBe true

        coordinator.reset()

        withTimeout(10_000) { cancelled.await() }
        oldApp.isActive shouldBe false
        oldService.isActive shouldBe false
        coordinator.isShutDown shouldBe false
        coordinator.activeHookCount() shouldBe 0
        coordinator.appScope shouldNotBeSameInstanceAs oldApp
        coordinator.serviceScope shouldNotBeSameInstanceAs oldService
        coordinator.appScope.isActive shouldBe true
        coordinator.serviceScope.isActive shouldBe true

        coordinator.shutdown()
        staleHookRan.get() shouldBe 0
    }

    @Test
    fun `AppScope and ServiceScope delegate to ZoukanLifecycle and refresh across reset`() {
        val initialApp = AppScope
        val initialService = ServiceScope
        initialApp shouldBeSameInstanceAs ZoukanLifecycle.appScope
        initialService shouldBeSameInstanceAs ZoukanLifecycle.serviceScope

        ZoukanLifecycle.reset()

        AppScope shouldNotBeSameInstanceAs initialApp
        ServiceScope shouldNotBeSameInstanceAs initialService
        AppScope shouldBeSameInstanceAs ZoukanLifecycle.appScope
        ServiceScope shouldBeSameInstanceAs ZoukanLifecycle.serviceScope
        AppScope.isActive shouldBe true
        ServiceScope.isActive shouldBe true
    }

    @Test
    fun `PersistentCookieStore flushes via ZoukanLifecycle shutdown and unregisters on close`() {
        val openFile = File(tempDir, "open/cookies.json")
        val openStore = PersistentCookieStore(openFile, saveDelayMillis = 60_000)
        val closedFile = File(tempDir, "closed/cookies.json")
        val closedStore = PersistentCookieStore(closedFile, saveDelayMillis = 60_000)

        val cookie = HttpCookie("session", "abc").apply {
            maxAge = 3600
            path = "/"
            version = 0
        }
        try {
            openStore.add(URI("https://example.org/"), cookie)
            closedStore.add(URI("https://example.org/"), cookie)

            closedStore.close()
            closedFile.parentFile.deleteRecursively() shouldBe true
            openFile.exists() shouldBe false

            ZoukanLifecycle.shutdown()

            openFile.isFile shouldBe true
            closedFile.exists() shouldBe false
            ZoukanLifecycle.activeHookCount() shouldBe 0
        } finally {
            openStore.close()
            closedStore.close()
        }
    }

    @Test
    fun `LibraryUpdater cancels active update when ZoukanLifecycle shuts down`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val getLibraryManga = mockk<GetLibraryManga> {
            coEvery { await() } coAnswers {
                started.countDown()
                awaitCancellation()
            }
        }
        val preferences = mockk<LibraryPreferences> {
            every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) {
                every { get() } returns 0L
            }
        }
        val updater = LibraryUpdater(
            dirs = mockk(),
            getLibraryManga = getLibraryManga,
            refreshManga = mockk(),
            sourceManager = mockk(),
            preferences = preferences,
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = mockk(relaxed = true),
            getManga = mockk(),
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk(),
            syncPreferences = mockk {
                every { syncService() } returns mockk<Preference<Int>> { every { get() } returns 0 }
            },
            syncManager = { mockk() },
        )

        updater.start() shouldBe true
        started.await(10, TimeUnit.SECONDS) shouldBe true

        ZoukanLifecycle.shutdown()

        withTimeout(10_000) { updater.join() }
        updater.isRunning shouldBe false
        updater.progress.value shouldBe null
    }
}

class ShutdownOrderTest {

    @Test
    fun `quitting stops the background jobs and waits for a slow progress write`() = runBlocking<Unit> {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val written = AtomicBoolean(false)
        val writer = ProgressWriter(appScope) {
            delay(300)
            written.set(true)
        }
        writer.submit(ChapterUpdate(id = 1, lastPageRead = 5))
        // The loop waits for the extensions, as before they load
        val jobs = BackgroundJobs(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk { every { isInitialized } returns MutableStateFlow(false) },
        )
        jobs.start(appScope)
        jobs.isRunning shouldBe true
        val updater = mockk<LibraryUpdater>(relaxed = true)
        val downloads = mockk<DownloadManager>(relaxed = true)

        AppShutdown.run(jobs, updater, downloads, appScope, databaseIdle = {})

        written.get() shouldBe true
        jobs.isRunning shouldBe false
        verify {
            updater.cancel()
            downloads.pause()
        }
        appScope.cancel()
    }
}
