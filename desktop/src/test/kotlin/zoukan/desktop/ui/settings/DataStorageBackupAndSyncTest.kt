package zoukan.desktop.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.GetSourceIdsWithNonLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_delete
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.action_reset
import tachiyomi.i18n.action_save
import tachiyomi.i18n.pref_restore_backup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager.CleanupItem
import zoukan.desktop.download.DownloadManager.CleanupPlan
import zoukan.desktop.download.DownloadManager.CleanupReason
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.browse.deleteSourceCategory
import zoukan.desktop.ui.browse.renameSourceCategory
import zoukan.desktop.ui.settings.ServerAddress.Verdict
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.cleanup_preview_permanent
import zoukan.i18n.desktop.cleanup_preview_read
import zoukan.i18n.desktop.cleanup_preview_trash
import zoukan.i18n.desktop.cleanup_preview_unfinished
import zoukan.i18n.desktop.could_not_leave_storage_folder_out_of_time_machine
import zoukan.i18n.desktop.plain_http_refused
import zoukan.i18n.desktop.replace
import zoukan.i18n.desktop.replace_server_library
import zoukan.i18n.desktop.replace_server_library_warning
import zoukan.i18n.desktop.reset_sync_time_warning
import zoukan.i18n.desktop.reset_the_last_sync_time
import zoukan.i18n.desktop.storage_change_warning
import zoukan.i18n.desktop.storage_folder_has_downloads
import zoukan.i18n.desktop.storage_folder_may_be_synced
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class StorageLocationTest {

    @TempDir
    lateinit var temp: File

    @AfterEach
    fun waitForWriters() {
        JavaSharedPreferences.awaitPendingSaves()
    }

    @Test
    fun `a path that is not a writable folder is refused`() {
        val file = File(temp, "file.txt").apply { writeText("x") }
        StorageLocation.check(file) shouldBe StorageLocation.Verdict.NotWritable
        StorageLocation.check(File(file, "sub")) shouldBe StorageLocation.Verdict.NotWritable
    }

    @Test
    fun `a read-only folder is refused`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val folder = File(temp, "readonly").apply { mkdirs() }
        folder.setWritable(false)
        try {
            StorageLocation.check(folder) shouldBe StorageLocation.Verdict.NotWritable
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `a folder that already holds downloads is flagged`() {
        File(temp, "other/downloads/Src/Title/ch.cbz").apply { parentFile.mkdirs() }.writeText("x")
        StorageLocation.check(File(temp, "other")) shouldBe StorageLocation.Verdict.Ok(hasDownloads = true)
        StorageLocation.check(File(temp, "empty").apply { mkdirs() }) shouldBe StorageLocation.Verdict.Ok(hasDownloads = false)
    }

    @Test
    fun `checking a folder leaves nothing behind`() {
        val folder = File(temp, "fresh").apply { mkdirs() }
        StorageLocation.check(folder)
        folder.listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `changing the folder leaves the old downloads untouched`() {
        val dataDir = File(temp, "data")
        val storage = ZoukanStorage(
            ZoukanDirs(dataDir),
            DesktopPreferenceStore(JavaSharedPreferences("storage_location_test_${System.nanoTime()}")),
        )
        File(storage.downloads, "Src/Title/one.cbz").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        File(storage.downloads, "Src/Title/two.cbz").writeBytes(byteArrayOf(4, 5, 6, 7))
        File(storage.autoBackups, "backup.tachibk").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(8, 9))
        val before = hashes(dataDir)
        before.size shouldBe 3

        val newDir = File(temp, "new")
        var invalidated = false
        StorageLocation.apply(storage, newDir.path) { invalidated = true }

        invalidated shouldBe true
        storage.root shouldBe newDir
        hashes(dataDir) shouldBe before
        File(newDir, ZoukanStorage.DOWNLOADS).exists() shouldBe false
    }

    @Test
    fun `choosing a new folder says the downloads stay in the old one`() = runDesktopComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        val old = File(temp, "old")
        setContent {
            StorageChangeDialog(old, File(temp, "new"), hasDownloads = true, onOpenOld = {}, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }
        onNodeWithText(localized(DMR.strings.storage_change_warning, old.path)).assertExists()
        onNodeWithText(localized(DMR.strings.storage_folder_has_downloads)).assertExists()

        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        dismissed shouldBe 1
        confirmed shouldBe 0

        onNodeWithText(localized(MR.strings.action_ok)).performClick()
        waitForIdle()
        confirmed shouldBe 1
    }

    @Test
    fun `iCloud Drive and other synced folders are flagged`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Library/Mobile Documents/com~apple~CloudDocs/Zoukan"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Library/Mobile Documents"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Library/CloudStorage/Dropbox/Zoukan"), home) shouldBe StorageLocation.CloudSync.SYNCED
        // Case does not matter on macOS, nor a path that is not normalized
        StorageLocation.cloudSync(File(home, "library/mobile documents/x"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Downloads/../Library/Mobile Documents/x"), home) shouldBe StorageLocation.CloudSync.SYNCED
    }

    @Test
    fun `Desktop and Documents may be synced`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Documents/Zoukan"), home) shouldBe StorageLocation.CloudSync.MAYBE_SYNCED
        StorageLocation.cloudSync(File(home, "Desktop"), home) shouldBe StorageLocation.CloudSync.MAYBE_SYNCED
    }

    @Test
    fun `a privacy setting that could not be applied shows its error line only while it failed`() = runDesktopComposeUiTest {
        val message = localized(DMR.strings.could_not_leave_storage_folder_out_of_time_machine)
        var failed by mutableStateOf(false)
        setContent { StoragePrivacyFailure(failed, message) }
        onNodeWithText(message).assertDoesNotExist()
        failed = true
        waitForIdle()
        onNodeWithText(message).assertExists()
        failed = false
        waitForIdle()
        onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun `older sync apps' folders in the home folder are flagged`() {
        val home = File("/Users/someone")
        listOf(
            "Dropbox",
            "Dropbox/Zoukan",
            "Dropbox (Personal)/Zoukan",
            "Dropbox (Team Name)",
            "OneDrive",
            "OneDrive/Manga/Zoukan",
            "OneDrive - Contoso Ltd/Zoukan",
            "Google Drive",
            "Google Drive/My Drive/Zoukan",
            // Case is ignored, as on macOS file systems
            "dropbox/zoukan",
            "ONEDRIVE - School/x",
            "google drive/my drive",
            "Manga/../Dropbox/Zoukan",
        ).forEach { sub ->
            withClue(sub) { StorageLocation.cloudSync(File(home, sub), home) shouldBe StorageLocation.CloudSync.SYNCED }
        }
    }

    @Test
    fun `only whole folder names directly in the home folder count as older sync folders`() {
        val home = File("/Users/someone")
        listOf(
            "Dropbox2/Zoukan",
            "My Dropbox",
            "OneDriveBackup",
            "OneDrive-Old",
            "OneDrive -",
            "Google Drive Old",
            "GoogleDrive",
            "Manga/Dropbox",
            "Manga/OneDrive - Contoso",
            "Manga/Google Drive/My Drive",
            "",
        ).forEach { sub ->
            withClue(sub) { StorageLocation.cloudSync(File(home, sub), home) shouldBe null }
        }
        // Outside the home folder
        StorageLocation.cloudSync(File("/Volumes/Drive/Dropbox"), home) shouldBe null
        StorageLocation.cloudSync(File("/Users/other/OneDrive"), home) shouldBe null
        StorageLocation.cloudSync(File("/Users/someone2/Google Drive"), home) shouldBe null
    }

    @Test
    fun `other folders are not flagged`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Library/Application Support/Zoukan"), home) shouldBe null
        StorageLocation.cloudSync(File(home, "Documents2/Zoukan"), home) shouldBe null
        StorageLocation.cloudSync(File(home, "Manga"), home) shouldBe null
        StorageLocation.cloudSync(File("/Volumes/Drive/Documents"), home) shouldBe null
        StorageLocation.cloudSync(File("/Users/other/Documents"), home) shouldBe null
    }

    @Test
    fun `the change dialog warns about a synced folder`() = runDesktopComposeUiTest {
        setContent {
            StorageChangeDialog(
                File(temp, "old"),
                File(temp, "new"),
                hasDownloads = false,
                cloudSync = StorageLocation.CloudSync.MAYBE_SYNCED,
                onOpenOld = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
        onNodeWithText(localized(DMR.strings.storage_folder_may_be_synced)).assertExists()
    }

    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }
}

class DataSettingsTest {

    private fun manga(title: String, author: String?, artist: String?) =
        Manga.create().copy(ogTitle = title, ogAuthor = author, ogArtist = artist)

    @Test
    fun `library list is the Android exporter's CSV`() {
        val favorites = listOf(
            manga("Plain", "Ann", "Bo"),
            manga("Comma, title", "Quote \"A\"", null),
            manga("Two\nlines", "", "Cy"),
        )
        LibraryCsv.generate(favorites, LibraryCsv.Options(title = true, author = true, artist = true)) shouldBe
            "Plain,Ann,Bo\r\n\"Comma, title\",\"Quote \"\"A\"\"\",\r\n\"Two\nlines\",,Cy"
        LibraryCsv.generate(favorites.take(1), LibraryCsv.Options(title = true, author = false, artist = true)) shouldBe "Plain,Bo"
    }

    @Test
    fun `sizes read as Android's formatter`() {
        formatSize(999) shouldBe "999 B"
        formatSize(1_500) shouldBe "%.2f kB".format(1.5)
        formatSize(250_000_000) shouldBe "250 MB"
    }

    @Test
    fun `renaming and deleting a source category updates its sources`() {
        val preferences = SourcePreferences(DesktopPreferenceStore(JavaSharedPreferences("source_categories_test_${System.nanoTime()}")))
        preferences.sourcesTabCategories().set(setOf("A", "B"))
        preferences.sourcesTabSourcesInCategories().set(setOf("1|A", "2|A", "2|B"))

        renameSourceCategory(preferences, "A", "C")
        preferences.sourcesTabCategories().get() shouldBe setOf("B", "C")
        preferences.sourcesTabSourcesInCategories().get() shouldBe setOf("1|C", "2|C", "2|B")

        deleteSourceCategory(preferences, "C")
        preferences.sourcesTabCategories().get() shouldBe setOf("B")
        preferences.sourcesTabSourcesInCategories().get() shouldBe setOf("2|B")
    }
}

@OptIn(ExperimentalTestApi::class)
class BackupButtonsTest {
    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val picks = AtomicInteger()

    private val effects = object : SystemEffects by SystemEffects.Native {
        override fun pickFile(request: FileRequest): File? {
            picks.incrementAndGet()
            return null
        }
    }

    @AfterEach
    fun restore() {
        SystemEffects.current = SystemEffects.Native
    }

    private object Blank : Screen {
        @Composable
        override fun Content() = Unit
    }

    @Test
    fun `two clicks on Restore before the button is disabled open one picker`() = runDesktopComposeUiTest {
        val releaseLookup = CountDownLatch(1)
        Injekt.addSingleton(ZoukanDirs(temp))
        Injekt.addSingleton(
            mockk<ZoukanStorage> {
                every { isAvailable } answers {
                    releaseLookup.await(5, TimeUnit.SECONDS)
                    false
                }
            },
        )
        SystemEffects.current = effects
        setContent { Navigator(Blank) { BackupButtons() } }

        val click = onNodeWithText(localized(MR.strings.pref_restore_backup)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // In one frame, as a fast double click lands before the recomposition that disables the button
        try {
            runOnUiThread {
                click()
                click()
            }
        } finally {
            releaseLookup.countDown()
        }
        Await.until("the picker to be asked for") { picks.get() > 0 }
        waitForIdle()

        picks.get() shouldBe 1
    }
}

/** Where the restore picker opens: the app's backups folder, or the storage folder's automatic backups. */
class BackupPickerFolderTest {

    @TempDir
    lateinit var temp: File

    private val manual get() = File(temp, "data/backups")
    private val automatic get() = File(temp, "storage/autobackup")

    private fun backup(dir: File, name: String) = File(dir.apply { mkdirs() }, name).writeBytes(byteArrayOf(1))

    @Test
    fun `an Android storage folder's automatic backups are offered when the app has made none`() {
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }

    @Test
    fun `the app's own backups folder is kept when it holds backups`() {
        backup(manual, "zoukan_2026-01-02_03-04.tachibk")
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe manual
    }

    @Test
    fun `the app's backups folder stays the start when there are no automatic backups`() {
        backupPickerFolder(manual, automatic) shouldBe manual
        // Other files, or a folder named like a backup, are no backups
        backup(automatic, "notes.txt")
        File(automatic, "old.tachibk").mkdirs()
        backupPickerFolder(manual, automatic) shouldBe manual
        // A missing storage folder (a disconnected drive)
        backupPickerFolder(manual, null) shouldBe manual
    }

    @Test
    fun `old proto backups count as backups`() {
        backup(automatic, "tachiyomi_2020-01-02_03-04.proto.gz")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }

    @Test
    fun `the start folder is looked up off the calling thread`() = runBlocking {
        // Real time: the lookup runs on the IO threads, well within the bound
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        val caller = Thread.currentThread()
        var lookedUpOn: Thread? = null
        val start = backupPickerStart(manual, automatic = {
            lookedUpOn = Thread.currentThread()
            automatic
        })
        start shouldBe automatic
        (lookedUpOn !== caller) shouldBe true
    }

    @Test
    fun `a storage folder that does not answer falls back to the app's backups folder`() = runTest {
        // A sleeping external drive or network share: the check blocks until the test ends
        val asleep = CountDownLatch(1)
        try {
            val start = backupPickerStart(manual, automatic = {
                asleep.await(30, TimeUnit.SECONDS)
                automatic
            }, io = Dispatchers.IO)
            start shouldBe manual
        } finally {
            asleep.countDown()
        }
    }

    @Test
    fun `a check that fails falls back to the app's backups folder`() = runTest {
        val start = backupPickerStart(
            manual,
            automatic = { throw SecurityException("access denied") },
            io = StandardTestDispatcher(testScheduler),
        )
        start shouldBe manual
    }
}

/** Clear database saves a safety backup before it deletes, and deletes nothing when the user cancels after one fails. */
class ClearDatabaseSnapshotTest {
    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models = mutableListOf<ClearDatabaseScreenModel>()

    @TempDir
    lateinit var dir: File

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        try {
            models.forEach { it.onDispose() }
            runBlocking {
                withTimeout(5_000) {
                    models.forEach { model ->
                        val job = model.screenModelScope.coroutineContext[Job]!!
                        job.cancelChildren()
                        job.children.toList().joinAll()
                    }
                }
            }
            models.clear()
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    private val deleted = CountDownLatch(1)

    private fun handler(): DatabaseHandler = mockk {
        every { subscribeToList<GetSourceIdsWithNonLibraryManga>(any()) } returns flowOf(listOf(GetSourceIdsWithNonLibraryManga(1L, 3L)))
        coEvery { await<Any>(any(), any()) } answers {
            deleted.countDown()
            Unit
        }
    }

    private fun model(handler: DatabaseHandler, fail: Boolean, prompt: (String) -> Boolean) =
        ClearDatabaseScreenModel(
            handler = handler,
            sourceManager = mockk(relaxed = true),
            snapshot = SafetySnapshot(folder = { dir }, write = { file ->
                if (fail) throw IOException("disk full")
                file.writeText("before")
            }),
            snapshotPrompt = { prompt(it) },
        ).also { models += it }

    private fun selectAll(model: ClearDatabaseScreenModel) = runBlocking {
        withTimeout(5_000) { model.state.first { !it.items.isNullOrEmpty() } }
        model.selectAll()
    }

    @Test
    fun `a safety backup is saved before the entries are deleted`() {
        val handler = handler()
        val model = model(handler, fail = false, prompt = { error("must not ask") })
        selectAll(model)

        model.delete(keepReadManga = true)

        deleted.await(5, TimeUnit.SECONDS) shouldBe true
        dir.listFiles()!!.single().name shouldStartWith "safety_clear-database_"
    }

    @Test
    fun `nothing is deleted when the backup fails and the user cancels`() {
        val handler = handler()
        val asked = CountDownLatch(1)
        val model = model(handler, fail = true, prompt = {
            asked.countDown()
            false
        })
        selectAll(model)

        model.delete(keepReadManga = true)

        asked.await(5, TimeUnit.SECONDS) shouldBe true
        deleted.await(500, TimeUnit.MILLISECONDS) shouldBe false
        coVerify(exactly = 0) { handler.await<Any>(any(), any()) }
    }

    @Test
    fun `the user may clear without a backup when it fails`() {
        val handler = handler()
        val model = model(handler, fail = true, prompt = { true })
        selectAll(model)

        model.delete(keepReadManga = true)

        deleted.await(5, TimeUnit.SECONDS) shouldBe true
    }
}

/** The clean-up preview: what goes, with orphaned folders unticked until the user ticks them. */
@OptIn(ExperimentalTestApi::class)
class CleanupPreviewDialogTest {

    private val orphan = File("/downloads/A (EN)/Nobody")
    private val plan = CleanupPlan(
        listOf(
            CleanupItem(File("/downloads/A (EN)/Entry/Chapter 1_tmp"), CleanupReason.UNFINISHED, setOf(1), 2_000),
            CleanupItem(File("/downloads/A (EN)/Entry/Chapter 2.cbz"), CleanupReason.READ, setOf(1), 3_000),
            CleanupItem(orphan, CleanupReason.ORPHAN_FOLDER, emptySet(), 5_000),
        ),
    )

    @Test
    fun `shows the counts and deletes no orphaned folder unless it is ticked`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<Set<File>>()
        setContent { CleanupPreviewDialog(plan, canTrash = true, onDismiss = {}) { confirmed += it } }

        onNodeWithText(localized(DMR.strings.cleanup_preview_unfinished, 1, formatSize(2_000))).assertExists()
        onNodeWithText(localized(DMR.strings.cleanup_preview_read, 1, formatSize(3_000))).assertExists()
        onNodeWithText(localized(DMR.strings.cleanup_preview_trash)).assertExists()
        onNodeWithText(localized(MR.strings.action_delete)).performClick()
        confirmed shouldBe listOf(emptySet())

        onNodeWithText("A (EN)/Nobody (${formatSize(5_000)})").performClick()
        onNodeWithText(localized(MR.strings.action_delete)).performClick()
        confirmed.last() shouldBe setOf(orphan)
    }

    @Test
    fun `says when deleted files cannot be recovered, and deletes nothing when only orphans are listed`() = runDesktopComposeUiTest {
        val onlyOrphan = CleanupPlan(plan.orphans)
        setContent { CleanupPreviewDialog(onlyOrphan, canTrash = false, onDismiss = {}) {} }

        onNodeWithText(localized(DMR.strings.cleanup_preview_permanent)).assertExists()
        onNodeWithText(localized(MR.strings.action_delete)).assertIsNotEnabled()
    }
}

@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class SyncSettingsValidationTest {

    @AfterEach
    fun waitForWriters() {
        JavaSharedPreferences.awaitPendingSaves()
    }

    @Test
    fun `an address that is not http or https is refused`() {
        listOf("ftp://example.com/dav", "example.com", "https://", "http://exa mple.com").forEach {
            ServerAddress.check(it) shouldBe Verdict.Invalid
        }
    }

    @Test
    fun `plain http to a public host is warned`() {
        ServerAddress.check("http://example.com/webdav") shouldBe Verdict.PlainHttp
        ServerAddress.check("http://203.0.113.5:8080") shouldBe Verdict.PlainHttp
    }

    @Test
    fun `plain http to this computer or the local network is fine`() {
        listOf(
            "http://localhost:8282",
            "http://127.0.0.1",
            "http://[::1]:8080",
            "http://192.168.1.10/dav",
            "http://10.0.0.2",
            "http://172.20.1.1",
            "http://nas.local/dav",
            "https://example.com/dav",
        ).forEach { ServerAddress.check(it) shouldBe Verdict.Ok }
    }

    @Test
    fun `plain http over a private network such as Tailscale is fine`() {
        listOf(
            "http://100.101.102.103:8282",
            "http://100.64.0.1",
            "http://sync.internal/dav",
            "http://nas.home.arpa",
        ).forEach { ServerAddress.check(it) shouldBe Verdict.Ok }
        // Just outside 100.64.0.0/10
        ServerAddress.check("http://100.128.0.1") shouldBe Verdict.PlainHttp
    }

    @Test
    fun `an empty address clears the setting`() {
        ServerAddress.check("") shouldBe Verdict.Ok
        ServerAddress.check("   ") shouldBe Verdict.Ok
    }

    @Test
    fun `resetting the last sync time asks first`() = runDesktopComposeUiTest {
        var resets = 0
        setContent { ResetSyncTimeRow { resets++ } }

        onNodeWithText(localized(DMR.strings.reset_the_last_sync_time)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.reset_sync_time_warning)).assertExists()
        resets shouldBe 0

        onNodeWithText(localized(MR.strings.action_reset)).performClick()
        waitForIdle()
        resets shouldBe 1
    }

    @Test
    fun `replacing the server's library asks first, says what is lost, and cancel sends nothing`() = runDesktopComposeUiTest {
        var replaces = 0
        setContent { ReplaceServerLibraryRow(enabled = true) { replaces++ } }

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertExists()
        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertDoesNotExist()
        replaces shouldBe 0

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace)).performClick()
        waitForIdle()
        replaces shouldBe 1
    }

    @Test
    fun `the server's library cannot be replaced while a sync runs`() = runDesktopComposeUiTest {
        var replaces = 0
        setContent { ReplaceServerLibraryRow(enabled = false) { replaces++ } }

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertDoesNotExist()
        replaces shouldBe 0
    }

    @Test
    fun `a sync address that is refused cannot be saved`() = runDesktopComposeUiTest {
        val store = DesktopPreferenceStore(JavaSharedPreferences("sync_address_test_${System.nanoTime()}"))
        val preference = store.getString("address", "")
        setContent { TextDialog("Server URL", preference, secret = false, checkAddress = true, refusePlainHttp = true) {} }

        onNode(hasSetTextAction()).performTextInput("http://example.com/dav")
        onNodeWithText(localized(DMR.strings.plain_http_refused)).assertExists()
        onNodeWithText(localized(MR.strings.action_save)).assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextReplacement("https://example.com/dav")
        onNodeWithText(localized(MR.strings.action_save)).assertIsEnabled()
        onNodeWithText(localized(MR.strings.action_save)).performClick()
        waitForIdle()
        preference.get() shouldBe "https://example.com/dav"
    }
}
