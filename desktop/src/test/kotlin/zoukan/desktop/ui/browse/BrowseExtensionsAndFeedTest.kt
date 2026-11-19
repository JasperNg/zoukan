package zoukan.desktop.ui.browse

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingDirection
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingMode
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import zoukan.desktop.compat.localized
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.Await
import java.util.concurrent.Executors

/** KMK's search box over the Sources list, as SourcesScreenModel's queryFilter. */
class SourceSearchTest {

    private fun source(name: String, id: Long): CatalogueSource = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.id } returns id
    }

    private val alpha = source("Alpha Comics", 11)
    private val bravo = source("Bravo Scans", 22)
    private val charlie = source("Charlie", 33)

    private val extension: Extension.Installed = mockk {
        every { name } returns "Tachiyomi: Delta"
        every { sources } returns listOf(charlie)
    }

    private fun shown(query: String) = BrowseScreenModel.State(
        sections = listOf("Pinned" to listOf(alpha), "English" to listOf(bravo, charlie)),
        installed = listOf(extension),
        sourceQuery = query,
    ).visibleSections.map { (title, list) -> title to list.map { it.name } }

    @Test
    fun `a blank query shows every section`() {
        shown("  ") shouldBe listOf("Pinned" to listOf("Alpha Comics"), "English" to listOf("Bravo Scans", "Charlie"))
    }

    @Test
    fun `matches the name ignoring case and drops empty sections`() {
        shown("SCANS") shouldBe listOf("English" to listOf("Bravo Scans"))
    }

    @Test
    fun `comma-separated terms match any, with the extension name and the id`() {
        shown("delta, 11") shouldBe listOf("Pinned" to listOf("Alpha Comics"), "English" to listOf("Charlie"))
        shown("nothing") shouldBe emptyList()
    }

    private fun installed(pkg: String, ext: String, update: Boolean): Extension.Installed = mockk {
        every { pkgName } returns pkg
        every { name } returns ext
        every { hasUpdate } returns update
    }

    private fun available(pkg: String, ext: String, language: String): Extension.Available = mockk {
        every { pkgName } returns pkg
        every { name } returns ext
        every { lang } returns language
        every { isNsfw } returns false
    }

    @Test
    fun `the visible extension lists are built once per state`() {
        val untrusted: Extension.Untrusted = mockk {
            every { pkgName } returns "pkg.untrusted"
            every { name } returns "Untrusted"
        }
        val state = BrowseScreenModel.State(
            installed = listOf(installed("pkg.b", "Beta", false), installed("pkg.a", "Alpha", false), installed("pkg.z", "Zeta", true)),
            untrusted = listOf(untrusted),
            available = listOf(available("pkg.n", "Nine", "en"), available("pkg.m", "Mango", "en")),
        )

        (state.visibleInstalled === state.visibleInstalled) shouldBe true
        (state.visibleUntrusted === state.visibleUntrusted) shouldBe true
        (state.visibleAvailable === state.visibleAvailable) shouldBe true
        // Updates first, then by name
        state.visibleInstalled.map { it.name } shouldBe listOf("Zeta", "Alpha", "Beta")
        state.visibleAvailable.map { it.name } shouldBe listOf("Mango", "Nine")
    }
}

class StoreLabelTest {

    private val store = ExtensionStore(
        indexUrl = "https://store.example/index.min.json",
        name = "Zoukan Fixture Store",
        badgeLabel = "ZFS",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://store.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private val installed: Extension = mockk<Extension.Installed> {
        every { signatureHash } returns "key"
        every { storeName } returns "ZFS"
    }

    @Test
    fun `an installed extension shows its store's full name`() {
        storeLabel(installed, listOf(store)) shouldBe "Zoukan Fixture Store"
    }

    @Test
    fun `an extension whose store is gone keeps its short name`() {
        storeLabel(installed, emptyList()) shouldBe "ZFS"
    }

