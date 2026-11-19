package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.i18n.MR
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.testing.Await
import zoukan.desktop.ui.browse.BulkFavorite
import zoukan.desktop.ui.browse.BulkLibraryAdd
import zoukan.desktop.ui.browse.QuickLibraryToggle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ListSelectionAndAddControllerTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, "common-test-main") }.asCoroutineDispatcher()
    private val toastCollectorScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toasts = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
        toasts.clear()
        toastCollectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            Toasts.flow.collect { toasts += it }
        }
    }

    @AfterEach
    fun tearDown() {
        toastCollectorScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `ListSelection supports select, toggle, group toggle, range, selectAll, invert, retain, and clear`() {
        val ordered = listOf(10L, 11L, 12L, 13L, 14L)
        var sel = ListSelection<Long>()
        sel.selecting shouldBe false

        // Explicit selection mode with no IDs yet
        sel = sel.start()
        sel.selecting shouldBe true
        sel.isEmpty shouldBe true

        // Select & toggle
        sel = sel.select(11L)
        sel.ids shouldBe setOf(11L)
        sel = sel.select(listOf(11L, 12L))
        sel.ids shouldBe setOf(11L, 12L)
        sel = sel.toggle(12L)
        sel.ids shouldBe setOf(11L)

        // Range selection downward, upward, empty anchor, and missing target
        sel = sel.selectRange(ordered, 13L)
        sel.ids shouldBe setOf(11L, 12L, 13L)
        ListSelection(setOf(13L)).selectRange(ordered, 10L).ids shouldBe setOf(10L, 11L, 12L, 13L)
        ListSelection<Long>().selectRange(ordered, 12L).ids shouldBe setOf(12L)
        sel.selectRange(ordered, 999L).ids shouldBe setOf(11L, 12L, 13L)

        // Invert and selectAll
        sel.invert(ordered).ids shouldBe setOf(10L, 14L)
        sel.selectAll(ordered).ids shouldBe ordered.toSet()

        // Group toggle uses clicked anchor state
        val group = listOf(10L, 11L)
        ListSelection(setOf(12L)).toggleGroup(group, clicked = 10L).ids shouldBe setOf(10L, 11L, 12L)
        ListSelection(setOf(10L, 11L, 12L)).toggleGroup(group, clicked = 10L).ids shouldBe setOf(12L)

        // Retain drops removed items and clear resets active + ids
        val retained = sel.retain(setOf(11L, 13L, 99L))
        retained.ids shouldBe setOf(11L, 13L)
        retained.clear().let { cleared ->
            cleared.ids shouldBe emptySet()
            cleared.active shouldBe false
            cleared.selecting shouldBe false
        }
    }

    @Test
    fun `LibraryAddController single flow handles confirmRemove, duplicates, categories, add, remove, and category edit`() = runBlocking {
        val cat = Category(id = 7L, name = "Sci-Fi", order = 0L, flags = 0L, hidden = false)
        val sysCat = Category(id = 0L, name = "Default", order = 0L, flags = 0L, hidden = false)
        val nonFav = Manga.create().copy(id = 1L, ogTitle = "One", favorite = false)
        val fav = Manga.create().copy(id = 2L, ogTitle = "Two", favorite = true)
        val dup = Manga.create().copy(id = 3L, ogTitle = "One", favorite = true)

        val addToLibrary = mockk<AddToLibrary>(relaxed = true)
        val getCategories = mockk<GetCategories> {
            coEvery { await() } returns listOf(sysCat, cat)
            coEvery { await(2L) } returns listOf(cat)
        }
        val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
        val controller = LibraryAddController(
            addToLibrary = { addToLibrary },
            getCategories = { getCategories },
            setMangaCategories = { setMangaCategories },
        )

        // Favorite entry: confirmRemove = true asks; confirmRemove = false removes immediately
        controller.toggleSingle(fav, confirmRemove = true, fetchOnAdd = true)
            .shouldBeInstanceOf<LibraryAddController.SingleResult.Prompt>()
            .prompt.shouldBeInstanceOf<LibraryAddController.SinglePrompt.ConfirmRemove>()

        controller.toggleSingle(fav, confirmRemove = false, fetchOnAdd = false)
            .shouldBeInstanceOf<LibraryAddController.SingleResult.Removed>()
        coVerify { addToLibrary.remove(fav) }

        // Non-favorite with duplicate match prompts Duplicates
        coEvery { addToLibrary.duplicates(nonFav) } returns listOf(MangaWithChapterCount(dup, 5L))
        controller.toggleSingle(nonFav, confirmRemove = true, fetchOnAdd = true)
            .shouldBeInstanceOf<LibraryAddController.SingleResult.Prompt>()
            .prompt.shouldBeInstanceOf<LibraryAddController.SinglePrompt.Duplicates>()

        // No duplicates + ask plan prompts Categories; default plan adds directly
        coEvery { addToLibrary.duplicates(nonFav) } returns emptyList()
        coEvery { addToLibrary.plan() } returns AddToLibrary.Plan(emptyList(), ask = listOf(cat))
        controller.toggleSingle(nonFav, confirmRemove = false, fetchOnAdd = true)
            .shouldBeInstanceOf<LibraryAddController.SingleResult.Prompt>()
            .prompt.shouldBeInstanceOf<LibraryAddController.SinglePrompt.Categories>()

        coEvery { addToLibrary.plan() } returns AddToLibrary.Plan(listOf(cat.id), ask = null)
        controller.toggleSingle(nonFav, confirmRemove = false, fetchOnAdd = true)
            .shouldBeInstanceOf<LibraryAddController.SingleResult.Added>()
        coVerify { addToLibrary.add(nonFav, listOf(7L), fetchOnAdd = true) }

        // Category edit filters system categories and delegates updates
        controller.loadCategoryEdit(2L) shouldBe (listOf(cat) to setOf(7L))
        controller.setCategories(2L, setOf(7L))
        coVerify { setMangaCategories.await(2L, listOf(7L)) }
    }

    @Test
    fun `QuickLibraryToggle and BulkFavorite delegate add flows through LibraryAddController`() {
        val cat = Category(id = 4L, name = "Fantasy", order = 0L, flags = 0L, hidden = false)
        val alpha = Manga.create().copy(id = 10L, ogTitle = "Alpha", favorite = false)
        val bravo = Manga.create().copy(id = 11L, ogTitle = "Bravo", favorite = false)
        val existing = Manga.create().copy(id = 12L, ogTitle = "Alpha", favorite = true)

        val addToLibrary = mockk<AddToLibrary>(relaxed = true) {
            coEvery { duplicates(alpha) } returns listOf(MangaWithChapterCount(existing, 2L))
            coEvery { duplicates(bravo) } returns emptyList()
            coEvery { plan() } returns AddToLibrary.Plan(emptyList(), ask = listOf(cat))
        }
        val getManga = mockk<GetManga> {
            coEvery { await(10L) } returns alpha
            coEvery { await(11L) } returns bravo
        }
        val controller = LibraryAddController(addToLibrary = { addToLibrary })
        val toggleScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + main)
        try {
            val toggle = QuickLibraryToggle(toggleScope, controller, getManga = { getManga })
            toggle.toggle(alpha)
            Await.until("quick toggle duplicate prompt", 5_000) { toggle.duplicates != null }
            toggle.duplicates?.first?.id shouldBe 10L

            toggle.addAnyway(alpha)
            Await.until("quick toggle category prompt", 5_000) { toggle.categories != null }
            toggle.categories?.second shouldBe listOf(cat)

            toggle.add(alpha, listOf(4L))
            Await.until("quick toggle added toast", 5_000) {
                localized(MR.strings.manga_added_library) in toasts
            }
            coVerify { addToLibrary.add(alpha, listOf(4L), fetchOnAdd = true) }
        } finally {
            toggleScope.cancel()
        }

        // BulkFavorite skip-duplicates flow when all selected entries are duplicates
        val bulkAdd = mockk<BulkLibraryAdd>(relaxed = true) {
            coEvery { duplicates(listOf(alpha)) } returns listOf(alpha)
        }
        val bulk = BulkFavorite(adder = { bulkAdd }, controller = controller)
        bulk.toggle(alpha)
        bulk.addToLibrary()
        Await.until("bulk duplicate prompt", 5_000) { bulk.duplicates == listOf(alpha) }

        bulk.resolveDuplicates(skip = true)
        bulk.active shouldBe false
        bulk.selection.isEmpty() shouldBe true
    }
}
