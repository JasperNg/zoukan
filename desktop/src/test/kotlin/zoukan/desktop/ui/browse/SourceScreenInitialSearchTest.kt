package zoukan.desktop.ui.browse

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.testing.Await
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.manga.EntrySearch
import zoukan.desktop.ui.manga.MangaScreen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.Executors

@OptIn(ExperimentalTestApi::class)
class SourceScreenInitialSearchTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models = mutableListOf<SourceScreenModel>()

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        runBlocking {
            withTimeout(5_000) {
                models.forEach { m ->
                    val job = m.screenModelScope.coroutineContext[Job]!!
                    job.children.forEach { it.cancel() }
                    job.children.toList().forEach { it.join() }
                }
            }
        }
        models.clear()
        Dispatchers.resetMain()
        main.close()
    }

    private fun createModel(
        source: CatalogueSource,
        initialQuery: String? = null,
        initialGenre: String? = null,
    ): SourceScreenModel {
        val id = source.id
        val mangaRepository: MangaRepository = mockk(relaxed = true) {
            coEvery { insertNetworkManga(any(), any()) } returns emptyList()
        }
        val model = SourceScreenModel(
            sourceId = id,
            latest = false,
            initialQuery = initialQuery,
            initialGenre = initialGenre,
            sourceManager = mockk<SourceManager> { every { get(id) } returns source },
            getFavorites = mockk { every { subscribe(id) } returns flowOf(emptyList()) },
            getExhSavedSearch = mockk { every { subscribe(id, any()) } returns flowOf(emptyList()) },
            filterSerializer = FilterSerializer(),
            preferences = DesktopPreferences(TestPreferenceStore()),
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            insertSavedSearch = mockk(relaxed = true),
            deleteSavedSearchById = mockk(relaxed = true),
            getFlatMetadataById = GetFlatMetadataById(mockk(relaxed = true)),
        )
        models += model
        return model
    }

    @Test
    fun `initialQuery starts a text search without relying on any shared channel`() {
        var searchedQuery: String? = null
        val source: CatalogueSource = mockk(relaxed = true) {
            every { id } returns 11L
            every { getFilterList() } answers { FilterList() }
            coEvery { getSearchManga(any(), any(), any()) } coAnswers {
                searchedQuery = secondArg()
                MangasPage(emptyList(), false)
            }
        }
        val model = createModel(source, initialQuery = "Alpha")
        Await.until("initial text search to run", 5_000) { searchedQuery == "Alpha" }
        model.state.value.listing shouldBe SourceScreenModel.Listing.Search("Alpha")
    }

    @Test
    fun `initialGenre activates the matching filter or falls back to query when absent`() {
        var matchedFilters: FilterList? = null
        var matchedQuery: String? = null
        val matchingSource: CatalogueSource = mockk(relaxed = true) {
            every { id } returns 12L
            every { getFilterList() } answers {
                FilterList(
                    object : Filter.Group<Filter.CheckBox>(
                        "Genres",
                        listOf(object : Filter.CheckBox("Drama", false) {}),
                    ) {},
                )
            }
            coEvery { getSearchManga(any(), any(), any()) } coAnswers {
                matchedQuery = secondArg()
                matchedFilters = thirdArg()
                MangasPage(emptyList(), false)
            }
        }
        val matchedModel = createModel(matchingSource, initialGenre = "drama")
        Await.until("matching genre search to run", 5_000) { matchedFilters != null }
        matchedQuery shouldBe ""
        matchedModel.state.value.listing shouldBe SourceScreenModel.Listing.Search("")
        val group = matchedFilters!!.first() as Filter.Group<*>
        (group.state.first() as Filter.CheckBox).state shouldBe true

        var fallbackQuery: String? = null
        val fallbackSource: CatalogueSource = mockk(relaxed = true) {
            every { id } returns 13L
            every { getFilterList() } answers { FilterList() }
            coEvery { getSearchManga(any(), any(), any()) } coAnswers {
                fallbackQuery = secondArg()
                MangasPage(emptyList(), false)
            }
        }
        val fallbackModel = createModel(fallbackSource, initialGenre = "Mystery")
        Await.until("fallback genre search to run", 5_000) { fallbackQuery == "Mystery" }
        fallbackModel.state.value.listing shouldBe SourceScreenModel.Listing.Search("Mystery")
    }

    @Test
    fun `EntrySearch replaces SourceScreen and pushes from SourceFeedScreen with explicit parameters`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val httpSource: HttpSource = mockk(relaxed = true) { every { id } returns 99L }

        setContent {
            Navigator(listOf(HomeScreen, SourceScreen(99L), MangaScreen(1L))) {
                navigator = it
            }
        }
        waitForIdle()

        runOnUiThread {
            EntrySearch.search(navigator!!, "TitleQuery", global = false)
        }
        waitForIdle()
        val afterSearch = navigator!!.lastItem.shouldBeInstanceOf<SourceScreen>()
        afterSearch.sourceId shouldBe 99L
        afterSearch.query shouldBe "TitleQuery"
        afterSearch.genre shouldBe null
        afterSearch.searchVersion shouldBe 1

        // Repeating the same query from a child entry increments searchVersion so Voyager creates a fresh screen
        runOnUiThread {
            navigator!!.push(MangaScreen(2L))
            EntrySearch.search(navigator!!, "TitleQuery", global = false)
        }
        waitForIdle()
        val afterRepeatSearch = navigator!!.lastItem.shouldBeInstanceOf<SourceScreen>()
        afterRepeatSearch.query shouldBe "TitleQuery"
        afterRepeatSearch.searchVersion shouldBe 2
        afterRepeatSearch.key shouldNotBe afterSearch.key

        // Tag search from an open SourceScreen replaces it with explicit genre
        runOnUiThread {
            navigator!!.push(MangaScreen(3L))
            EntrySearch.searchGenre(navigator!!, "Action", httpSource)
        }
        waitForIdle()
        val afterGenre = navigator!!.lastItem.shouldBeInstanceOf<SourceScreen>()
        afterGenre.genre shouldBe "Action"
        afterGenre.query shouldBe null
        afterGenre.searchVersion shouldBe 3

        // Tag search from SourceFeedScreen pushes a new SourceScreen with explicit genre
        runOnUiThread {
            navigator!!.replaceAll(listOf(HomeScreen, SourceFeedScreen(99L), MangaScreen(4L)))
            EntrySearch.searchGenre(navigator!!, "Comedy", httpSource)
        }
        waitForIdle()
        val fromFeed = navigator!!.lastItem.shouldBeInstanceOf<SourceScreen>()
        fromFeed.sourceId shouldBe 99L
        fromFeed.genre shouldBe "Comedy"
        fromFeed.query shouldBe null
    }

    @Test
    fun `SourceScreen variants with genre and searchVersion serialize and keep distinct prefix-free keys`() {
        val plain = SourceScreen(1L)
        val withQuery = SourceScreen(1L, query = "one")
        val withGenre = SourceScreen(1L, genre = "Action")
        val bumped = SourceScreen(1L, query = "one", searchVersion = 1)
        val longerId = SourceScreen(12L, genre = "Action")

        val keys = listOf(plain, withQuery, withGenre, bumped, longerId).map { it.key }
        keys.distinct().size shouldBe 5
        keys.all { it.endsWith("/") } shouldBe true
        longerId.key.startsWith(withGenre.key) shouldBe false

        val bytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { it.writeObject(withGenre) }
            baos.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as SourceScreen
        restored shouldBe withGenre
        restored.key shouldBe withGenre.key
    }
}
