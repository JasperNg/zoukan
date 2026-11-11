package zoukan.desktop.sync

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
}
