package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Tests for shared UI controls, snackbar messages/insets, accessibility semantics, scroll thumb
 * geometry, selection bar actions, [ListSelection], and [LibraryAddController].
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class CommonUiControlsAndSelectionTest {

    /** The app's messages: the newest one replaces the one shown, and held-back ones show only the newest. */
    @Nested
    inner class MessagesAndInsets {

        // showSnackbar suspends until the message is dismissed when no host is composed, which is what these need

        @Test
        fun `a new message replaces the one on screen`() = runTest {
            val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
            val snackbar = SnackbarHostState()
            val shown = launch { showMessages(messages, snackbar) {} }
            runCurrent()
            messages.emit("Added to library")
            runCurrent()
            snackbar.currentSnackbarData?.visuals?.message shouldBe "Added to library"
            messages.emit("Restored 1 entry")
            runCurrent()
            snackbar.currentSnackbarData?.visuals?.message shouldBe "Restored 1 entry"
            shown.cancel()
        }

        @Test
        fun `messages held back show only the newest when released`() = runTest {
            val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
            val snackbar = SnackbarHostState()
            val held = MutableStateFlow(1)
            val shown = launch { showMessages(messages, snackbar) { held.first { it == 0 } } }
            runCurrent()
            messages.emit("one")
            messages.emit("two")
            runCurrent()
            snackbar.currentSnackbarData shouldBe null
            held.value = 0
            runCurrent()
            snackbar.currentSnackbarData?.visuals?.message shouldBe "two"
            shown.cancel()
        }

        @Test
        fun `overlapping insets keep the highest active reservation when disposed out of order`() {
            val messages = AppMessages()
            messages.bottomInset shouldBe 0.dp

            val releaseLarger = messages.reserveBottomInset(80.dp)
            messages.bottomInset shouldBe 80.dp

            val releaseSmaller = messages.reserveBottomInset(48.dp)
            messages.bottomInset shouldBe 80.dp

            // Disposing the first (larger) inset falls back to the still-active smaller inset, not 0.dp
            releaseLarger()
            messages.bottomInset shouldBe 48.dp

            // Releasing the same token twice is a no-op
            releaseLarger()
            messages.bottomInset shouldBe 48.dp

            releaseSmaller()
            messages.bottomInset shouldBe 0.dp
        }

        @Test
        fun `equal overlapping insets stay active until every reservation is released`() {
            val messages = AppMessages()
            val releaseFirst = messages.reserveBottomInset(56.dp)
            val releaseSecond = messages.reserveBottomInset(56.dp)
            messages.bottomInset shouldBe 56.dp

            releaseFirst()
            messages.bottomInset shouldBe 56.dp

            releaseSecond()
            messages.bottomInset shouldBe 0.dp
        }

        @Test
        fun `zero inset reservation does not disturb an active reservation`() {
            val messages = AppMessages()
            val releaseActive = messages.reserveBottomInset(40.dp)
            val releaseZero = messages.reserveBottomInset(0.dp)
            messages.bottomInset shouldBe 40.dp

            releaseZero()
            messages.bottomInset shouldBe 40.dp

            releaseActive()
            messages.bottomInset shouldBe 0.dp
        }

        @Test
        fun `overlapping ReserveSnackbarSpace composables keep remaining inset on out-of-order dispose`() = runDesktopComposeUiTest {
            val messages = AppMessages()
            var showOuter by mutableStateOf(true)
            var showInner by mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalAppMessages provides messages) {
                    if (showOuter) ReserveSnackbarSpace(72.dp)
                    if (showInner) ReserveSnackbarSpace(48.dp)
                }
            }
            waitForIdle()
            messages.bottomInset shouldBe 72.dp

            runOnUiThread { showOuter = false }
            waitForIdle()
            messages.bottomInset shouldBe 48.dp

            runOnUiThread { showInner = false }
            waitForIdle()
            messages.bottomInset shouldBe 0.dp
        }
    }

    /** A menu item with a checkbox or radio button in it tells screen readers its state. */
    @Nested
    inner class MenuItemSemantics {

        @Test
        fun `a checked menu item is announced on or off, and its click toggles it`() = runDesktopComposeUiTest {
            var on by mutableStateOf(false)
            setContent {
                Column {
                    DropdownMenuItem(
                        text = { Text("English") },
                        leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                        modifier = Modifier.checkedMenuItem(on),
                        onClick = { on = !on },
                    )
                }
            }
            onNodeWithText("English").assertIsOff()
            onNodeWithText("English").performClick()
            onNodeWithText("English").assertIsOn()
        }

        @Test
        fun `a radio menu item is announced selected or not`() = runDesktopComposeUiTest {
            setContent {
                Column {
                    listOf("Left to right", "Right to left").forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { RadioButton(selected = label == "Right to left", onClick = null) },
                            modifier = Modifier.selectedMenuItem(label == "Right to left"),
                            onClick = {},
                        )
                    }
                }
            }
            onNodeWithText("Right to left").assertIsSelected()
            onNodeWithText("Left to right").assertIsNotSelected()
        }

        @Test
        fun `every menu item with a checkbox or radio button says its state`() {
            val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }
            val leading = Regex("""leadingIcon = \{ (Checkbox|RadioButton|TriStateCheckbox)\(""")
            val missing = root.walk().filter { it.extension == "kt" }.flatMap { f ->
                val lines = f.readLines()
                lines.indices.filter { leading.containsMatchIn(lines[it]) }
                    .filter { i -> lines.subList(maxOf(0, i - 4), minOf(lines.size, i + 5)).none { "checkedMenuItem" in it || "selectedMenuItem" in it } }
                    .map { "${f.path}:${it + 1}" }
            }.toList()
            missing.shouldBeEmpty()
        }
    }

    /** What only a right-click opens is also a "More actions" custom action, for screen readers, keys and the crawl. */
    @Nested
    inner class RightClickSemantics {

        @Test
        fun `more actions is a custom action that runs the right-click's action`() = runDesktopComposeUiTest {
            var opened = 0
            setContent { Box(Modifier.size(50.dp).testTag("page").moreActions { opened++ }) }
            val actions = onNodeWithTag("page").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            actions.map { it.label } shouldBe listOf(localized(DMR.strings.more_actions))
            runOnUiThread { actions.single().action() }
            opened shouldBe 1
        }

        @Test
        fun `every element that handles a right-click itself also has the custom action`() {
            // The readers handle the right-click themselves (before their tap zones), so they can't
            // use onRightClick; each must then say moreActions
            val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }
            val missing = root.walk().filter { it.extension == "kt" }
                .filter { f -> f.readText().let { "isSecondaryPressed" in it || "PointerButton.Secondary" in it } }
                .filter { f -> f.readText().let { "moreActions" !in it && "onRightClick" !in it } }
                .map { it.path }.toList()
            missing.shouldBeEmpty()
        }
    }

    @Nested
    inner class ScrollThumbGeometry {

        @Test
        fun `no thumb when everything fits`() {
            scrollThumb(value = 0, max = 0, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe null
            scrollThumb(value = 0, max = Int.MAX_VALUE, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe null
        }

        @Test
        fun `the thumb shows how much is in view and where`() {
            // Twice the viewport: the thumb is half the track, at the top, then at the bottom
            scrollThumb(value = 0, max = 400, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe (4f to 196f)
            scrollThumb(value = 400, max = 400, viewport = 400f, minThumb = 40f, inset = 4f) shouldBe (200f to 196f)
        }

        @Test
        fun `a long list keeps a thumb big enough to see`() {
            val (top, height) = scrollThumb(value = 50_000, max = 100_000, viewport = 400f, minThumb = 40f, inset = 4f)!!
            height shouldBe 40f
            top shouldBe 4f + (392f - 40f) / 2
        }
    }

    @Nested
    inner class SelectionBarRendering {

        @Test
        fun `the selection bar shows the count and calls clear, select all and invert`() = runDesktopComposeUiTest {
            val calls = mutableListOf<String>()
            setContent {
                SelectionBar(3, onClear = { calls += "clear" }, onSelectAll = { calls += "all" }, onInvert = { calls += "invert" }) {
                    TooltipIconButton(Icons.Outlined.Delete, "Action", onClick = { calls += "action" })
                }
            }
            onNodeWithText(localized(DMR.strings.selected, 3)).assertExists()
            onNodeWithContentDescription(localized(DMR.strings.clear_selection)).performClick()
            onNodeWithContentDescription(localized(MR.strings.action_select_all)).performClick()
            onNodeWithContentDescription(localized(DMR.strings.invert_selection)).performClick()
            onNodeWithContentDescription("Action").performClick()
            calls shouldBe listOf("clear", "all", "invert", "action")
        }
    }

    @Nested
    inner class ListSelectionAndAddController {

        private val main = Executors.newSingleThreadExecutor { Thread(it, "common-test-main") }.asCoroutineDispatcher()
        private val toastCollectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            val toggleScope = CoroutineScope(SupervisorJob() + main)
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
}
