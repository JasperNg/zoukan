package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.screenModelScope
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
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.testing.Await
import java.util.concurrent.Executors

/** Changing only the selection must not refilter or resort a large library. */
@OptIn(ForTestsOnly::class, ExperimentalCoroutinesApi::class)
class LibraryScreenModelSelectionTest {

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
