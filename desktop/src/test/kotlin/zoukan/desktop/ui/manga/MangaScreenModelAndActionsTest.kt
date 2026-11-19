package zoukan.desktop.ui.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.Toasts
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.backup.SnapshotReason
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.library.LibraryUpdateErrors
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.StorageUnavailableException
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UncaughtCoroutineErrors
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.common.LibraryAddController
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.io.File
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * Tests for [MangaScreenModel] and its action delegates: chapter selection/downloads/settings,
 * cleanup/external action trims, Clear manga local-file boundaries, storage-unavailable delete
 * errors, and missing/deleted entry or refresh failure handling.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MangaScreenModelAndActionsTest {

    /**
     * Covers chapter multi-selection, read/unread/bookmark/mark-previous-read actions, unread &
     * bookmarked download menu queuing, deleting downloads confirmation dialog, and chapter settings
     * (Display mode, Scanlator filter, and Set as default).
     */
    @Nested
    inner class ChapterActions {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<MangaScreenModel>()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
        }

        @AfterEach
        fun tearDown() {
            models.forEach { it.onDispose() }
            app.cancel()
            Dispatchers.resetMain()
            main.close()
        }

        private val manga = Manga.create().copy(id = 77, source = 4242L, ogTitle = "Formats", initialized = true)
        private val chapters = (1..12).map { n ->
            Chapter.create().copy(
                id = n.toLong(),
                mangaId = manga.id,
                name = "Chapter $n",
                url = "/c/$n",
                chapterNumber = n.toDouble(),
                sourceOrder = (12 - n).toLong(),
                read = n == 1,
                bookmark = n == 7 || n == 9,
                scanlator = if (n <= 6) "Alpha Scans" else "Beta Scans",
            )
        }

        private fun newModel(
            updateChapter: UpdateChapter = mockk(relaxed = true),
            downloads: DownloadManager = mockk(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
            },
            chapterSettings: ChapterSettings = mockk(relaxed = true) {
                every { excludedScanlators(any()) } returns flowOf(emptySet())
            },
        ): MangaScreenModel {
            val store = TestPreferenceStore()
            Injekt.addSingleton(mockk<GetMergedMangaById>(relaxed = true))
            val model = MangaScreenModel(
                mangaId = manga.id,
                getMangaWithChapters = mockk {
                    coEvery { awaitManga(manga.id) } returns manga
                    coEvery { subscribe(manga.id) } returns flowOf(manga to chapters)
                },
                sourceManager = mockk<SourceManager>(relaxed = true),
                updateChapter = updateChapter,
                refreshManga = mockk(relaxed = true),
                downloadManager = downloads,
                getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                trackPreferences = mockk(relaxed = true),
                trackerManager = mockk(relaxed = true),
                trackChapters = mockk(relaxed = true),
                getMergedChaptersByMangaId = mockk(relaxed = true),
                getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
                addToLibraryFlow = mockk(relaxed = true),
                getCategories = mockk(relaxed = true),
                setMangaCategories = mockk(relaxed = true),
                chapterSettings = chapterSettings,
                filterChaptersForDownload = mockk(relaxed = true),
                updateErrors = mockk(relaxed = true),
                refreshTracks = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(store),
                uiPreferences = UiPreferences(store),
                chaptersByEntry = mockk<ChaptersByEntry> {
                    coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) }
                },
                desktopPreferences = lazyOf(DesktopPreferences(store)),
                appScope = app,
            ).also { models += it }
            Await.until("manga loaded", 5_000) { model.state.value.manga != null }
            return model
        }

        @Test
        fun `selection actions mark read, reset progress on unread, bookmark, and mark previous read`() {
            val updates = Collections.synchronizedList(mutableListOf<ChapterUpdate>())
            val updateChapter = mockk<UpdateChapter> {
                coEvery { awaitAll(any()) } answers {
                    updates += firstArg<List<ChapterUpdate>>()
                    true
                }
            }
            val model = newModel(updateChapter = updateChapter)

            // selectAll selects all filtered chapters; clearSelection empties it
            model.selectAll()
            model.state.value.selected.size shouldBe 12
            model.clearSelection()
            model.state.value.selected shouldBe emptySet()

            // Mark selection [1, 2] as read -> clears selection
            model.toggleSelection(chapters[0])
            model.toggleSelection(chapters[1])
            model.markRead(listOf(chapters[0], chapters[1]), read = true)
            Await.until("selection cleared after markRead", 5_000) {
                model.state.value.selected.isEmpty() && updates.size == 2
            }
            updates.map { it.id to it.read } shouldBe listOf(1L to true, 2L to true)

            // Mark chapter 2 unread -> resets lastPageRead = 0L
            updates.clear()
            model.toggleSelection(chapters[1])
            model.markRead(listOf(chapters[1]), read = false)
            Await.until("unread reset lastPageRead", 5_000) {
                model.state.value.selected.isEmpty() && updates.size == 1
            }
            updates.single().let {
                it.id shouldBe 2L
                it.read shouldBe false
                it.lastPageRead shouldBe 0L
            }

            // Bookmark selection [3, 4], then remove bookmark on 4
            updates.clear()
            model.toggleSelection(chapters[2])
            model.toggleSelection(chapters[3])
            model.setBookmark(listOf(chapters[2], chapters[3]), bookmark = true)
            Await.until("bookmarks updated", 5_000) { updates.size == 2 && model.state.value.selected.isEmpty() }
            updates.map { it.id to it.bookmark } shouldBe listOf(3L to true, 4L to true)

            // markPreviousRead(chapter 4) marks only unread chapters older than chapter 4 (2, 3; 1 is already read)
            updates.clear()
            model.markPreviousRead(chapters[3])
            Await.until("previous read updated", 5_000) { updates.isNotEmpty() }
            updates.map { it.id } shouldBe listOf(2L, 3L)
        }

        @Test
        fun `download menu actions enqueue exact unread and bookmarked chapters in reading order`() {
            val enqueued = Collections.synchronizedList(mutableListOf<List<Long>>())
            val downloads = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
                coEvery { enqueueAll(any()) } answers {
                    enqueued += firstArg<List<Pair<Manga, List<Chapter>>>>().flatMap { (_, list) -> list.map { it.id } }
                }
            }
            val model = newModel(downloads = downloads)

            model.downloadUnread(1)
            Await.until("next 1 queued", 5_000) { enqueued.size == 1 }
            enqueued.last() shouldBe listOf(2L)

            model.downloadUnread(5)
            Await.until("next 5 queued", 5_000) { enqueued.size == 2 }
            enqueued.last() shouldBe (2L..6L).toList()

            model.downloadUnread(10)
            Await.until("next 10 queued", 5_000) { enqueued.size == 3 }
            enqueued.last() shouldBe (2L..11L).toList()

            model.downloadUnread(null)
            Await.until("all unread queued", 5_000) { enqueued.size == 4 }
            enqueued.last() shouldBe (2L..12L).toList()

            model.downloadBookmarked()
            Await.until("bookmarked queued", 5_000) { enqueued.size == 5 }
            enqueued.last() shouldBe listOf(7L, 9L)
        }

        @Test
        fun `deleting downloads of selection asks with chapter count and deletes only after confirm`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val deleted = Collections.synchronizedList(mutableListOf<List<Long>>())
                val downloads = mockk<DownloadManager>(relaxed = true) {
                    every { changes } returns MutableStateFlow(0)
                    every { queue } returns MutableStateFlow(emptyList())
                    coEvery { delete(any(), any()) } answers {
                        deleted += secondArg<List<Chapter>>().map { it.id }
                        Unit
                    }
                }
                val model = newModel(downloads = downloads)
                model.selectAll()

                var dialog by mutableStateOf<MangaDialog?>(MangaDialog.ConfirmDeleteDownloads(chapters))
                setContent {
                    MangaDialogHost(
                        dialog = dialog,
                        manga = manga,
                        model = model,
                        isLocal = false,
                        onShow = { dialog = it },
                    )
                }

                val title = localizedPlural(DMR.plurals.delete_selected_downloads_title, chapters.size, chapters.size)
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
                deleted shouldBe emptyList()

                // Cancel preserves selection and avoids deletion
                onNodeWithText(localized(MR.strings.action_cancel)).performClick()
                waitForIdle()
                deleted shouldBe emptyList()
                model.state.value.selected.size shouldBe 12

                // Re-open and confirm deletes and clears selection
                runOnUiThread { dialog = MangaDialog.ConfirmDeleteDownloads(chapters) }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(localized(DMR.strings.delete_downloads)).performClick()
                waitUntil(timeoutMillis = 5_000) { deleted.size == 1 && model.state.value.selected.isEmpty() }
                deleted.single().size shouldBe 12
            }

        @Test
        fun `ChapterSettingsDialog updates display mode, excluded scanlators, and set as default`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val chapterSettings = mockk<ChapterSettings>(relaxed = true) {
                    every { excludedScanlators(any()) } returns flowOf(emptySet())
                }
                val model = newModel(chapterSettings = chapterSettings)

                setContent {
                    ChapterSettingsDialog(state = model.state.value, model = model, onDismiss = {})
                }

                // Scanlators filter
                onAllNodesWithText(localized(DMR.strings.scanlators)).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Beta Scans")).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("Beta Scans").onLast().performClick()
                onAllNodesWithText(localized(MR.strings.action_save)).onFirst().performClick()
                coVerify(timeout = 5_000) { chapterSettings.setExcludedScanlators(manga.id, setOf("Beta Scans")) }

                // Display tab
                onAllNodesWithText(localized(MR.strings.action_display)).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText(localized(MR.strings.show_chapter_number))).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(localized(MR.strings.show_chapter_number)).performClick()
                coVerify(timeout = 5_000) { chapterSettings.setDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER) }

                // Set as default (current entry vs all library entries)
                onAllNodesWithText(localized(MR.strings.set_chapter_settings_as_default)).onFirst().performClick()
                val allEntriesLabel = localized(DMR.strings.also_apply_them_to_every_entry)
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(allEntriesLabel)).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(localized(MR.strings.action_ok)).onLast().performClick()
                coVerify(timeout = 5_000) { chapterSettings.setAsDefault(manga, false) }

                onAllNodesWithText(localized(MR.strings.set_chapter_settings_as_default)).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(allEntriesLabel)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(allEntriesLabel).performClick()
                onAllNodesWithText(localized(MR.strings.action_ok)).onLast().performClick()
                coVerify(timeout = 5_000) { chapterSettings.setAsDefault(manga, true) }
            }
    }

    @Nested
    inner class ActionsTrim {

        @TempDir
        lateinit var tempDir: File

        private val main = Executors.newSingleThreadExecutor { Thread(it, "trim-test-main") }.asCoroutineDispatcher()
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<MangaScreenModel>()
        private val originalTrash = systemTrash

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
        }

        @AfterEach
        fun tearDown() {
            systemTrash = originalTrash
            models.forEach { it.onDispose() }
            appScope.cancel()
            Dispatchers.resetMain()
            main.close()
        }

        @Test
        fun `MangaCleanupActions aborts DeleteChapters when safety snapshot guard refuses`() {
            val deleteChapters = mockk<DeleteChapters>(relaxed = true)
            val safetySnapshot = mockk<SafetySnapshot> {
                coEvery { guard(SnapshotReason.CLEAR_CHAPTERS, any()) } returns false
            }
            val actions = MangaCleanupActions(
                downloadManager = mockk(relaxed = true),
                chaptersByEntry = mockk(relaxed = true),
                sourceManager = mockk(relaxed = true),
                downloadPaths = lazyOf(mockk(relaxed = true)),
                getChaptersByMangaId = lazyOf(mockk(relaxed = true)),
                getMergedMangaById = lazyOf(mockk(relaxed = true)),
                deleteChapters = lazyOf(deleteChapters),
                safetySnapshot = safetySnapshot,
                snapshotPrompt = SnapshotFailedPrompt { false },
                appScope = appScope,
            )
            val manga = Manga.create().copy(id = 1L, source = 10L)
            val chapters = listOf(Chapter.create().copy(id = 101L, mangaId = 1L))

            actions.clearManga(manga, chapters, deleteDownload = false, removeChapters = true)

            coVerify(timeout = 5_000) { safetySnapshot.guard(SnapshotReason.CLEAR_CHAPTERS, any()) }
            coVerify(exactly = 0) { deleteChapters.await(any()) }
        }

        @Test
        fun `MangaCleanupActions trashes only confirmed local folders belonging to the entry`() = runBlocking {
            val localRoot = File(tempDir, "local").apply { mkdirs() }
            val ownedDir = File(localRoot, "Owned").apply { mkdirs() }
            val unconfirmedDir = File(localRoot, "Other").apply { mkdirs() }
            val foreignDir = File(tempDir, "foreign").apply { mkdirs() }
            val localSource = LocalSource(folder = { localRoot })
            val sourceManager = mockk<SourceManager>(relaxed = true) {
                every { get(LocalSource.ID) } returns localSource
            }
            val trashed = Collections.synchronizedList(mutableListOf<File>())
            systemTrash = {
                trashed += it
                true
            }
            val actions = MangaCleanupActions(
                downloadManager = mockk(relaxed = true),
                chaptersByEntry = mockk(relaxed = true),
                sourceManager = sourceManager,
                downloadPaths = lazyOf(mockk<DownloadPaths>(relaxed = true)),
                getChaptersByMangaId = lazyOf(mockk<GetChaptersByMangaId>(relaxed = true)),
                getMergedMangaById = lazyOf(mockk<GetMergedMangaById>(relaxed = true)),
                deleteChapters = lazyOf(mockk(relaxed = true)),
                safetySnapshot = mockk(relaxed = true),
                snapshotPrompt = SnapshotFailedPrompt { false },
                appScope = appScope,
            )
            val manga = Manga.create().copy(id = 2L, source = LocalSource.ID, url = "Owned")

            actions.localFolders(manga).map { it.canonicalFile } shouldBe listOf(ownedDir.canonicalFile)

            val moved = actions.trashLocalFolders(manga, listOf(ownedDir, foreignDir))
            moved shouldBe true
            trashed.map { it.canonicalFile } shouldBe listOf(ownedDir.canonicalFile)
            unconfirmedDir.exists() shouldBe true
            actions.openMangaFolder(StubSource(1L, "en", "Stub"), manga)
        }

        @Test
        fun `MangaExternalActions handles auto-track modes, mangaUrl, and mergedEntries`() = runBlocking {
            val store = TestPreferenceStore()
            val trackPrefs = TrackPreferences(store)
            val trackerManager = mockk<TrackerManager> {
                every { loggedInTrackers() } returns listOf(mockk<BaseTracker>())
            }
            val trackChapters = mockk<TrackChapters>(relaxed = true)
            val httpSource = mockk<HttpSource>(relaxed = true) {
                every { id } returns 20L
                every { getMangaUrl(any()) } returns "https://example.org/manga/1"
            }
            val mergedRef = Manga.create().copy(id = 50L, source = MERGED_SOURCE_ID)
            val childOnline = Manga.create().copy(id = 51L, source = 20L, url = "/m/51")
            val sourceManager = mockk<SourceManager>(relaxed = true) {
                every { getOrStub(20L) } returns httpSource
            }
            val external = MangaExternalActions(
                sourceManager = sourceManager,
                getTracks = mockk<GetTracks> { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                trackPreferences = trackPrefs,
                trackerManager = trackerManager,
                trackChapters = trackChapters,
                refreshTracks = mockk<RefreshTracks>(relaxed = true),
                getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
                getMergedMangaById = lazyOf(
                    mockk<GetMergedMangaById> {
                        coEvery { await(50L) } returns listOf(mergedRef, childOnline)
                    },
                ),
                appScope = appScope,
            )

            external.mangaUrl(childOnline, httpSource) shouldBe "https://example.org/manga/1"
            external.mangaUrl(childOnline, mockk<Source>()) shouldBe null
            external.mergedEntries(mergedRef) shouldBe listOf(childOnline to httpSource)
            external.mergedEntries(childOnline).shouldBeEmpty()

            trackPrefs.trackOnAddingToLibrary().set(true)
            external.shouldShowTrackingOnAdd() shouldBe true

            val chapters = listOf(
                Chapter.create().copy(id = 1L, chapterNumber = 4.0),
                Chapter.create().copy(id = 2L, chapterNumber = 9.0),
                Chapter.create().copy(id = 3L, chapterNumber = -1.0),
            )
            trackPrefs.autoUpdateTrackOnMarkRead().set(AutoTrackState.ASK)
            external.handleAutoTrackOnMarkRead(51L, chapters, trackCount = 1) shouldBe 9.0
            external.handleAutoTrackOnMarkRead(51L, chapters, trackCount = 0) shouldBe null

            trackPrefs.autoUpdateTrackOnMarkRead().set(AutoTrackState.NEVER)
            external.handleAutoTrackOnMarkRead(51L, chapters, trackCount = 1) shouldBe null

            trackPrefs.autoUpdateTrackOnMarkRead().set(AutoTrackState.ALWAYS)
            external.handleAutoTrackOnMarkRead(51L, chapters, trackCount = 1) shouldBe null
            coVerify(timeout = 5_000) { trackChapters.await(51L, 9.0) }
        }

        @Test
        fun `MangaChapterActions selects chapters in reading order and updates read and bookmark state`() = runBlocking {
            val updates = Collections.synchronizedList(mutableListOf<ChapterUpdate>())
            val updateChapter = mockk<UpdateChapter> {
                coEvery { awaitAll(any()) } answers {
                    updates += firstArg<List<ChapterUpdate>>()
                    true
                }
            }
            val downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
            }
            val actions = MangaChapterActions(
                updateChapter = updateChapter,
                downloadManager = downloadManager,
                chaptersByEntry = mockk<ChaptersByEntry>(relaxed = true),
                filterChaptersForDownload = mockk<FilterChaptersForDownload>(relaxed = true),
                appScope = appScope,
            )
            val ch1 = Chapter.create().copy(id = 1L, sourceOrder = 2L, read = true, bookmark = false)
            val ch2 = Chapter.create().copy(id = 2L, sourceOrder = 1L, read = false, bookmark = true)
            val ch3 = Chapter.create().copy(id = 3L, sourceOrder = 0L, read = false, bookmark = false)

            actions.selectUnreadForDownload(listOf(ch3, ch1, ch2), downloaded = setOf(2L), count = null)
                .map { it.id } shouldBe listOf(3L)
            actions.selectBookmarkedForDownload(listOf(ch3, ch1, ch2), downloaded = emptySet())
                .map { it.id } shouldBe listOf(2L)

            actions.markRead(listOf(ch2), read = true)
            updates.single().let {
                it.id shouldBe 2L
                it.read shouldBe true
                it.lastPageRead shouldBe null
            }
            coVerify(timeout = 5_000) { downloadManager.afterMarkedRead(match { it.map { c -> c.id } == listOf(2L) }) }

            updates.clear()
            actions.setBookmark(listOf(ch3), bookmark = true)
            updates.single().let {
                it.id shouldBe 3L
                it.bookmark shouldBe true
            }
        }

        @Test
        fun `trimmed MangaScreenModel primary constructor coordinates selection and library add flow`() {
            val store = TestPreferenceStore()
            val manga = Manga.create().copy(id = 77L, source = 10L, ogTitle = "Trimmed", favorite = false, initialized = true)
            val chapters = (1L..4L).map { id ->
                Chapter.create().copy(id = id, mangaId = manga.id, name = "Ch $id", url = "/$id", sourceOrder = 4L - id)
            }
            val duplicate = Manga.create().copy(id = 88L, source = 11L, ogTitle = "Trimmed", favorite = true)
            val category = Category(id = 5L, name = "Action", order = 0L, flags = 0L, hidden = false)
            val addToLibrary = mockk<AddToLibrary>(relaxed = true) {
                coEvery { duplicates(manga) } returns listOf(MangaWithChapterCount(duplicate, 10L))
                coEvery { plan() } returns AddToLibrary.Plan(emptyList(), ask = listOf(category))
            }
            val trackPrefs = TrackPreferences(store).apply { trackOnAddingToLibrary().set(true) }
            val trackerManager = mockk<TrackerManager> {
                every { loggedInTrackers() } returns listOf(mockk<BaseTracker>())
            }
            val model = MangaScreenModel(
                mangaId = manga.id,
                getMangaWithChapters = mockk<GetMangaWithChapters> {
                    coEvery { awaitManga(manga.id) } returns manga
                    coEvery { subscribe(manga.id) } returns flowOf(manga to chapters)
                },
                getMergedChaptersByMangaId = mockk(relaxed = true),
                sourceManager = mockk(relaxed = true),
                refreshManga = mockk(relaxed = true),
                updateErrors = mockk(relaxed = true),
                chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
                libraryPreferences = LibraryPreferences(store),
                uiPreferences = UiPreferences(store),
                desktopPreferences = lazyOf(DesktopPreferences(store)),
                chapterActions = MangaChapterActions(
                    updateChapter = mockk(relaxed = true),
                    downloadManager = mockk(relaxed = true) {
                        every { changes } returns MutableStateFlow(0)
                        every { queue } returns MutableStateFlow(emptyList())
                    },
                    chaptersByEntry = mockk(relaxed = true),
                    filterChaptersForDownload = mockk(relaxed = true),
                    appScope = appScope,
                ),
                cleanupActions = MangaCleanupActions(
                    downloadManager = mockk(relaxed = true),
                    chaptersByEntry = mockk(relaxed = true),
                    sourceManager = mockk(relaxed = true),
                    downloadPaths = lazyOf(mockk(relaxed = true)),
                    getChaptersByMangaId = lazyOf(mockk(relaxed = true)),
                    getMergedMangaById = lazyOf(mockk(relaxed = true)),
                    deleteChapters = lazyOf(mockk(relaxed = true)),
                    safetySnapshot = mockk(relaxed = true),
                    snapshotPrompt = SnapshotFailedPrompt { false },
                    appScope = appScope,
                ),
                externalActions = MangaExternalActions(
                    sourceManager = mockk(relaxed = true),
                    getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                    trackPreferences = trackPrefs,
                    trackerManager = trackerManager,
                    trackChapters = mockk(relaxed = true),
                    refreshTracks = mockk(relaxed = true),
                    getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
                    getMergedMangaById = lazyOf(mockk(relaxed = true)),
                    appScope = appScope,
                ),
                libraryAdd = LibraryAddController(addToLibrary = { addToLibrary }),
            ).also { models += it }

            Await.until("entry loaded", 5_000) { model.state.value.manga != null }

            // ListSelection integration on chapter list
            model.toggleSelection(chapters[0])
            model.selectRange(chapters[2])
            model.state.value.selected shouldBe setOf(1L, 2L, 3L)
            model.invertSelection()
            model.state.value.selected shouldBe setOf(4L)
            model.clearSelection()
            model.state.value.selected shouldBe emptySet()

            // LibraryAddController integration: duplicate -> addAnyway -> categoryChoice -> addToLibrary -> showTracking
            model.toggleFavorite()
            Await.until("duplicates prompted", 5_000) { model.state.value.duplicates != null }
            model.state.value.duplicates?.map { it.manga.id } shouldBe listOf(88L)

            model.addAnyway()
            Await.until("categories prompted", 5_000) { model.state.value.categoryChoice != null }
            model.state.value.categoryChoice shouldBe listOf(category)

            model.addToLibrary(listOf(category.id))
            Await.until("added and tracking opened", 5_000) { model.state.value.showTracking }
            coVerify { addToLibrary.add(manga, listOf(5L), fetchOnAdd = false) }
        }
    }

    /**
     * Verifies "Clear manga" boundaries:
     * - Clearing downloaded data deletes only online downloads and never deletes or modifies local
     *   source files (neither on a local entry nor on a merged entry containing a local entry).
     * - Clearing local files requires explicit selection and a second "Move to trash" confirmation,
     *   does nothing on Cancel, moves only confirmed local folders via [systemTrash], and triggers
     *   a single refresh on the UI thread.
     */
    @Nested
    inner class ClearMangaBoundaries {

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
            val store = TestPreferenceStore()
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

    /** Deleting downloads with the storage drive unplugged says so, instead of failing without a word. */
    @Nested
    inner class DeleteFailure {

        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        private val toasts = CopyOnWriteArrayList<String>()
        private val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val models = mutableListOf<MangaScreenModel>()

        private val manga = Manga.create().copy(id = 99, source = 1, initialized = true)
        private val chapter = Chapter.create().copy(id = 1, mangaId = 99)

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            collector.launch(start = CoroutineStart.UNDISPATCHED) { Toasts.flow.collect { toasts += it } }
        }

        @AfterEach
        fun tearDown() {
            models.forEach { it.onDispose() }
            collector.cancel()
            app.cancel()
            Dispatchers.resetMain()
            main.close()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }

        private fun newModel(): MangaScreenModel {
            val unplugged = StorageUnavailableException(File("/unplugged/Zoukan"))
            val downloads = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
                coEvery { delete(any(), any()) } throws unplugged
                coEvery { deleteManga(any(), any()) } throws unplugged
            }
            val downloadedOnly = mockk<Preference<Boolean>> { every { changes() } returns flowOf(false) }
            val preferences = mockk<DesktopPreferences> { every { downloadedOnly() } returns downloadedOnly }
            return MangaScreenModel(
                mangaId = 99,
                getMangaWithChapters = mockk {
                    coEvery { awaitManga(99L) } returns manga
                    coEvery { subscribe(99L) } returns flowOf(manga to listOf(chapter))
                },
                sourceManager = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                refreshManga = mockk(relaxed = true),
                downloadManager = downloads,
                getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                trackPreferences = mockk(relaxed = true),
                trackerManager = mockk(relaxed = true),
                trackChapters = mockk(relaxed = true),
                getMergedChaptersByMangaId = mockk(relaxed = true),
                getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
                addToLibraryFlow = mockk(relaxed = true),
                getCategories = mockk(relaxed = true),
                setMangaCategories = mockk(relaxed = true),
                chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
                filterChaptersForDownload = mockk(relaxed = true),
                updateErrors = mockk(relaxed = true),
                refreshTracks = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                uiPreferences = UiPreferences(InMemoryPreferenceStore()),
                chaptersByEntry = mockk<ChaptersByEntry> { coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) } },
                desktopPreferences = lazyOf(preferences),
                getChaptersByMangaId = lazyOf(mockk<GetChaptersByMangaId> { coEvery { await(any(), any()) } returns listOf(chapter) }),
                appScope = app,
            ).also { models += it }
        }

        /**
         * Waits for [condition], then for the UI thread to run what was queued on it before: a failure
         * of that work reaches the default handler on that thread, so it has arrived when this returns.
         */
        private fun waitUntil(condition: () -> Boolean) {
            Await.until("the screen model", 5_000, condition = condition)
            runBlocking(main) {}
        }

        private val failed get() = localized(DMR.strings.screen_action_failed_delete_downloads)

        @Test
        fun `Delete downloads with the drive unplugged shows the error`() {
            val model = newModel()
            waitUntil { model.state.value.manga != null }

            model.deleteDownloads(listOf(chapter))
            waitUntil { toasts.any { it.startsWith(failed) } }

            toasts.any { it.startsWith(failed) && File("/unplugged/Zoukan").path in it } shouldBe true
            uncaught.shouldBeEmpty()
        }

        @Test
        fun `Clear manga with the drive unplugged shows the error`() {
            val model = newModel()
            waitUntil { model.state.value.manga != null }

            model.clearManga(deleteDownload = true, removeChapters = false)
            waitUntil { toasts.any { it.startsWith(failed) } }

            toasts.any { it.startsWith(failed) } shouldBe true
            uncaught.shouldBeEmpty()
        }
    }

    /** An entry that is gone, or goes while its screen is open, shows an error instead of failing. */
    @Nested
    inner class MissingEntry {

        // The model's scope runs on Main; a thread of its own stands in for the UI thread
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        private val models = mutableListOf<MangaScreenModel>()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        }

        @AfterEach
        fun tearDown() {
            models.forEach { it.onDispose() }
            Dispatchers.resetMain()
            main.close()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            UncaughtCoroutineErrors.drain()
        }

        private fun newModel(
            getMangaWithChapters: GetMangaWithChapters,
            refreshManga: RefreshManga = mockk(relaxed = true),
            updateErrors: LibraryUpdateErrors = mockk(relaxed = true),
        ): MangaScreenModel {
            val downloadedOnly = mockk<Preference<Boolean>> { every { changes() } returns flowOf(false) }
            val preferences = mockk<DesktopPreferences> { every { downloadedOnly() } returns downloadedOnly }
            return MangaScreenModel(
                mangaId = 99,
                getMangaWithChapters = getMangaWithChapters,
                sourceManager = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                refreshManga = refreshManga,
                downloadManager = mockk(relaxed = true) {
                    every { changes } returns MutableStateFlow(0)
                    every { queue } returns MutableStateFlow(emptyList())
                },
                getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                trackPreferences = mockk(relaxed = true),
                trackerManager = mockk(relaxed = true),
                trackChapters = mockk(relaxed = true),
                getMergedChaptersByMangaId = mockk(relaxed = true),
                getFlatMetadataById = mockk { every { subscribe(any<Long>()) } returns flowOf() },
                addToLibraryFlow = mockk(relaxed = true),
                getCategories = mockk(relaxed = true),
                setMangaCategories = mockk(relaxed = true),
                chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
                filterChaptersForDownload = mockk(relaxed = true),
                updateErrors = updateErrors,
                refreshTracks = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                uiPreferences = UiPreferences(InMemoryPreferenceStore()),
                chaptersByEntry = mockk(relaxed = true),
                desktopPreferences = lazyOf(preferences),
            ).also { models += it }
        }

        /**
         * Waits for [condition], then for the UI thread to run what was queued on it before: a failure
         * of that work reaches the default handler on that thread, so it has arrived when this returns.
         */
        private fun waitUntil(condition: () -> Boolean) {
            Await.until("the screen model", 5_000, condition = condition)
            runBlocking(main) {}
        }

        @Test
        fun `opening an entry that no longer exists shows an error instead of failing`() {
            val interactor = mockk<GetMangaWithChapters> {
                // What executeAsOne throws for a missing row
                coEvery { awaitManga(99L) } throws NullPointerException("ResultSet returned null")
            }
            val model = newModel(interactor)
            waitUntil { model.state.value.error != null }

            model.state.value.error shouldBe localized(DMR.strings.this_entry_no_longer_exists)
            uncaught.shouldBeEmpty()
        }

        @Test
        fun `an entry deleted while its screen is open shows an error`() {
            val manga = Manga.create().copy(id = 99, initialized = true)
            val interactor = mockk<GetMangaWithChapters> {
                coEvery { awaitManga(99L) } returns manga
                coEvery { subscribe(99L) } returns flow {
                    emit(manga to listOf(Chapter.create().copy(id = 1, mangaId = 99)))
                    throw NullPointerException("ResultSet returned null")
                }
            }
            val model = newModel(interactor)
            waitUntil { model.state.value.error != null }

            model.state.value.error shouldBe localized(DMR.strings.this_entry_no_longer_exists)
            uncaught.shouldBeEmpty()
        }

        @Test
        fun `running out of memory while the entry opens shows an error and still reaches the crash report`() {
            val interactor = mockk<GetMangaWithChapters> { coEvery { awaitManga(99L) } throws OutOfMemoryError("test") }
            val model = newModel(interactor)
            waitUntil { model.state.value.error != null && uncaught.isNotEmpty() }

            model.state.value.refreshing shouldBe false
            uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
        }

        @Test
        fun `running out of memory while refreshing ends the refresh and still reaches the crash report`() {
            val manga = Manga.create().copy(id = 99, initialized = true)
            val interactor = mockk<GetMangaWithChapters> {
                coEvery { awaitManga(99L) } returns manga
                coEvery { subscribe(99L) } returns flowOf(manga to emptyList())
            }
            val refresh = mockk<RefreshManga> {
                coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws OutOfMemoryError("test")
            }
            val model = newModel(interactor, refresh)
            waitUntil { model.state.value.manga != null && !model.state.value.refreshing }
            runBlocking(main) { model.refresh() }
            waitUntil { uncaught.isNotEmpty() && !model.state.value.refreshing }

            model.state.value.refreshing shouldBe false
            model.state.value.error shouldNotBe null
            // The first load refreshes an entry without chapters too: each refresh's error goes on
            uncaught.forEach { it.shouldBeInstanceOf<OutOfMemoryError>() }
        }

        @Test
        fun `a refresh whose error cannot even be logged still ends`() {
            val manga = Manga.create().copy(id = 99, initialized = true)
            val interactor = mockk<GetMangaWithChapters> {
                coEvery { awaitManga(99L) } returns manga
                coEvery { subscribe(99L) } returns flowOf(manga to listOf(Chapter.create().copy(id = 1, mangaId = 99)))
            }
            // An extension's exception whose message itself throws: putting it into words fails
            val broken = object : UnknownHostException() {
                override val message: String get() = throw IllegalStateException("the message cannot be read")
            }
            val refresh = mockk<RefreshManga> {
                coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws broken
            }
            val model = newModel(interactor, refresh)
            waitUntil { model.state.value.manga != null && !model.state.value.refreshing }
            runBlocking(main) { model.refresh(manualFetch = false) }

            // Not left refreshing for the screen's lifetime: the refresh button works again
            waitUntil { !model.state.value.refreshing }
        }

        @Test
        fun `a failed refresh stays refreshing until its error is recorded and shown`() {
            val manga = Manga.create().copy(id = 99, initialized = true)
            val interactor = mockk<GetMangaWithChapters> {
                coEvery { awaitManga(99L) } returns manga
                coEvery { subscribe(99L) } returns flowOf(manga to listOf(Chapter.create().copy(id = 1, mangaId = 99)))
            }
            val refresh = mockk<RefreshManga> {
                coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws NoChaptersException()
            }
            val recording = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val errors = mockk<LibraryUpdateErrors>(relaxed = true) {
                coEvery { record(99L, any()) } coAnswers {
                    recording.complete(Unit)
                    release.await()
                }
            }
            val model = newModel(interactor, refresh, errors)
            waitUntil { model.state.value.manga != null }
            runBlocking(main) { model.refresh(manualFetch = false) }
            runBlocking { withTimeout(5_000) { recording.await() } }

            // While the error is recorded the refresh is still running: no second refresh can start
            // and be overwritten when this one ends, and no generic error shows first
            runBlocking(main) { model.state.value.refreshing shouldBe true }
            model.state.value.error shouldBe null

            release.complete(Unit)
            waitUntil { !model.state.value.refreshing }
            model.state.value.error shouldBe localized(DMR.strings.the_source_lists_no_chapters)
            uncaught.shouldBeEmpty()
        }
    }
}
