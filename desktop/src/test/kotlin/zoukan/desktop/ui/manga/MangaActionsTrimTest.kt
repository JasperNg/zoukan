package zoukan.desktop.ui.manga

import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.backup.SnapshotReason
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.common.LibraryAddController
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

class MangaActionsTrimTest {

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
        val store = InMemoryPreferenceStore()
        val trackPrefs = TrackPreferences(store)
        val trackerManager = mockk<TrackerManager> {
            every { loggedInTrackers() } returns listOf(mockk<Tracker>())
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
        val store = InMemoryPreferenceStore()
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
            every { loggedInTrackers() } returns listOf(mockk<Tracker>())
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
