package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
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
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import java.util.concurrent.Executors

/**
 * Tests for library selection mechanics, selection download capabilities, and [LibraryScreenModel]
 * state/preference reactivity.
 */
@OptIn(ForTestsOnly::class, ExperimentalCoroutinesApi::class)
class LibrarySelectionAndStateTest {

    /** Selecting in the library as in a file manager: Ctrl/Cmd + click toggles, Shift + click selects a range. */
    @Nested
    inner class ClickSelection {

        @Test
        fun `modifier clicks toggle one entry and select a range from the last toggled one`() {
            clickKind(shift = false, ctrl = false, meta = false) shouldBe ClickKind.Plain
            clickKind(shift = false, ctrl = true, meta = false) shouldBe ClickKind.Toggle
            clickKind(shift = false, ctrl = false, meta = true) shouldBe ClickKind.Toggle
            clickKind(shift = true, ctrl = true, meta = false) shouldBe ClickKind.Range

            val shown = (1L..10L).toList()
            // Ctrl + click 3, then Shift + click 7: entries 3 to 7
            var selection = toggledSelection(emptySet(), 3L)
            selection = selection + rangeSelection(shown, anchor = 3L, id = 7L)!!
            selection.size shouldBe 5
            // Ctrl + click 5 takes it out again
            toggledSelection(selection, 5L).size shouldBe 4
            // Shift + click backwards from the anchor, and with no anchor shown: only the clicked entry
            rangeSelection(shown, anchor = 7L, id = 2L) shouldBe listOf(2L, 3L, 4L, 5L, 6L, 7L)
            rangeSelection(shown, anchor = 42L, id = 4L) shouldBe listOf(4L)
            rangeSelection(shown, anchor = 3L, id = 42L) shouldBe null
        }
    }