    @Test
    fun `an extension signed with another key keeps its own name`() {
        storeLabel(installed, listOf(store.copy(signingKey = "other"))) shouldBe "ZFS"
    }
}

@OptIn(ExperimentalTestApi::class)
class FeedRowHeaderTest {

    @Test
    fun `a right-click on a feed row does not remove it until confirmed`() = runDesktopComposeUiTest {
        var removed = 0
        setContent { FeedRowHeader(title = "Latest", fixed = false, onOpen = null, onRemove = { removed++ }) }
        onAllNodes(hasText("Latest")).onFirst().performMouseInput { rightClick() }
        waitForIdle()
        removed shouldBe 0
        onAllNodes(hasText("Remove") and hasClickAction()).onLast().performClick()
        waitForIdle()
        removed shouldBe 1
    }

    @Test
    fun `cancelling the question keeps the feed row`() = runDesktopComposeUiTest {
        var removed = 0
        setContent { FeedRowHeader(title = "Latest", fixed = false, onOpen = null, onRemove = { removed++ }) }
        onAllNodes(hasText("Latest")).onFirst().performMouseInput { rightClick() }
        waitForIdle()
        onAllNodes(hasText("Cancel") and hasClickAction()).onLast().performClick()
        waitForIdle()
        removed shouldBe 0
    }
}

@OptIn(ExperimentalTestApi::class)
class OpenUrlDialogTest {
    private val address = "https://example.org/manga/1"

    @Test
    fun `text that is not an address cannot be opened`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlEntryDialog(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput("one piece")
        onNodeWithText(localized(KMR.strings.open)).assertIsNotEnabled()
        onNodeWithTag("open-url-field").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitForIdle()
        opened shouldBe emptyList()
    }

    @Test
    fun `one Enter press opens the address once`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlEntryDialog(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput(address)
        onNodeWithTag("open-url-field").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitForIdle()
        opened shouldBe listOf(address)
    }

    @Test
    fun `an address can be opened with the button`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlEntryDialog(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput(address)
        onNodeWithText(localized(KMR.strings.open)).assertIsEnabled().performClick()
        waitForIdle()
        opened shouldBe listOf(address)
    }
}

/** Adding a bulk selection to the library goes through the same path as adding one entry. */
class BulkFavoriteTest {

    private fun manga(id: Long, title: String, favorite: Boolean, dateAdded: Long = 0) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { source } returns 10L
        every { this@mockk.title } returns title
        every { this@mockk.favorite } returns favorite
        every { this@mockk.dateAdded } returns dateAdded
    }

    private val addToLibrary = mockk<AddToLibrary>(relaxed = true)
    private val getManga = mockk<GetManga>()
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)

    private fun adder() = BulkLibraryAdd(
        addToLibrary = addToLibrary,
        getManga = getManga,
        getCategories = getCategories,
        setMangaCategories = setMangaCategories,
    )

    init {
        coEvery { getCategories.await(any<Long>()) } returns emptyList()
    }

    @Test
    fun `bulk add fetches each new entry as a single add does`() = runBlocking<Unit> {
        val a = manga(1, "Alpha", favorite = false)
        val b = manga(2, "Bravo", favorite = false)
        coEvery { getManga.await(1) } returns a
        coEvery { getManga.await(2) } returns b

        adder().add(listOf(a, b), listOf(5L)) shouldBe 2

        // getManga hands back the same instances, so the calls are matched by them
        coVerify { addToLibrary.add(a, listOf(5L), fetchOnAdd = true) }
        coVerify { addToLibrary.add(b, listOf(5L), fetchOnAdd = true) }
    }

    @Test
    fun `an entry added since the page loaded keeps its date added`() = runBlocking<Unit> {
        val snapshot = manga(1, "Alpha", favorite = false)
        coEvery { getManga.await(1) } returns manga(1, "Alpha", favorite = true, dateAdded = 1000)

        adder().add(listOf(snapshot), listOf(5L)) shouldBe 0

        coVerify(exactly = 0) { addToLibrary.add(any(), any(), any()) }
        coVerify { setMangaCategories.await(1, listOf(5L)) }
    }

    @Test
    fun `a duplicate the library check finds under another spelling is reported`() = runBlocking<Unit> {
        val a = manga(1, "Alpha", favorite = false)
        val b = manga(2, "Bravo", favorite = false)
        coEvery { addToLibrary.duplicates(match { it.id == 1L }) } returns listOf(MangaWithChapterCount(manga(9, "alpha!", favorite = true), 3))
        coEvery { addToLibrary.duplicates(match { it.id == 2L }) } returns emptyList()

        adder().duplicates(listOf(a, b)).map { it.id } shouldBe listOf(1L)
    }
}

