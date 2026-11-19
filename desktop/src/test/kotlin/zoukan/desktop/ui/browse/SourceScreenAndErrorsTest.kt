package zoukan.desktop.ui.browse

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.UncaughtCoroutineErrors
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.manga.EntrySearch
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

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

@OptIn(ExperimentalTestApi::class)
class BrowseSourceContentPagingTest {

    @TempDir
    lateinit var temp: File

    private fun entries(count: Int) = (1..count).map { Manga.create().copy(id = it.toLong(), ogTitle = "Entry $it", source = 1L) }

    @Test
    fun `a page that fits the window asks for the next page once it lands`() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        // The list rows load covers through the app's image loader
        Images.init(File(temp, "paging-covers"))
        var items by mutableStateOf(emptyList<Manga>())
        var nearEnd = 0
        setContent {
            BrowseSourceContent(
                source = null,
                items = items,
                metadata = emptyMap(),
                favorites = emptySet(),
                displayMode = LibraryDisplayMode.List,
                ehentaiBrowseDisplayMode = false,
                listingKey = "popular",
                isSelected = { false },
                onMangaClick = {},
                onMangaSecondaryClick = {},
                onNearEnd = { nearEnd++ },
                footer = { Text("footer") },
            )
        }
        waitForIdle()
        // The footer-only first frame asks (the model ignores it while its own first load runs)
        (nearEnd >= 1) shouldBe true
        val beforePage = nearEnd

        // The page lands and fits the window: the end is still visible, so it must ask again
        items = entries(6)
        waitForIdle()
        (nearEnd > beforePage) shouldBe true
    }
}

/** A search sends the filters as they were when it started, not the ones the filter sheet edits afterwards. */
class SourceFiltersSnapshotTest {

    private val asked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    @Volatile
    private var received: FilterList? = null

    private val fake: CatalogueSource = mockk(relaxed = true) {
        every { id } returns 42L
        every { getFilterList() } answers { FilterList(object : Filter.CheckBox("Completed", false) {}) }
        coEvery { getSearchManga(any(), any(), any()) } coAnswers {
            received = thirdArg()
            asked.complete(Unit)
            release.await()
            MangasPage(emptyList(), false)
        }
    }

    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        Dispatchers.setMain(main)
    }

    private val model = SourceScreenModel(
        sourceId = 42L,
        latest = false,
        initialQuery = "query",
        sourceManager = mockk<SourceManager> { every { get(42L) } returns fake },
        getFavorites = mockk { every { subscribe(42L) } returns flowOf(emptyList()) },
        getExhSavedSearch = mockk { every { subscribe(42L, any()) } returns flowOf(emptyList()) },
        filterSerializer = FilterSerializer(),
        preferences = mockk(relaxed = true),
        networkToLocalManga = mockk(relaxed = true),
        insertSavedSearch = mockk(),
        deleteSavedSearchById = mockk(),
        getFlatMetadataById = mockk(relaxed = true),
    )

    @AfterEach
    fun tearDown() {
        release.complete(Unit)
        try {
            model.onDispose()
            runBlocking {
                withTimeout(5_000) {
                    val job = model.screenModelScope.coroutineContext[Job]!!
                    job.children.forEach { it.cancel() }
                    job.children.toList().forEach { it.join() }
                }
            }
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    @Test
    fun `changing a filter while a search runs does not change the filters that search sent`() = runBlocking<Unit> {
        withTimeout(5_000) { asked.await() }
        // What the filter sheet does: change the filter in place, then signal it
        (model.filters.first() as Filter.CheckBox).state = true
        model.filtersChanged()
        release.complete(Unit)
        (received!!.first() as Filter.CheckBox).state shouldBe false
    }
}

/**
 * Running out of memory while a source's page loads goes on up to the crash report, after the
 * listing has stopped loading: a listing left loading spins for good and loads no more pages.
 */
class SourceFatalErrorTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    init {
        Dispatchers.setMain(main)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    private val fake: CatalogueSource = mockk(relaxed = true) {
        every { id } returns 42L
        coEvery { getPopularManga(any()) } throws OutOfMemoryError("test")
    }

    private val model = SourceScreenModel(
        sourceId = 42L,
        latest = false,
        initialQuery = null,
        sourceManager = mockk<SourceManager> { every { get(42L) } returns fake },
        getFavorites = mockk { every { subscribe(42L) } returns flowOf(emptyList()) },
        getExhSavedSearch = mockk { every { subscribe(42L, any()) } returns flowOf(emptyList()) },
        filterSerializer = FilterSerializer(),
        preferences = mockk(relaxed = true),
        networkToLocalManga = mockk(relaxed = true),
        insertSavedSearch = mockk(),
        deleteSavedSearchById = mockk(),
        getFlatMetadataById = mockk(relaxed = true),
    )

    @AfterEach
    fun tearDown() {
        model.onDispose()
        // Its coroutines read Dispatchers.Main until they end, and resetting it meanwhile fails
        runBlocking {
            withTimeout(5_000) {
                listOf(model).forEach { m ->
                    val job = m.screenModelScope.coroutineContext[Job]!!
                    job.children.forEach { it.cancel() }
                    job.children.toList().forEach { it.join() }
                }
            }
        }
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        UncaughtCoroutineErrors.drain()
    }

    @Test
    fun `running out of memory on a page ends the loading and still reaches the crash report`() {
        Await.until("the listing's error", 10_000, state = { model.state.value }) {
            model.state.value.error != null && uncaught.isNotEmpty()
        }
        model.state.value.loading shouldBe false
        uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
    }
}

/** The browse screens' source time limit: a slow source fails, leaving the screen still cancels. */
class SourceTimeoutTest {

    @Test
    fun `a fast source returns its result`() {
        runBlocking { withSourceTimeout(10_000) { "page" } } shouldBe "page"
    }

    @Test
    fun `a slow source fails with an ordinary exception, not a cancellation the callers rethrow`() {
        shouldThrow<TimeoutException> {
            runBlocking { withSourceTimeout(50) { delay(10_000) } }
        }
    }

    @Test
    fun `cancelling the caller still cancels the request`() {
        var caught: Throwable? = null
        val requesting = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch {
                try {
                    withSourceTimeout(10_000) {
                        requesting.complete(Unit)
                        delay(10_000)
                    }
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }
            requesting.await()
            job.cancel()
            job.join()
            job.isCancelled shouldBe true
        }
        caught.shouldBeInstanceOf<CancellationException>()
        (caught is TimeoutException) shouldBe false
    }

    @Test
    fun `a timeout shows the translated message, other errors their own`() {
        sourceErrorMessage(TimeoutException("Timed out waiting for 60000 ms")) shouldBe
            localized(DMR.strings.source_timed_out)
        sourceErrorMessage(IllegalStateException("HTTP 503")) shouldBe "HTTP 503"
    }

    @Test
    fun `a blocked site asks to open it in the browser`() {
        sourceErrorMessage(HttpException(403)) shouldBe localized(DMR.strings.source_blocked_open_the_site)
        sourceErrorMessage(HttpException(503)) shouldBe localized(DMR.strings.source_blocked_open_the_site)
        sourceErrorMessage(HttpException(500)) shouldBe "HTTP error 500"
    }

    @Test
    fun `no network and an unknown host get their own message`() {
        sourceErrorMessage(UnknownHostException("example.org")) shouldBe localized(DMR.strings.source_no_connection)
        sourceErrorMessage(ConnectException()) shouldBe localized(DMR.strings.source_no_connection)
        sourceErrorMessage(SocketTimeoutException()) shouldBe localized(DMR.strings.source_timed_out)
    }
}
