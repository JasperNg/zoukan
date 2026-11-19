package zoukan.desktop.ui.library

import androidx.compose.ui.unit.Density
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.SearchEngine
import exh.source.EH_SOURCE_ID
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.LanguageIcons
import java.util.concurrent.Executors

/**
 * Tests for library view projection, filtering, grouping, search queries, and pure projection caching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryProjectionAndFilteringTest {

    @Nested
    inner class ViewProjector {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val models = mutableListOf<LibraryScreenModel>()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
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
            val libraryPrefs = LibraryPreferences(TestPreferenceStore())
            val desktopPrefs = DesktopPreferences(TestPreferenceStore())
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
                entry(1, "Alpha", categories = listOf(1L), source = 10L, status = SManga.COMPLETED, totalChapters = 4, readCount = 4),
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

            // Filtering to completed entries leaves only entry 1 in category 1, hiding empty tabs unless showEmptyCategoriesSearch is true
            val completedOnly = unreadOnly.copy(
                filterPrefs = LibraryScreenModel.FilterPrefs(completed = TriState.ENABLED_IS),
            )
            LibraryViewProjector.project(input.copy(settings = completedOnly)).tabs.map { it.id } shouldContainExactly listOf(1L)

            val keepEmptyFilter = completedOnly.copy(
                displayPrefs = completedOnly.displayPrefs.copy(showEmptyCategoriesSearch = true),
            )
            LibraryViewProjector.project(input.copy(settings = keepEmptyFilter)).tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)

            // Searching also hides empty category tabs unless showEmptyCategoriesSearch is true
            val searchInput = input.copy(
                query = "Alpha",
                searchResults = setOf(1L),
                settings = LibraryScreenModel.Settings(
                    displayPrefs = LibraryScreenModel.DisplayPrefs(showEmptyCategoriesSearch = false),
                ),
            )
            LibraryViewProjector.project(searchInput).tabs.map { it.id } shouldContainExactly listOf(1L)

            val keepEmptySearch = searchInput.copy(
                settings = LibraryScreenModel.Settings(
                    displayPrefs = LibraryScreenModel.DisplayPrefs(showEmptyCategoriesSearch = true),
                ),
            )
            LibraryViewProjector.project(keepEmptySearch).tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)
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
                    current.settings.showEmptyCategoriesSearch &&
                    !current.settings.useLangIcon &&
                    current.settings.group == LibraryGroup.BY_SOURCE &&
                    current.settings.includedCategories == setOf(1L) &&
                    current.settings.excludedCategories == setOf(2L) &&
                    current.settings.lewd == TriState.ENABLED_NOT
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
                !model.state.value.loading &&
                    model.state.value.visible.size == 1 &&
                    model.state.value.settings.skipOutsideReleasePeriod
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

    /** KMK's library filters and display options, as Android's LibraryScreenModel.applyFilters. */
    @Nested
    inner class Filters {

        private fun entry(id: Long, categories: List<Long>, fetchInterval: Int = 0) = LibraryManga(
            manga = Manga.create().copy(id = id, ogTitle = "Entry $id", fetchInterval = fetchInterval),
            categories = categories,
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        private fun category(id: Long, hidden: Boolean = false) = Category(id = id, name = "Category $id", order = id, flags = 0, hidden = hidden)

        private val items = listOf(
            entry(1, listOf(1L)),
            entry(2, listOf(1L, 2L)),
            entry(3, listOf(2L), fetchInterval = -3),
            entry(4, listOf(0L)),
        )

        private fun input(
            settings: LibraryScreenModel.Settings,
            tracks: Map<Long, List<Track>> = emptyMap(),
            downloadCounts: Map<Long, Int> = emptyMap(),
        ) = LibraryViewInput(
            items = items,
            categories = listOf(category(0), category(1), category(2, hidden = true)),
            settings = settings,
            tracks = tracks,
            downloadCounts = downloadCounts,
        )

        private fun LibraryViewInput.projected() = LibraryViewProjector.project(this)
        private fun LibraryViewInput.shown() = projected().visible.map { it.id }

        @Test
        fun `included categories need all of them, excluded ones none`() {
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(
                        categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L)),
                    ),
                ),
            ).shown() shouldContainExactly listOf(1L, 2L)
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(
                        categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L, 2L)),
                    ),
                ),
            ).shown() shouldContainExactly listOf(2L)
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(
                        categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, excluded = setOf(2L)),
                    ),
                ),
            ).shown() shouldContainExactly listOf(1L, 4L)
            // Off, the chosen categories do nothing
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(
                        categories = LibraryScreenModel.CategoryFilterPrefs(included = setOf(1L)),
                    ),
                ),
            ).shown().size shouldBe 4
        }

        @Test
        fun `including categories shows the library ungrouped`() {
            val filtered = input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(
                        categories = LibraryScreenModel.CategoryFilterPrefs(enabled = true, included = setOf(1L)),
                    ),
                ),
            )
            filtered.settings.effectiveGroup shouldBe LibraryGroup.UNGROUPED
            filtered.projected().tabs.size shouldBe 0
            filtered.settings.hasFilter shouldBe true
        }

        @Test
        fun `hidden categories show with Show hidden categories`() {
            input(LibraryScreenModel.Settings()).projected().tabs.map { it.id } shouldContainExactly listOf(0L, 1L)
            input(
                LibraryScreenModel.Settings(
                    displayPrefs = LibraryScreenModel.DisplayPrefs(showHiddenCategories = true),
                ),
            ).projected().tabs.map { it.id } shouldContainExactly listOf(0L, 1L, 2L)
        }

        @Test
        fun `customized update frequency applies with the release period restriction`() {
            val only = LibraryScreenModel.FilterPrefs(intervalCustom = TriState.ENABLED_IS)
            input(LibraryScreenModel.Settings(filterPrefs = only)).shown().size shouldBe 4
            input(LibraryScreenModel.Settings(filterPrefs = only.copy(skipOutsideReleasePeriod = true))).shown() shouldContainExactly listOf(3L)
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = only.copy(intervalCustom = TriState.ENABLED_NOT, skipOutsideReleasePeriod = true),
                ),
            ).shown() shouldContainExactly listOf(1L, 2L, 4L)
        }

        @Test
        fun `tracker filters match any included tracker`() {
            fun track(manga: Long, tracker: Long) = Track(
                id = manga * 10 + tracker, mangaId = manga, trackerId = tracker, remoteId = 0, libraryId = null, title = "",
                lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "", startDate = 0, finishDate = 0, private = false,
            )
            val tracks = mapOf(1L to listOf(track(1, 1)), 2L to listOf(track(2, 2)), 3L to listOf(track(3, 1), track(3, 2)))
            val both = LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(tracking = mapOf(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_IS)),
            )
            input(both, tracks).shown() shouldContainExactly listOf(1L, 2L, 3L)
            val notTwo = LibraryScreenModel.Settings(
                filterPrefs = LibraryScreenModel.FilterPrefs(tracking = mapOf(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_NOT)),
            )
            input(notTwo, tracks).shown() shouldContainExactly listOf(1L)
        }

        @Test
        fun `search results narrow the library once they arrive`() {
            val searching = input(LibraryScreenModel.Settings()).copy(query = "x")
            searching.shown().size shouldBe 4
            searching.copy(searchResults = setOf(2L)).shown() shouldContainExactly listOf(2L)
        }

        @Test
        fun `downloaded filter keeps only downloaded entries and restores all when disabled`() {
            val counts = mapOf(2L to 1, 4L to 0)
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(downloaded = TriState.ENABLED_IS),
                ),
                downloadCounts = counts,
            ).shown() shouldContainExactly listOf(2L)
            input(
                LibraryScreenModel.Settings(
                    filterPrefs = LibraryScreenModel.FilterPrefs(downloaded = TriState.DISABLED),
                ),
                downloadCounts = counts,
            ).shown() shouldContainExactly listOf(1L, 2L, 3L, 4L)
        }

        @Test
        fun `language flags load`() {
            listOf("en", "ja", "all", "zh-Hant", "es-419", "eo", "ko").forEach { LanguageIcons.iconName(it).shouldNotBeNull() }
            // Every flag the build copies, gradients and all, reads as a Compose vector
            val flags = java.io.File("../flagkit/src/main/res/drawable").listFiles().orEmpty().map { it.nameWithoutExtension }
            (flags.size > 200) shouldBe true
            (flags + listOf("globe", "ic_flag_un", "ic_flag_esperanto")).forEach { LanguageIcons.load(it, Density(1f)).shouldNotBeNull() }
        }
    }

    @Nested
    inner class Grouping {

        private fun entry(id: Long, source: Long, status: Int) = LibraryManga(
            manga = Manga.create().copy(id = id, source = source, ogStatus = status.toLong(), ogTitle = "Entry $id"),
            categories = listOf(0L),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        private val items = listOf(
            entry(1, source = 10, status = SManga.ONGOING),
            entry(2, source = 20, status = SManga.COMPLETED),
            entry(3, source = 10, status = SManga.COMPLETED),
            entry(4, source = LocalSource.ID, status = SManga.UNKNOWN),
        )

        private fun input(group: Int) = LibraryViewInput(
            items = items,
            settings = LibraryScreenModel.Settings(sortPrefs = LibraryScreenModel.SortPrefs(group = group)),
            sources = mapOf(10L to ("Zeta" to "en"), 20L to ("Alpha" to "ja")),
            trackStatuses = mapOf(1L to listOf(TrackStatus.READING), 3L to listOf(TrackStatus.READING, TrackStatus.COMPLETED)),
        )

        @Test
        fun `source groups are named after the source, in name order`() {
            val inp = input(LibraryGroup.BY_SOURCE)
            val projected = LibraryViewProjector.project(inp)
            projected.tabs.map { it.name } shouldContainExactly listOf("Alpha", "Local source", "Zeta")
            LibraryViewProjector.inCategory(inp.items, 10L, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(1L, 3L)
            // Local's id is the Default category's, which grouping must not treat as Default
            LibraryViewProjector.inCategory(inp.items, LocalSource.ID, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(4L)
        }

        @Test
        fun `status groups follow Komikku's order, unknown last`() {
            val inp = input(LibraryGroup.BY_STATUS)
            val projected = LibraryViewProjector.project(inp)
            projected.tabs.map { it.name } shouldContainExactly listOf("Ongoing", "Completed", "Unknown")
            LibraryViewProjector.inCategory(inp.items, SManga.COMPLETED + 1L, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(2L, 3L)
        }

        @Test
        fun `an entry with several tracking statuses is in each, untracked ones in Not tracked`() {
            val inp = input(LibraryGroup.BY_TRACK_STATUS)
            val projected = LibraryViewProjector.project(inp)
            projected.tabs.map { it.id } shouldContainExactly listOf(1L, 5L, 7L)
            LibraryViewProjector.inCategory(inp.items, TrackStatus.READING.int.toLong(), grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(1L, 3L)
            LibraryViewProjector.inCategory(inp.items, TrackStatus.OTHER.int.toLong(), grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(2L, 4L)
        }

        @Test
        fun `group tab updates follow the library dynamic category updates setting`() {
            val bySource = input(LibraryGroup.BY_SOURCE)
            val bySourceGroups = LibraryViewProjector.project(bySource).groups
            LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.GLOBAL, bySourceGroups) shouldBe null
            LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.ALL_BUT_UNGROUPED, bySourceGroups) shouldBe setOf(1L, 3L)
            LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.ALL, bySourceGroups) shouldBe setOf(1L, 3L)

            val ungrouped = input(LibraryGroup.UNGROUPED)
            val ungroupedGroups = LibraryViewProjector.project(ungrouped).groups
            LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.GLOBAL, ungroupedGroups) shouldBe null
            LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.ALL_BUT_UNGROUPED, ungroupedGroups) shouldBe null
            LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.ALL, ungroupedGroups) shouldBe setOf(1L, 2L, 3L, 4L)
        }

        @Test
        fun `ungrouped shows only All`() {
            val inp = input(LibraryGroup.UNGROUPED)
            val projected = LibraryViewProjector.project(inp)
            projected.tabs.size shouldBe 0
            LibraryViewProjector.inCategory(inp.items, null, grouped = true, projected.groups).size shouldBe 4
        }
    }

    @Nested
    inner class Search {

        private fun entry(id: Long, source: Long, title: String, genre: List<String>? = null, author: String? = null) = LibraryManga(
            manga = Manga.create().copy(id = id, source = source, ogTitle = title, ogGenre = genre, ogAuthor = author),
            categories = listOf(0L),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        private val items = listOf(
            // An E-Hentai gallery with metadata: searched by its tags and titles, not its genres
            entry(1, EH_SOURCE_ID, "Gallery one", genre = listOf("ignored")),
            entry(2, 10, "Blue Sky", genre = listOf("Action", "Comedy"), author = "Someone"),
            entry(3, 10, "Red Moon", genre = listOf("Drama")),
            entry(4, LocalSource.ID, "Local book"),
        )

        private val search = LibrarySearch(
            searchEngine = SearchEngine(),
            sourceManager = mockk<SourceManager>().also { every { it.get(any<Long>()) } returns null },
            trackerManager = mockk<TrackerManager> { every { loggedInTrackers() } returns emptyList() },
            getIdsOfFavoriteMangaWithMetadata = mockk { coEvery { await() } returns listOf(1L) },
            getSearchTags = mockk<GetSearchTags> {
                coEvery { await(1L) } returns listOf(
                    SearchTag(null, 1L, "artist", "Jane Doe", 0),
                    SearchTag(null, 1L, "female", "glasses", 0),
                )
            },
            getSearchTitles = mockk<GetSearchTitles> { coEvery { await(1L) } returns listOf(SearchTitle(null, 1L, "Alt title", 0)) },
        )

        private fun find(query: String) = runBlocking { search.search(items, query, emptyMap()) }

        @Test
        fun `plain text matches titles, authors and genres`() {
            find("sky") shouldContainExactlyInAnyOrder listOf(2L)
            find("someone") shouldContainExactlyInAnyOrder listOf(2L)
            find("drama") shouldContainExactlyInAnyOrder listOf(3L)
        }

        @Test
        fun `namespaced tags match metadata tags`() {
            find("artist:jane") shouldContainExactlyInAnyOrder listOf(1L)
            find("female:glasses") shouldContainExactlyInAnyOrder listOf(1L)
            find("artist:nobody") shouldContainExactlyInAnyOrder emptyList()
        }

        @Test
        fun `metadata titles match, metadata entries skip their genres`() {
            find("alt title") shouldContainExactlyInAnyOrder listOf(1L)
            find("ignored") shouldContainExactlyInAnyOrder emptyList()
        }

        @Test
        fun `a dash excludes`() {
            find("-sky") shouldContainExactlyInAnyOrder listOf(1L, 3L, 4L)
            find("-artist:jane") shouldContainExactlyInAnyOrder listOf(2L, 3L, 4L)
        }

        @Test
        fun `an excluded word keeps local entries that do not contain it`() {
            find("-sky") shouldContainExactlyInAnyOrder listOf(1L, 3L, 4L)
            find("-drama") shouldContainExactlyInAnyOrder listOf(1L, 2L, 4L)
            // A local entry that does contain the word is still excluded
            find("-local") shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
        }

        @Test
        fun `id and src pick entries and sources`() {
            find("id:3") shouldContainExactlyInAnyOrder listOf(3L)
            find("src:10") shouldContainExactlyInAnyOrder listOf(2L, 3L)
            find("src:local") shouldContainExactlyInAnyOrder listOf(4L)
        }
    }

    /** Pure projection computes deterministic, immutable derived lists without mutating state. */
    @Nested
    inner class StateCache {

        private fun entry(id: Long, title: String, categories: List<Long>) = LibraryManga(
            manga = Manga.create().copy(id = id, ogTitle = title),
            categories = categories,
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        private val input = LibraryViewInput(
            items = listOf(entry(1, "b", listOf(1L)), entry(2, "a", listOf(1L)), entry(3, "c", listOf(2L)), entry(2, "a", listOf(2L))),
            categories = listOf(Category(1, "One", 1, 0, false), Category(2, "Two", 2, 0, false)),
        )

        @Test
        fun `visible, tabs and counts are deterministic for a given input`() {
            val first = LibraryViewProjector.project(input, withCounts = true)
            val second = LibraryViewProjector.project(input, withCounts = true)

            first shouldBe second
            first.countIn(1L) shouldBe 2
            first.countIn(null) shouldBe 3
            first.entryCount shouldBe 3
        }

        @Test
        fun `a changed input recomputes what it shows without mutating prior projections`() {
            val initial = LibraryViewProjector.project(input, withCounts = true)
            initial.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)

            val filtered = LibraryViewProjector.project(input.copy(selectedCategory = 2L), withCounts = true)

            filtered.visible.map { it.id } shouldContainExactly listOf(2L, 3L)
            filtered.countIn(2L) shouldBe 2
            initial.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)
        }
    }
}