class MigrateSourcesModelTest {

    // Sources 1 and 2 are installed; every other id is a stub with a name
    private val installed: Map<Long, Source> = mapOf(1L to StubSource(1, "en", "Alpha"), 2L to StubSource(2, "en", "Zulu"))
    private val sourceManager = mockk<SourceManager>().also { manager ->
        every { manager.get(any<Long>()) } answers { installed[firstArg()] }
        every { manager.getOrStub(any()) } answers { installed[firstArg()] ?: StubSource(firstArg(), "en", "Missing ${firstArg<Long>()}") }
    }

    private fun items(
        counts: Map<Long, Int>,
        query: String = "",
        mode: MigrateSortingMode = MigrateSortingMode.ALPHABETICAL,
        direction: MigrateSortingDirection = MigrateSortingDirection.ASCENDING,
    ) = migrateSourceItems(counts, sourceManager, query, mode, direction)

    @Test
    fun `sources that are not installed come first in either direction`() {
        val counts = mapOf(1L to 3, 2L to 5, 9L to 1)

        items(counts, direction = MigrateSortingDirection.ASCENDING).map { it.id } shouldBe listOf(9L, 1L, 2L)
        items(counts, direction = MigrateSortingDirection.DESCENDING).map { it.id } shouldBe listOf(9L, 2L, 1L)
        items(counts, mode = MigrateSortingMode.TOTAL, direction = MigrateSortingDirection.DESCENDING).map { it.id } shouldBe listOf(9L, 2L, 1L)
    }

    @Test
    fun `a comma search matches names or ids`() {
        val counts = mapOf(1L to 3, 2L to 5, 42L to 1, 7L to 2)

        items(counts, query = "alpha, 42").map { it.id }.toSet() shouldBe setOf(1L, 42L)
    }

    @Test
    fun `the local source is left out`() {
        items(mapOf(LocalSource.ID to 4, 1L to 3)).map { it.id } shouldBe listOf(1L)
    }

    private fun entry(id: Long, source: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `the model lists the library's sources and follows the search`() {
        // The model's scope runs on Main; a thread of its own stands in for the UI thread
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val model = MigrateSourcesModel(
            getLibraryManga = mockk { every { subscribe() } returns flowOf(listOf(entry(1, 1), entry(2, 1), entry(3, 2))) },
            sourceManager = sourceManager,
            preferences = SourcePreferences(InMemoryPreferenceStore()),
        )
        try {
            fun waitUntil(condition: () -> Boolean) =
                Await.until("the source list", 5_000, { model.state.value }, condition)

            waitUntil { model.state.value.items != null }
            model.state.value.items!!.map { it.name to it.count } shouldBe listOf("Alpha" to 2, "Zulu" to 1)

            model.search("zul")

            model.state.value.query shouldBe "zul"
            waitUntil { model.state.value.items?.size == 1 }
            model.state.value.items!!.map { it.name } shouldBe listOf("Zulu")

            model.search("")
            waitUntil { model.state.value.items?.size == 2 }
        } finally {
            model.onDispose()
            Dispatchers.resetMain()
            main.close()
        }
    }
}
