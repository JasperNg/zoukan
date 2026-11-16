package zoukan.desktop.sync

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ZoukanDirs
import java.io.IOException
import java.nio.file.Files

class SyncManagerTest {
    // Not @TempDir: the preferences file is written in the background and may outlive the test
    private val root = Files.createTempDirectory("zoukan-sync").toFile()
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
}
