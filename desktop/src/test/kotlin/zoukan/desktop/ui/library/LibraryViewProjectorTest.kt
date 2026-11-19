package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import java.util.concurrent.Executors

@OptIn(ForTestsOnly::class, ExperimentalCoroutinesApi::class)
class LibraryViewProjectorTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models = mutableListOf<LibraryScreenModel>()

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
                        val job = model.screenModelScope.coroutineContext.job
                        job.cancelChildren()
                        job.children.toList().joinAll()
                    }
                }
            }
            JavaSharedPreferences.awaitPendingSaves()
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    private fun entry(
        id: Long,
        title: String,
        categories: List<Long> = listOf(1L),
        source: Long = 10L,
        status: Int = SManga.ONGOING,
        totalChapters: Long = 10L,
        readCount: Long = 0L,
        bookmarkCount: Long = 0L,
        lastRead: Long = 0L,
        lastUpdate: Long = 0L,
        dateAdded: Long = 0L,
        fetchInterval: Int = 0,
        genre: List<String>? = null,
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = source,
            ogTitle = title,
            ogStatus = status.toLong(),
            lastUpdate = lastUpdate,
            dateAdded = dateAdded,
            fetchInterval = fetchInterval,
            ogGenre = genre,
        ),
        categories = categories,
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = bookmarkCount,
        bookmarkReadCount = 0L,
        chapterFlags = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = lastRead,
    )

    private fun track(mangaId: Long, trackerId: Long, score: Double) = Track(
        id = mangaId * 10 + trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 0L,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = score,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun newModel(
        items: List<LibraryManga> = emptyList(),
        categories: List<Category> = emptyList(),
    ): LibraryScreenModel {
        val suffix = System.nanoTime()
        val libraryPrefs = LibraryPreferences(DesktopPreferenceStore(JavaSharedPreferences("lib_proj_$suffix")))
        val desktopPrefs = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("desktop_proj_$suffix")))
        return LibraryScreenModel(
            getLibraryManga = mockk { every { subscribe() } returns flowOf(items) },
            getCategories = mockk { every { subscribe() } returns flowOf(categories) },
            getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
            preferences = libraryPrefs,
            downloadManager = mockk { every { changes } returns MutableStateFlow(0) },
            sourceManager = mockk(relaxed = true),
            trackerManager = mockk<TrackerManager>(relaxed = true) {
                every { loggedInTrackers() } returns emptyList()
                every { loggedInTrackersFlow() } returns MutableStateFlow(emptyList())
            },
            setSortModeForCategory = mockk(),
            setMangaCategories = mockk(),
            getChaptersByMangaId = mockk(),
            getMergedChaptersByMangaId = mockk(),
            updateChapter = mockk(),
            getNextChapters = mockk(),
            getManga = mockk(),
            mangaRepository = mockk(),
            setCustomMangaInfo = mockk(),
            sourcePreferences = mockk(relaxed = true),
            librarySearch = mockk(relaxed = true),
            extensionManager = mockk {
                every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
                every { availableExtensionsFlow } returns MutableStateFlow(emptyMap())
            },
            chaptersByEntry = mockk(relaxed = true),
            desktopPreferences = lazyOf(desktopPrefs),
            libraryUpdater = lazyOf(mockk(relaxed = true)),
        ).also { models += it }
    }

    @Test
    fun `pure projection filters downloaded, unread, started, bookmarked, completed, and lewd states`() {
        val items = listOf(
            entry(1, "Alpha", source = LocalSource.ID, totalChapters = 5, readCount = 5, status = SManga.COMPLETED),
            entry(2, "Bravo", totalChapters = 5, readCount = 2, bookmarkCount = 1, genre = listOf("Comedy")),
            entry(3, "Charlie", totalChapters = 5, readCount = 0, genre = listOf("Hentai")),
        )
        val base = LibraryViewInput(items = items, downloadCounts = mapOf(2L to 3))

        fun filterIds(transform: LibraryScreenModel.FilterPrefs.() -> LibraryScreenModel.FilterPrefs): List<Long> {
            val settings = LibraryScreenModel.Settings(filterPrefs = LibraryScreenModel.FilterPrefs().transform())
            return LibraryViewProjector.project(base.copy(settings = settings)).visible.map { it.id }
        }

        filterIds { copy(downloaded = TriState.ENABLED_IS) } shouldContainExactly listOf(1L, 2L)
        filterIds { copy(downloaded = TriState.ENABLED_NOT) } shouldContainExactly listOf(3L)
        filterIds { copy(unread = TriState.ENABLED_IS) } shouldContainExactly listOf(2L, 3L)
        filterIds { copy(unread = TriState.ENABLED_NOT) } shouldContainExactly listOf(1L)
        filterIds { copy(started = TriState.ENABLED_IS) } shouldContainExactly listOf(1L, 2L)
        filterIds { copy(bookmarked = TriState.ENABLED_IS) } shouldContainExactly listOf(2L)
        filterIds { copy(completed = TriState.ENABLED_IS) } shouldContainExactly listOf(1L)
        filterIds { copy(lewd = TriState.ENABLED_IS) } shouldContainExactly listOf(3L)
        filterIds { copy(lewd = TriState.ENABLED_NOT) } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `pure projection handles tabs, empty search categories, item metadata, and counts`() {
        val items = listOf(
            entry(1, "Alpha", categories = listOf(1L), source = 10L, totalChapters = 4, readCount = 4),
            entry(2, "Bravo", categories = listOf(1L, 2L), source = 20L, totalChapters = 4, readCount = 1),
            entry(3, "Charlie", categories = emptyList(), source = LocalSource.ID, totalChapters = 4, readCount = 0),
        )
        val categories = listOf(
            Category(0L, "Default", 0L, 0L, false),
            Category(1L, "Action", 1L, 0L, false),
            Category(2L, "Drama", 2L, 0L, false),
        )
        val unreadOnly = LibraryScreenModel.Settings(
            displayPrefs = LibraryScreenModel.DisplayPrefs(itemCounts = true, showEmptyCategoriesSearch = false),
            filterPrefs = LibraryScreenModel.FilterPrefs(unread = TriState.ENABLED_IS),
        )
        val input = LibraryViewInput(
            items = items,
            categories = categories,
            settings = unreadOnly,
            downloadCounts = mapOf(2L to 2),
            sources = mapOf(10L to ("SourceTen" to "en"), 20L to ("SourceTwenty" to "ja")),
            sourceIcons = mapOf(10L to "https://icon/10.png"),
            stubSources = setOf(20L),
        )

        val projected = LibraryViewProjector.project(input, withCounts = true)
        projected.entryCount shouldBe 3
        projected.tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)
        projected.countIn(null) shouldBe 2
        projected.countIn(1L) shouldBe 1
        projected.visible.map { it.id } shouldContainExactly listOf(2L, 3L)

        val secondItem = projected.visible.first { it.id == 2L }
        secondItem.downloadCount shouldBe 2
        secondItem.sourceName shouldBe "SourceTwenty"
        secondItem.language shouldBe "ja"
        secondItem.isLocal shouldBe false
        secondItem.isStubSource shouldBe true

        val localItem = projected.visible.first { it.id == 3L }
        localItem.isLocal shouldBe true

        // Filtering out everything in category 2 hides category 2 unless showEmptyCategoriesSearch is true
        val downloadedOnly = unreadOnly.copy(
            filterPrefs = LibraryScreenModel.FilterPrefs(downloaded = TriState.ENABLED_IS, unread = TriState.ENABLED_IS),
        )
        LibraryViewProjector.project(input.copy(settings = downloadedOnly)).tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)

        val localOnly = downloadedOnly.copy(
            filterPrefs = LibraryScreenModel.FilterPrefs(completed = TriState.ENABLED_IS),
        )
        LibraryViewProjector.project(input.copy(settings = localOnly)).tabs.map { it.id } shouldContainExactly listOf(1L)

        val keepEmpty = localOnly.copy(
            displayPrefs = localOnly.displayPrefs.copy(showEmptyCategoriesSearch = true),
        )
        LibraryViewProjector.project(input.copy(settings = keepEmpty)).tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)
    }

    @Test
    fun `pure sorting handles unread priority, tracker mean, tag list, random seed, and per-category sort`() {
        val items = listOf(
            entry(1, "Alpha", categories = listOf(1L), totalChapters = 10, readCount = 10, genre = listOf("comedy")),
            entry(2, "Bravo", categories = listOf(1L), totalChapters = 30, readCount = 25, genre = listOf("action")),
            entry(3, "Charlie", categories = listOf(1L), totalChapters = 20, readCount = 18, genre = listOf("romance")),
        )
        val tracks = mapOf(
            1L to listOf(track(1L, 1L, 9.0)),
            2L to listOf(track(2L, 1L, 5.0), track(2L, 2L, 7.0)),
        )

        fun sortedIds(sort: LibrarySort, sortTags: List<String> = emptyList(), seed: Int = 0): List<Long> {
            val settings = LibraryScreenModel.Settings(
                sortPrefs = LibraryScreenModel.SortPrefs(sort = sort, sortTags = sortTags, randomSeed = seed),
            )
            return LibraryViewProjector.sorted(items, sort, settings, tracks).map { it.id }
        }

        // Unread entries always come before entries with 0 unread, regardless of sort direction
        sortedIds(LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Ascending)) shouldContainExactly listOf(3L, 2L, 1L)
        sortedIds(LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending)) shouldContainExactly listOf(2L, 3L, 1L)

        // TrackerMean: unrated (-1.0) sorts below positive averages in ascending order
        sortedIds(LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Ascending)) shouldContainExactly listOf(3L, 2L, 1L)
        sortedIds(LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Descending)) shouldContainExactly listOf(1L, 2L, 3L)

        // TagList: entries without a matching tag (-1) come first ascending, last descending
        sortedIds(
            LibrarySort(LibrarySort.Type.TagList, LibrarySort.Direction.Ascending),
            sortTags = listOf("romance", "comedy"),
        ) shouldContainExactly listOf(2L, 3L, 1L)

        // Random is deterministic per seed
        val randomSort = LibrarySort(LibrarySort.Type.Random, LibrarySort.Direction.Ascending)
        sortedIds(randomSort, seed = 42) shouldContainExactly sortedIds(randomSort, seed = 42)

        // Per-category sort applies only when categorized is enabled
        val categorySort = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending)
        val cat = Category(1L, "One", 1L, categorySort.flag, false)
        val perCategoryInput = LibraryViewInput(
            items = items,
            categories = listOf(cat),
            selectedCategory = 1L,
            settings = LibraryScreenModel.Settings(
                sortPrefs = LibraryScreenModel.SortPrefs(
                    sort = LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending),
                    categorized = true,
                ),
            ),
        )
        LibraryViewProjector.project(perCategoryInput).visible.map { it.id } shouldContainExactly listOf(2L, 3L, 1L)
    }

    @Test
    fun `selection helpers check download capability, remote presence, and overflow flags`() {
        val local = entry(1L, "Local", source = LocalSource.ID)
        val modifiedManga = mockk<Manga>(relaxed = true) {
            every { id } returns 2L
            every { source } returns 10L
            every { ogTitle } returns "Original"
            every { title } returns "Edited"
        }
        val modified = LibraryManga(
            manga = modifiedManga,
            categories = listOf(0L),
            totalChapters = 0L,
            readCount = 0L,
            bookmarkCount = 0L,
            bookmarkReadCount = 0L,
            chapterFlags = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        )
        val items = listOf(local, modified)

        LibraryViewProjector.selectionCanDownload(items, setOf(1L)) shouldBe false
        LibraryViewProjector.selectionCanDownload(items, setOf(2L)) shouldBe true
        LibraryViewProjector.selectionHasRemote(items, setOf(1L)) shouldBe false
        LibraryViewProjector.selectionHasRemote(items, setOf(1L, 2L)) shouldBe true
        LibraryViewProjector.selectionFlags(items, setOf(1L)) shouldBe LibraryScreenModel.SelectionFlags(
            cleanTitles = false,
            addToMangadex = false,
            resetInfo = false,
        )
        LibraryViewProjector.selectionFlags(items, setOf(2L)) shouldBe LibraryScreenModel.SelectionFlags(
            cleanTitles = false,
            addToMangadex = false,
            resetInfo = true,
        )
    }

    @Test
    fun `typed preference combine updates nested settings without positional errors and resets tab on group change`() {
        val items = listOf(entry(1L, "Alpha", categories = listOf(1L)))
        val categories = listOf(Category(1L, "Action", 1L, 0L, false))
        val model = newModel(items = items, categories = categories)

        Await.until("initial load", 5_000, { model.state.value }) { !model.state.value.loading }

        model.selectCategory(1L)
        Await.until("category selected", 5_000, { model.state.value.selectedCategory }) {
            model.state.value.selectedCategory == 1L
        }

        val p = model.preferences
        p.displayMode().set(LibraryDisplayMode.List)
        p.landscapeColumns().set(4)
        p.showContinueReadingButton().set(true)
        p.categoryTabs().set(false)
        p.categoryNumberOfItems().set(true)
        p.showHiddenCategories().set(true)
        p.showEmptyCategoriesSearch().set(true)
        p.unreadBadge().set(false)
        p.downloadBadge().set(true)
        p.localBadge().set(false)
        p.languageBadge().set(false)
        p.sourceBadge().set(false)
        p.useLangIcon().set(false)
        p.sortingMode().set(LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending))
        p.randomSortSeed().set(99)
        p.categorizedDisplaySettings().set(true)
        p.filterUnread().set(TriState.ENABLED_IS)
        p.filterLewd().set(TriState.ENABLED_NOT)
        p.filterCategories().set(true)
        p.filterCategoriesInclude().set(setOf("1", "bad"))
        p.filterCategoriesExclude().set(setOf("2"))
        p.groupLibraryBy().set(LibraryGroup.BY_SOURCE)

        Await.until("typed settings updated", 5_000, { model.state.value }) {
            val current = model.state.value
            current.settings.display == LibraryDisplayMode.List &&
                current.settings.group == LibraryGroup.BY_SOURCE &&
                current.settings.includedCategories == setOf(1L)
        }

        val state = model.state.value
        state.selectedCategory shouldBe null
        state.settings.displayPrefs shouldBe LibraryScreenModel.DisplayPrefs(
            mode = LibraryDisplayMode.List,
            columns = 4,
            continueReading = true,
            categoryTabs = false,
            itemCounts = true,
            showHiddenCategories = true,
            showEmptyCategoriesSearch = true,
        )
        state.settings.badgePrefs shouldBe LibraryScreenModel.BadgePrefs(
            unread = false,
            download = true,
            local = false,
            language = false,
            source = false,
            useLangIcon = false,
        )
        state.settings.sortPrefs shouldBe LibraryScreenModel.SortPrefs(
            sort = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending),
            randomSeed = 99,
            categorized = true,
            sortTags = emptyList(),
            group = LibraryGroup.BY_SOURCE,
        )
        state.settings.filterPrefs.categories shouldBe LibraryScreenModel.CategoryFilterPrefs(
            enabled = true,
            included = setOf(1L),
            excluded = setOf(2L),
        )
        state.settings.unread shouldBe TriState.ENABLED_IS
        state.settings.lewd shouldBe TriState.ENABLED_NOT
    }

    @Test
    fun `sealed LibraryDialog enforces at most one active dialog and leaves view projection untouched`() {
        val model = newModel(
            items = listOf(entry(1L, "Alpha", categories = listOf(1L))),
            categories = listOf(Category(1L, "Action", 1L, 0L, false)),
        )
        Await.until("initial load", 5_000, { model.state.value }) {
            !model.state.value.loading && model.state.value.visible.size == 1
        }
        val initialView = model.state.value.view
        model.state.value.dialog shouldBe null

        model.openDialog(LibraryDialog.Settings)
        model.state.value.dialog shouldBe LibraryDialog.Settings
        (model.state.value.view === initialView) shouldBe true

        val categoriesDialog = LibraryDialog.Categories(setOf(1L, 2L))
        model.openDialog(categoriesDialog)
        model.state.value.dialog shouldBe categoriesDialog
        model.state.value.dialog shouldNotBe LibraryDialog.Settings

        val removeDialog = LibraryDialog.Remove(setOf(1L))
        model.openDialog(removeDialog)
        model.state.value.dialog shouldBe removeDialog

        val deleteDownloads = LibraryDialog.DeleteDownloads(setOf(1L))
        model.openDialog(deleteDownloads)
        model.state.value.dialog shouldBe deleteDownloads

        model.openDialog(LibraryDialog.FavoritesSyncNotes)
        model.state.value.dialog shouldBe LibraryDialog.FavoritesSyncNotes

        model.openDialog(LibraryDialog.FavoritesSyncConfirm)
        model.state.value.dialog shouldBe LibraryDialog.FavoritesSyncConfirm

        val manga = Manga.create().copy(id = 1L, ogTitle = "Alpha")
        val recs = LibraryDialog.Recommendations(listOf(manga))
        model.openDialog(recs)
        model.state.value.dialog shouldBe recs

        val removeMerged = LibraryDialog.RemoveMerged(listOf(manga), mergedMangaId = 99L)
        model.openDialog(removeMerged)
        model.state.value.dialog shouldBe removeMerged

        model.dismissDialog()
        model.state.value.dialog shouldBe null
        (model.state.value.view === initialView) shouldBe true
    }
}
