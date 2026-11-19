package zoukan.desktop.sync

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppScope
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.testing.FreshInjekt
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SyncManagerTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    // Not @TempDir: the preferences file is written in the background and may outlive the test
    private val root = Files.createTempDirectory("zoukan-sync").toFile()

    @BeforeEach
    fun setUp() {
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
    }
    private val preferences by lazy {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        SyncPreferences(DesktopPreferenceStore(JavaSharedPreferences("sync_test_${System.nanoTime()}")))
    }
    private val handler = mockk<DatabaseHandler>()
    private val manager by lazy {
        SyncManager(
            context = mockk<Application>(relaxed = true),
            handler = handler,
            syncPreferences = preferences,
            getCategories = mockk(),
            dirs = ZoukanDirs(root),
        )
    }

    @Test
    fun `an unsupported service is never due`() {
        preferences.syncInterval().set(60)
        preferences.syncService().set(SyncManager.SyncService.GOOGLE_DRIVE.value)
        manager.isDue() shouldBe false
        preferences.syncService().set(SyncManager.SyncService.NONE.value)
        manager.isDue() shouldBe false
        preferences.syncService().set(SyncManager.SyncService.WEB_DAV.value)
        manager.isDue() shouldBe true
    }

    @Test
    fun `a failed sync waits before the next try instead of retrying every minute`() {
        preferences.syncInterval().set(30)
        preferences.syncService().set(SyncManager.SyncService.WEB_DAV.value)
        coEvery { handler.await<Any>(any(), any()) } throws IOException("offline")
        val now = System.currentTimeMillis()
        runBlocking { manager.syncData() }
        manager.isDue(now + 60_000) shouldBe false
        // It retries after the interval (30 minutes, shorter than an hour)
        manager.isDue(now + 31 * 60_000) shouldBe true
    }

    @Test
    fun `sync on start or on resume runs only with a supported service and that trigger on`() {
        coEvery { handler.await<Any>(any(), any()) } throws IOException("offline")
        // A server the sync may reach: an empty address is refused before the database is read
        preferences.webDavUrl().set("https://dav.example.com")
        preferences.setSyncTriggerOptions(SyncTriggerOptions(syncOnAppStart = false, syncOnAppResume = true))

        // syncOn decides and launches the sync on AppScope before it returns
        val earlier = AppScope.coroutineContext.job.children.toSet()
        // No service: never, whatever the trigger
        preferences.syncService().set(SyncManager.SyncService.NONE.value)
        manager.syncOn { it.syncOnAppResume }
        // A service the desktop cannot sync with
        preferences.syncService().set(SyncManager.SyncService.GOOGLE_DRIVE.value)
        manager.syncOn { it.syncOnAppResume }
        // WebDAV, but the start trigger is off
        preferences.syncService().set(SyncManager.SyncService.WEB_DAV.value)
        manager.syncOn { it.syncOnAppStart }
        // A sync that should not start has by now either read the database or not started at all
        val started = AppScope.coroutineContext.job.children.filter { it !in earlier }.toList()
        runBlocking { withTimeout(10_000) { started.joinAll() } }
        coVerify(exactly = 0) { handler.await<Any>(any(), any()) }

        // WebDAV and the resume trigger on: a sync starts in the background
        manager.syncOn { it.syncOnAppResume }
        coVerify(timeout = 10_000, exactly = 1) { handler.await<Any>(any(), any()) }
    }

    @Test
    fun `plain http is allowed only for this computer and the local network`() {
        listOf(
            "https://example.com",
            "http://localhost:8080",
            "http://127.0.0.1",
            "http://[::1]",
            "http://192.168.1.10",
            "http://10.0.0.2",
            "http://172.16.5.1",
            "http://nas.local",
        ).forEach { SyncManager.isAllowedSyncUrl(it) shouldBe true }
        listOf(
            "http://example.com",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "ftp://example.com",
            "",
        ).forEach { SyncManager.isAllowedSyncUrl(it) shouldBe false }
    }

    @Test
    fun `a default sync uploads no private settings`() {
        preferences.getSyncSettings().privateSettings shouldBe false
    }

    @Test
    fun `a sync to a public host over plain http is refused before anything is sent`() {
        preferences.syncInterval().set(30)
        preferences.syncService().set(SyncManager.SyncService.WEB_DAV.value)
        preferences.webDavUrl().set("http://example.com/dav")
        preferences.webDavUsername().set("user")
        preferences.webDavPassword().set("secret")
        runBlocking { manager.syncData() }
        coVerify(exactly = 0) { handler.await<Any>(any(), any()) }
        manager.isDue(System.currentTimeMillis() + 60_000) shouldBe false

        preferences.syncService().set(SyncManager.SyncService.SYNCYOMI.value)
        preferences.clientHost().set("http://sync.example.com")
        runBlocking { manager.syncData() }
        coVerify(exactly = 0) { handler.await<Any>(any(), any()) }
        manager.isDue(System.currentTimeMillis() + 60_000) shouldBe false
    }

    // A favorite Manga reads its custom info through Injekt (registered in setUp)
    private val local by lazy {
        Manga.create().copy(id = 7, source = 1, url = "/a", ogTitle = "A", favorite = true)
    }

    /** A manager whose snapshots go to [writes] (or fail with [fail]) and whose entry writes go to [written]. */
    private fun withSnapshots(writes: MutableList<String>, written: MutableList<Manga>, fail: Boolean = false): SyncManager {
        coEvery { handler.awaitList<Manga>(any(), any()) } returns listOf(local)
        val snapshot = SafetySnapshot(
            folder = { File(root, "safety") },
            write = { file ->
                if (fail) throw IOException("disk full")
                writes += "snapshot"
                file.writeText("x")
            },
        )
        return SyncManager(
            context = mockk<Application>(relaxed = true),
            handler = handler,
            syncPreferences = preferences,
            getCategories = mockk(),
            dirs = ZoukanDirs(root),
            safetySnapshot = snapshot,
            updateLocalManga = { manga ->
                writes += "update"
                written += manga
            },
        )
    }

    @Test
    fun `a remote with no favorites that unfavorites a local entry takes a snapshot first`() = runBlocking {
        val writes = mutableListOf<String>()
        val written = mutableListOf<Manga>()
        val manager = withSnapshots(writes, written)
        val remote = BackupManga(source = 1, url = "/a", title = "A").apply { favorite = false }

        manager.snapshotThenUpdateNonFavorites(favorites = emptyList(), nonFavorites = listOf(remote)) shouldBe true

        writes shouldBe listOf("snapshot", "update")
        written.single().favorite shouldBe false
    }

    @Test
    fun `without a snapshot the sync unfavorites nothing`() = runBlocking {
        val writes = mutableListOf<String>()
        val written = mutableListOf<Manga>()
        val manager = withSnapshots(writes, written, fail = true)
        val remote = BackupManga(source = 1, url = "/a", title = "A").apply { favorite = false }

        manager.snapshotThenUpdateNonFavorites(favorites = emptyList(), nonFavorites = listOf(remote)) shouldBe false

        written shouldBe emptyList()
    }

    @Test
    fun `a sync that changes nothing takes no snapshot`() = runBlocking {
        val writes = mutableListOf<String>()
        val written = mutableListOf<Manga>()
        val manager = withSnapshots(writes, written)
        // A non-favorite the desktop does not have: nothing local changes
        val remote = BackupManga(source = 1, url = "/b", title = "B").apply { favorite = false }

        manager.snapshotThenUpdateNonFavorites(favorites = emptyList(), nonFavorites = listOf(remote)) shouldBe true

        writes shouldBe emptyList()
    }
}