    /** The selection bar's download actions, as Android's LibraryTab shows them. */
    @Nested
    inner class SelectionDownloadCapabilities {

        private fun entry(id: Long, source: Long) = LibraryManga(
            manga = Manga.create().copy(id = id, source = source, ogTitle = "title $id"),
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

        private val state = LibraryScreenModel.State(
            loading = false,
            items = listOf(entry(1, LocalSource.ID), entry(2, LocalSource.ID), entry(3, 42L)),
        )

        @Test
        fun `a selection of local entries offers no download actions`() {
            val local = state.copy(selection = setOf(1L, 2L))
            local.selectionCanDownload shouldBe false
            local.selectionHasRemote shouldBe false
        }

        @Test
        fun `a mixed selection hides Download but keeps Delete downloads`() {
            val mixed = state.copy(selection = setOf(1L, 3L))
            mixed.selectionCanDownload shouldBe false
            mixed.selectionHasRemote shouldBe true
        }

        @Test
        fun `a selection of source entries offers both`() {
            val remote = state.copy(selection = setOf(3L))
            remote.selectionCanDownload shouldBe true
            remote.selectionHasRemote shouldBe true
        }
    }

    /** Changing only the selection must not refilter or resort a large library. */
    @Nested
    inner class ScreenModelSelection {

        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val models = mutableListOf<LibraryScreenModel>()

        @BeforeEach
        fun setUp() {
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

        private val items = (1L..5000L).map { entry(it, "title $it", listOf(if (it % 2 == 0L) 1L else 2L)) }
        private val categories = listOf(Category(1, "One", 1, 0, false), Category(2, "Two", 2, 0, false))

        private fun newModel(librarySearch: LibrarySearch = mockk(relaxed = true)): LibraryScreenModel {
            val suffix = System.nanoTime()
            val libraryPrefs = LibraryPreferences(DesktopPreferenceStore(JavaSharedPreferences("lib_sel_$suffix"))).apply {
                categoryNumberOfItems().set(true)
            }
            val desktopPrefs = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("desktop_sel_$suffix")))
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
                librarySearch = librarySearch,
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
        fun `a selection change keeps the shown list, tabs and counts`() {
            val model = newModel()
            Await.until("initial projection", 5_000, { model.state.value }) {
                !model.state.value.loading && model.state.value.visible.size == 5000
            }

            val before = model.state.value
            val visible = before.visible
            val tabs = before.tabs
            val count = before.countIn(1L)

            model.toggleSelection(1L)
            model.toggleSelection(2L)

            val selected = model.state.value
            (selected.visible === visible) shouldBe true
            (selected.tabs === tabs) shouldBe true
            selected.countIn(1L) shouldBe count
            selected.selection shouldBe setOf(1L, 2L)
        }

        @Test
        fun `a selection change does not change what the state shows`() {
            val state = LibraryScreenModel.State(loading = false, items = items, categories = categories)
            state.sameView(state.copy(selection = setOf(1L))) shouldBe true
            state.sameView(state.copy(dialog = LibraryDialog.Settings)) shouldBe true
            state.sameView(state.copy(query = "x")) shouldBe false
        }

        @Test
        fun `a query or filter change still recomputes`() {
            val search = mockk<LibrarySearch> {
                coEvery { search(any(), eq("c"), any()) } returns setOf(3L)
            }
            val model = newModel(librarySearch = search)
            Await.until("initial projection", 5_000, { model.state.value }) {
                !model.state.value.loading && model.state.value.visible.size == 5000
            }
            val initialVisible = model.state.value.visible

            model.selectCategory(2L)
            Await.until("category projection", 5_000, { model.state.value }) {
                model.state.value.selectedCategory == 2L && model.state.value.visible.size == 2500
            }
            model.state.value.visible shouldNotBe initialVisible

            model.selectCategory(null)
            model.search("c")
            Await.until("search projection", 5_000, { model.state.value }) {
                model.state.value.searchResults == setOf(3L) && model.state.value.visible.map { it.id } == listOf(3L)
            }
            model.state.value.visible.map { it.id } shouldBe listOf(3L)
        }
    }

    @Nested
    inner class TrackerFilterState {

        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val models = mutableListOf<LibraryScreenModel>()

        @BeforeEach
        fun setUp() {
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

        private fun tracker(id: Long): BaseTracker = mockk(relaxed = true) {
            every { this@mockk.id } returns id
        }

        private fun newModel(loggedIn: MutableStateFlow<List<BaseTracker>>): LibraryScreenModel {
            val suffix = System.nanoTime()
            val libraryPrefs = LibraryPreferences(DesktopPreferenceStore(JavaSharedPreferences("lib_tracker_filter_$suffix")))
            val desktopPrefs = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("desktop_tracker_filter_$suffix")))
            return LibraryScreenModel(
                getLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
                getCategories = mockk { every { subscribe() } returns flowOf(emptyList()) },
                getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
                preferences = libraryPrefs,
                downloadManager = mockk { every { changes } returns MutableStateFlow(0) },
                sourceManager = mockk(relaxed = true),
                trackerManager = mockk<TrackerManager>(relaxed = true) {
                    every { loggedInTrackers() } answers { loggedIn.value }
                    every { loggedInTrackersFlow() } returns loggedIn
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
        fun `logging in a tracker after init makes its filter preference update state`() {
            val loggedIn = MutableStateFlow<List<BaseTracker>>(emptyList())
            val model = newModel(loggedIn)

            Await.until("initial library load", 5_000, { model.state.value }) { !model.state.value.loading }
            model.state.value.settings.tracking shouldBe emptyMap()

            val tracker = tracker(TrackerManager.ANILIST)
            loggedIn.value = listOf(tracker)
            Await.until("tracker appears in settings", 5_000, { model.state.value.settings.tracking }) {
                tracker.id in model.state.value.settings.tracking
            }

            model.preferences.filterTracking(tracker.id.toInt()).set(TriState.ENABLED_IS)
            Await.until("tracker filter updates", 5_000, { model.state.value.settings.tracking }) {
                model.state.value.settings.tracking[tracker.id] == TriState.ENABLED_IS
            }
            model.state.value.settings.tracking shouldBe mapOf(tracker.id to TriState.ENABLED_IS)
        }

        @Test
        fun `logging out a tracker removes its entry from settings tracking`() {
            val anilist = tracker(TrackerManager.ANILIST)
            val kitsu = tracker(TrackerManager.KITSU)
            val loggedIn = MutableStateFlow(listOf(anilist, kitsu))
            val model = newModel(loggedIn)

            model.preferences.filterTracking(anilist.id.toInt()).set(TriState.ENABLED_IS)
            model.preferences.filterTracking(kitsu.id.toInt()).set(TriState.ENABLED_NOT)

            Await.until("both trackers loaded", 5_000, { model.state.value.settings.tracking }) {
                model.state.value.settings.tracking == mapOf(anilist.id to TriState.ENABLED_IS, kitsu.id to TriState.ENABLED_NOT)
            }

            loggedIn.value = listOf(kitsu)
            Await.until("logged-out tracker removed", 5_000, { model.state.value.settings.tracking }) {
                anilist.id !in model.state.value.settings.tracking
            }
            model.state.value.settings.tracking shouldBe mapOf(kitsu.id to TriState.ENABLED_NOT)

            loggedIn.value = emptyList()
            Await.until("all trackers removed", 5_000, { model.state.value.settings.tracking }) {
                model.state.value.settings.tracking.isEmpty()
            }
            model.state.value.settings.tracking shouldBe emptyMap()
        }
    }
}
