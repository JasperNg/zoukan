package zoukan.desktop.ui.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * Verifies "Clear manga" boundaries:
 * - Clearing downloaded data deletes only online downloads and never deletes or modifies local
 *   source files (neither on a local entry nor on a merged entry containing a local entry).
 * - Clearing local files requires explicit selection and a second "Move to trash" confirmation,
 *   does nothing on Cancel, moves only confirmed local folders via [systemTrash], and triggers
 *   a single refresh on the UI thread.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ClearMangaBoundaryTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var tempDir: File

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<MangaScreenModel>()
    private val originalTrash = systemTrash

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        onRefreshStart = null
        systemTrash = originalTrash
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private class ReactiveMemoryPreferenceStore : PreferenceStore {
        private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()
        private fun <T> pref(key: String, defaultValue: T): Preference<T> {
            val flow = flows.computeIfAbsent(key) { MutableStateFlow(null) }
            @Suppress("UNCHECKED_CAST")
            return object : Preference<T> {
                override fun key(): String = key
                override fun get(): T = (flow.value as T?) ?: defaultValue
                override fun isSet(): Boolean = flow.value != null
                override fun delete() {
                    flow.value = null
                }
                override fun defaultValue(): T = defaultValue
                override fun changes(): Flow<T> = flow.map { (it as T?) ?: defaultValue }
                override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
                override fun set(value: T) {
                    flow.value = value
                }
            }
        }
        override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)
        override fun <T> getObjectFromString(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T) = pref(key, defaultValue)
        override fun <T> getObjectFromInt(key: String, defaultValue: T, serializer: (T) -> Int, deserializer: (Int) -> T) = pref(key, defaultValue)
        override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }
    }

    private fun seedLocalFiles(folderName: String = "LocalManga"): Pair<File, List<File>> {
        val localRoot = File(tempDir, "local").apply { mkdirs() }
        val entryDir = File(localRoot, folderName).apply { mkdirs() }
        val cbz = File(entryDir, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val cover = File(entryDir, "cover.png").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        return localRoot to listOf(cbz, cover)
    }

    private fun createModel(
        manga: Manga,
        chapters: List<Chapter>,
        localRoot: File,
        downloads: DownloadManager,
        onlineSourceId: Long = 4242L,
        mergedEntries: List<Manga> = emptyList(),
        deleteChapters: DeleteChapters = mockk(relaxed = true),
        refreshManga: RefreshManga = mockk(relaxed = true),
    ): MangaScreenModel {
        val store = ReactiveMemoryPreferenceStore()
        val localSource = LocalSource(folder = { localRoot })
        val onlineSource = mockk<HttpSource>(relaxed = true) {
            every { id } returns onlineSourceId
            every { name } returns "OnlineSource"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(LocalSource.ID) } returns localSource
            every { getOrStub(LocalSource.ID) } returns localSource
            every { get(onlineSourceId) } returns onlineSource
            every { getOrStub(onlineSourceId) } returns onlineSource
        }
        val getMergedMangaById = mockk<GetMergedMangaById> {
            coEvery { await(manga.id) } returns mergedEntries
        }
        val getChaptersByMangaId = mockk<GetChaptersByMangaId> {
            coEvery { await(any(), any()) } answers {
                val id = firstArg<Long>()
                chapters.filter { it.mangaId == id }
            }
        }
        val model = MangaScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = mockk {
                coEvery { awaitManga(manga.id) } returns manga
                coEvery { subscribe(manga.id) } returns flowOf(manga to chapters)
            },
            sourceManager = sourceManager,
            updateChapter = mockk(relaxed = true),
            refreshManga = refreshManga,
            downloadManager = downloads,
            getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
            trackPreferences = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
            trackChapters = mockk(relaxed = true),
            getMergedChaptersByMangaId = mockk {
                coEvery { subscribe(manga.id) } returns flowOf(chapters)
            },
            getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
            addToLibraryFlow = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
            filterChaptersForDownload = mockk(relaxed = true),
            updateErrors = mockk(relaxed = true),
            refreshTracks = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(store),
            uiPreferences = UiPreferences(store),
            chaptersByEntry = mockk<ChaptersByEntry> {
                coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) }
            },
            desktopPreferences = lazyOf(DesktopPreferences(store)),
            getChaptersByMangaId = lazyOf(getChaptersByMangaId),
            getMergedMangaById = lazyOf(getMergedMangaById),
            deleteChapters = lazyOf(deleteChapters),
            appScope = app,
            safetySnapshot = mockk<SafetySnapshot> { coEvery { guard(any(), any()) } returns true },
            snapshotPrompt = SnapshotFailedPrompt { true },
        ).also { models += it }

        Await.until("entry loaded", 5_000) { model.state.value.manga != null }
        return model
    }

    @Test
    fun `clearing downloaded data never deletes local files on local or merged entries`() {
        val (localRoot, localFiles) = seedLocalFiles()
        val hashesBefore = localFiles.associateWith { it.readBytes().contentHashCode() }

        val localManga = Manga.create().copy(id = 10, source = LocalSource.ID, url = "LocalManga", ogTitle = "LocalManga", initialized = true)
        val localChapter = Chapter.create().copy(id = 101, mangaId = localManga.id, url = "LocalManga/Chapter 1.cbz", name = "Ch 1")
        val downloads = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
        }
        val localModel = createModel(localManga, listOf(localChapter), localRoot, downloads)

        localModel.clearManga(deleteDownload = true, removeChapters = false)
        // Allow appScope IO job a moment to complete if anything was launched
        localModel.clearManga(deleteDownload = false, removeChapters = false)
        coVerify(exactly = 0) { downloads.deleteManga(match { it.id == localManga.id }, any()) }
        localFiles.forEach { file ->
            file.exists() shouldBe true
            file.readBytes().contentHashCode() shouldBe hashesBefore.getValue(file)
        }

        // Merged entry containing both an online entry and the local entry
        val onlineManga = Manga.create().copy(id = 20, source = 4242L, url = "/online", ogTitle = "Online", initialized = true)
        val onlineChapter = Chapter.create().copy(id = 201, mangaId = onlineManga.id, url = "/online/c1", name = "Online Ch 1")
        val mergedManga = Manga.create().copy(id = 30, source = MERGED_SOURCE_ID, url = "merged", ogTitle = "Merged", initialized = true)

        val deletedMangaIds = Collections.synchronizedList(mutableListOf<Long>())
        val mergedDownloads = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
            coEvery { deleteManga(any(), any()) } answers {
                deletedMangaIds += firstArg<Manga>().id
                true
            }
        }
        val mergedModel = createModel(
            manga = mergedManga,
            chapters = listOf(onlineChapter, localChapter),
            localRoot = localRoot,
            downloads = mergedDownloads,
            mergedEntries = listOf(onlineManga, localManga),
        )

        mergedModel.clearManga(deleteDownload = true, removeChapters = false)
        Await.until("online downloads deleted", 5_000) { onlineManga.id in deletedMangaIds }

        deletedMangaIds shouldBe listOf(onlineManga.id)
        localFiles.forEach { file ->
            file.exists() shouldBe true
            file.readBytes().contentHashCode() shouldBe hashesBefore.getValue(file)
        }
    }

    @Test
    fun `clear manga removes chapters from database while preserving local files`() {
        val (localRoot, localFiles) = seedLocalFiles("Charlie")
        val hashesBefore = localFiles.associateWith { it.readBytes().contentHashCode() }
        val localManga = Manga.create().copy(id = 40, source = LocalSource.ID, url = "Charlie", ogTitle = "Charlie", initialized = true)
        val ch1 = Chapter.create().copy(id = 401, mangaId = localManga.id, url = "Charlie/Chapter 1.cbz", name = "Ch 1")
        val deletedIds = Collections.synchronizedList(mutableListOf<Long>())
        val deleteChapters = mockk<DeleteChapters> {
            coEvery { await(any()) } answers {
                deletedIds += firstArg<List<Long>>()
                Unit
            }
        }
        val downloads = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
        }
        val model = createModel(localManga, listOf(ch1), localRoot, downloads, deleteChapters = deleteChapters)

        model.clearManga(deleteDownload = false, removeChapters = true)
        Await.until("chapters deleted from database", 5_000) { ch1.id in deletedIds }

        deletedIds shouldBe listOf(ch1.id)
        localFiles.forEach { file ->
            file.exists() shouldBe true
            file.readBytes().contentHashCode() shouldBe hashesBefore.getValue(file)
        }
    }

    @Test
    fun `clearing local files requires second confirmation, keeps files on cancel, and refreshes on Main thread`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val (localRoot, localFiles) = seedLocalFiles("AlphaLocal")
            val entryDir = localFiles.first().parentFile
            val localManga = Manga.create().copy(id = 50, source = LocalSource.ID, url = "AlphaLocal", ogTitle = "AlphaLocal", initialized = true)
            val ch1 = Chapter.create().copy(id = 501, mangaId = localManga.id, url = "AlphaLocal/Chapter 1.cbz", name = "Ch 1")
            val downloads = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
            }
            val refreshManga = mockk<RefreshManga>(relaxed = true)
            val model = createModel(localManga, listOf(ch1), localRoot, downloads, refreshManga = refreshManga)

            val trashed = Collections.synchronizedList(mutableListOf<File>())
            val refreshThreadsAreUi = Collections.synchronizedList(mutableListOf<Boolean>())
            systemTrash = {
                trashed += it
                true
            }
            onRefreshStart = { thread ->
                refreshThreadsAreUi += SwingUtilities.isEventDispatchThread() || thread.name.startsWith("test-main")
            }

            var dialog by mutableStateOf<MangaDialog?>(MangaDialog.Clear)
            setContent {
                MangaDialogHost(
                    dialog = dialog,
                    manga = localManga,
                    model = model,
                    isLocal = true,
                    onShow = { dialog = it },
                )
            }

            val localLabel = localized(DMR.strings.clear_local_files, entryDir.name)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(localLabel)).fetchSemanticsNodes().isNotEmpty()
            }
            // Local entry does not offer "Downloaded data"
            onAllNodes(hasText(localized(KMR.strings.downloaded_data))).fetchSemanticsNodes().shouldBeEmpty()

            onNodeWithText(localLabel).performClick()
            onNode(hasText(localized(MR.strings.action_ok)) and isEnabled()).performClick()

            // Second confirmation appears; Cancel leaves local files untouched
            val secondTitle = localized(DMR.strings.delete_local_files_title)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(secondTitle)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(localized(MR.strings.action_cancel)).performClick()
            waitForIdle()
            trashed.shouldBeEmpty()

            // Re-open, tick, confirm OK, then confirm Move to trash
            runOnUiThread { dialog = MangaDialog.Clear }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(localLabel)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(localLabel).performClick()
            onNode(hasText(localized(MR.strings.action_ok)) and isEnabled()).performClick()
            val moveLabel = localized(DMR.strings.move_to_trash)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(moveLabel)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(moveLabel).performClick()

            waitUntil(timeoutMillis = 5_000) { trashed.isNotEmpty() && refreshThreadsAreUi.isNotEmpty() }
            trashed.map { it.canonicalFile } shouldBe listOf(entryDir.canonicalFile)
            refreshThreadsAreUi shouldBe listOf(true)
        }
}
