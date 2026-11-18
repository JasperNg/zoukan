package zoukan.desktop.app

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ZoukanDirs
import java.io.IOException
import java.nio.file.Files

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
