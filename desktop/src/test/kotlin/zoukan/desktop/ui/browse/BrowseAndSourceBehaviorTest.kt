package zoukan.desktop.ui.browse

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import exh.source.ExhPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.source.FeedSavedSearchRepositoryImpl
import tachiyomi.data.source.SavedSearchRepositoryImpl
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchById
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceIdFeed
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DatabaseThread
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.local.LocalSource
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Isolated behavior coverage for Browse, Source, Global Search, Feed Order, and missing
 * Extension Details pushed down from `BrowseCategoriesJourneyTest`, `BrowseFeaturesJourneyTest`,
 * and `SourceErrorJourneyTest`.
 */
@OptIn(ExperimentalTestApi::class)
class BrowseAndSourceBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var store: InMemoryPreferenceStore
    private lateinit var desktopPrefs: DesktopPreferences
    private lateinit var sourcePrefs: SourcePreferences
    private lateinit var uiPrefs: UiPreferences
    private lateinit var exhPrefs: ExhPreferences

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Images.init(File(temp, "covers"))
        store = InMemoryPreferenceStore()
        desktopPrefs = DesktopPreferences(store)
        sourcePrefs = SourcePreferences(store)
        uiPrefs = UiPreferences(store)
        exhPrefs = ExhPreferences(store)

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(desktopPrefs)
        Injekt.addSingleton(sourcePrefs)
        Injekt.addSingleton(uiPrefs)
        Injekt.addSingleton(exhPrefs)
        Injekt.addSingleton(mockk<GetExtensionStores> { coEvery { subscribe() } returns flowOf(emptyList()) })
        Injekt.addSingleton(mockk<AddExtensionStore>(relaxed = true))
        Injekt.addSingleton(mockk<RemoveExtensionStore>(relaxed = true))
        Injekt.addSingleton(
            mockk<DesktopExtensionManager>(relaxed = true) {
                every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
                every { untrustedExtensionsFlow } returns MutableStateFlow(emptyMap())
                every { availableExtensionsFlow } returns MutableStateFlow(emptyMap())
                every { loadErrorsFlow } returns MutableStateFlow(emptyMap())
            },
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private class GenreFilter : Filter.Select<String>("Genre", arrayOf("Any", "Drama", "Comedy"))

    private class FakeCatalogueSource(
        override val id: Long,
        override val name: String,
        override val lang: String = "en",
        var nextFailure: Throwable? = null,
        var results: List<SManga> = listOf(
            SManga.create().apply {
                url = "/alpha"
                title = "Alpha"
            },
            SManga.create().apply {
                url = "/drama"
                title = "Fixture Second"
                genre = "Drama"
            },
        ),
    ) : CatalogueSource {
        override val supportsLatest = true
        override fun getFilterList() = FilterList(GenreFilter())
        override suspend fun getPopularManga(page: Int): MangasPage {
            nextFailure?.let { throw it }
            return MangasPage(results, hasNextPage = false)
        }
        override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            nextFailure?.let { throw it }
            val genre = (filters.firstOrNull() as? GenreFilter)?.let { it.values[it.state] } ?: "Any"
            val filtered = results.filter { manga ->
                (query.isBlank() || query in manga.title) && (genre == "Any" || manga.genre == genre)
            }
            return MangasPage(filtered, hasNextPage = false)
        }
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter) = emptyList<eu.kanade.tachiyomi.source.model.Page>()
    }

    @Test
    fun `browse categories group sources and disabling hides the source`() {
        val fake = FakeCatalogueSource(101L, "Test Source")
        val local = mockk<LocalSource>(relaxed = true) {
            every { id } returns LocalSource.ID
            every { name } returns "Local source"
            every { lang } returns "other"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { sources } returns MutableStateFlow(listOf(fake, local))
            every { isInitialized } returns MutableStateFlow(true)
        }
        sourcePrefs.sourcesTabCategories().set(setOf("Comics"))
        sourcePrefs.sourcesTabSourcesInCategories().set(setOf("${LocalSource.ID}|Comics"))

        val model = BrowseScreenModel(
            extensionManager = Injekt.getInstance(DesktopExtensionManager::class.java),
            sourceManager = sourceManager,
            preferences = desktopPrefs,
            getStores = Injekt.getInstance(GetExtensionStores::class.java),
            addStore = Injekt.getInstance(AddExtensionStore::class.java),
            removeStore = Injekt.getInstance(RemoveExtensionStore::class.java),
            sourcePreferences = sourcePrefs,
            uiPreferences = uiPrefs,
            exhPreferences = exhPrefs,
        )
        try {
            Await.until("sources loaded") { model.state.value.sourcesLoaded }
            model.state.value.sections.first { it.first == "Comics" }.second.map { it.id } shouldBe listOf(LocalSource.ID)

            // Assign fake source to Comics: listed under Comics and under English
            model.setCategories(fake, setOf("Comics"))
            Await.until("fake source added to Comics") {
                model.state.value.sections.firstOrNull { it.first == "Comics" }?.second?.any { it.id == fake.id } == true
            }
            sourcePrefs.sourcesTabSourcesInCategories().get() shouldBe setOf("${LocalSource.ID}|Comics", "${fake.id}|Comics")

            // Disable fake source: removed from all sections while local source keeps Comics
            model.toggleHidden(fake)
            Await.until("fake source hidden") { fake.id.toString() in model.state.value.disabled }
            model.state.value.sections.flatMap { it.second }.none { it.id == fake.id } shouldBe true
            model.state.value.sections.first { it.first == "Comics" }.second.map { it.id } shouldBe listOf(LocalSource.ID)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `source errors show timeout or HTTP status, retry recovers, and saved search persists filter`() {
        val dbThread = DatabaseThread()
        val driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "browse_source.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = dbThread,
            schema = Database.Schema,
        )
        val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dbDispatcher, dbDispatcher)
        val fake = FakeCatalogueSource(202L, "Fake Source", nextFailure = SocketTimeoutException("read timed out"))
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(fake.id) } returns fake
            every { getAll() } returns listOf(fake)
        }
        val mangaRepo = MangaRepositoryImpl(handler)
        val savedSearchRepo = SavedSearchRepositoryImpl(handler)
        val filterSerializer = FilterSerializer()
        val getExhSavedSearch = GetExhSavedSearch(
            getSavedSearchById = GetSavedSearchById(savedSearchRepo),
            getSavedSearchBySourceId = GetSavedSearchBySourceId(savedSearchRepo),
            filterSerializer = filterSerializer,
        )

        try {
            val model = SourceScreenModel(
                sourceId = fake.id,
                latest = false,
                initialQuery = null,
                getExhSavedSearch = getExhSavedSearch,
                insertSavedSearch = InsertSavedSearch(savedSearchRepo),
                deleteSavedSearchById = DeleteSavedSearchById(savedSearchRepo),
                filterSerializer = filterSerializer,
                getFlatMetadataById = mockk(relaxed = true),
                sourceManager = sourceManager,
                networkToLocalManga = NetworkToLocalManga(mangaRepo),
                getFavorites = GetFavorites(mangaRepo),
                preferences = desktopPrefs,
            )
            try {
                // 1. Timeout error -> HTTP 500 -> Retry succeeds
                Await.until("timeout error") { model.state.value.error == localized(DMR.strings.source_timed_out) }
                fake.nextFailure = HttpException(500)
                model.retry()
                Await.until("HTTP 500 error") { model.state.value.error == "HTTP error 500" }
                fake.nextFailure = null
                model.retry()
                Await.until("catalogue loaded after retry") { model.state.value.items.size == 2 }
                model.state.value.error shouldBe null

                // 2. Empty search shows no items
                model.search("zzz-nothing")
                Await.until("empty search settled") { !model.state.value.loading && model.state.value.items.isEmpty() }

                // 3. Apply filter + query, save search, restore into fresh model, then delete
                (model.filters.single() as GenreFilter).state = 1 // Drama
                model.search("Fixture")
                Await.until("filtered results") { model.state.value.items.map { it.ogTitle } == listOf("Fixture Second") }
                model.saveSearch("Drama picks")
                Await.until("saved search stored") { model.state.value.savedSearches.any { it.name == "Drama picks" } }
                val saved = model.state.value.savedSearches.single()
                saved.query shouldBe "Fixture"
                (saved.filterList?.single() as GenreFilter).state shouldBe 1

                val reopened = SourceScreenModel(
                    sourceId = fake.id,
                    latest = false,
                    initialQuery = null,
                    savedSearchId = saved.id,
                    getExhSavedSearch = getExhSavedSearch,
                    insertSavedSearch = InsertSavedSearch(savedSearchRepo),
                    deleteSavedSearchById = DeleteSavedSearchById(savedSearchRepo),
                    filterSerializer = filterSerializer,
                    getFlatMetadataById = mockk(relaxed = true),
                    sourceManager = sourceManager,
                    networkToLocalManga = NetworkToLocalManga(mangaRepo),
                    getFavorites = GetFavorites(mangaRepo),
                    preferences = desktopPrefs,
                )
                try {
                    Await.until("reopened saved search applied") {
                        !reopened.state.value.loading && reopened.state.value.items.map { it.ogTitle } == listOf("Fixture Second")
                    }
                    (reopened.filters.single() as GenreFilter).state shouldBe 1
                    reopened.deleteSavedSearch(saved.id)
                    Await.until("saved search deleted") { reopened.state.value.savedSearches.isEmpty() }
                } finally {
                    reopened.onDispose()
                }
            } finally {
                model.onDispose()
            }
        } finally {
            driver.close()
            dbThread.executor.shutdownNow()
        }
    }

    @Test
    fun `global search filters out empty sources and retries HTTP 403 blocked source`() {
        val blocked = FakeCatalogueSource(301L, "Blocked Source", nextFailure = HttpException(403))
        val matching = FakeCatalogueSource(302L, "Matching Source")
        val empty = FakeCatalogueSource(303L, "Empty Source", results = emptyList())
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { getAll() } returns listOf(blocked, matching, empty)
        }
        val mangaRepo = mockk<MangaRepositoryImpl>(relaxed = true)
        val networkToLocal = mockk<NetworkToLocalManga> {
            coEvery { invoke(any<List<Manga>>(), any<Boolean>()) } answers { firstArg<List<Manga>>() }
        }
        Injekt.addSingleton(sourceManager)
        Injekt.addSingleton(networkToLocal)

        val model = GlobalSearchScreenModel(
            query = "Alpha",
            sourceManager = sourceManager,
            preferences = desktopPrefs,
            networkToLocalManga = networkToLocal,
            sourcePreferences = sourcePrefs,
        )
        try {
            Await.until("all sources finished") {
                model.state.value.size == 3 &&
                    model.state.value.values.none { it is GlobalSearchScreenModel.Result.Loading }
            }
            model.state.value[blocked.id] shouldBe GlobalSearchScreenModel.Result.Error(
                localized(DMR.strings.source_blocked_open_the_site),
            )
            (model.state.value[matching.id] as GlobalSearchScreenModel.Result.Success).manga.map { it.ogTitle } shouldContainExactly listOf("Alpha")
            (model.state.value[empty.id] as GlobalSearchScreenModel.Result.Success).manga shouldBe emptyList()

            // Retry blocked source after recovery
            blocked.nextFailure = null
            model.retry(blocked.id)
            Await.until("blocked source recovered") {
                model.state.value[blocked.id] is GlobalSearchScreenModel.Result.Success
            }
            (model.state.value[blocked.id] as GlobalSearchScreenModel.Result.Success).manga.map { it.ogTitle } shouldContainExactly listOf("Alpha")

            // UI filter chip hides empty source when globalSearchFilterState is enabled
            sourcePrefs.globalSearchFilterState().set(false)
            runDesktopComposeUiTest {
                setContent {
                    cafe.adriel.voyager.navigator.Navigator(GlobalSearchScreen("Alpha", SearchPurpose.Browse))
                }
                Await.until("Matching Source visible") { true }
                onNodeWithTag("global-search-has-results").performClick()
                waitForIdle()
                sourcePrefs.globalSearchFilterState().get() shouldBe true
                onNodeWithText("Empty Source").assertDoesNotExist()
            }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `feed reorder persists feed order in database`() {
        val dbThread = DatabaseThread()
        val driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "feed_order.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = dbThread,
            schema = Database.Schema,
        )
        val dbDispatcher = dbThread.executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dbDispatcher, dbDispatcher)
        val feedRepo = FeedSavedSearchRepositoryImpl(handler)
        val savedRepo = SavedSearchRepositoryImpl(handler)
        val getFeeds = GetFeedSavedSearchGlobal(feedRepo)
        val insertFeed = InsertFeedSavedSearch(feedRepo)
        try {
            runBlocking {
                insertFeed.await(FeedSavedSearch(id = -1, source = 4242L, savedSearch = null, global = true, feedOrder = 0))
                insertFeed.await(FeedSavedSearch(id = -1, source = 4243L, savedSearch = null, global = true, feedOrder = 1))
            }
            val model = FeedScreenModel(
                sourceId = null,
                getSourceFeeds = GetFeedSavedSearchBySourceId(feedRepo),
                getSourceFeedSearches = GetSavedSearchBySourceIdFeed(feedRepo),
                getFeeds = getFeeds,
                getFeedSavedSearches = GetSavedSearchGlobalFeed(feedRepo),
                getExhSavedSearch = GetExhSavedSearch(
                    getSavedSearchById = GetSavedSearchById(savedRepo),
                    getSavedSearchBySourceId = GetSavedSearchBySourceId(savedRepo),
                    filterSerializer = FilterSerializer(),
                ),
                insertFeed = insertFeed,
                deleteFeed = DeleteFeedSavedSearchById(feedRepo),
                sourceManager = mockk(relaxed = true),
                networkToLocalManga = mockk(relaxed = true),
            )
            try {
                Await.until("two feeds loaded") { model.state.value.rows?.size == 2 }
                val first = model.state.value.rows!!.first()
                model.move(first, 1)
                Await.until("feed reordered") {
                    runBlocking { getFeeds.await() }.map { it.source } == listOf(4243L, 4242L)
                }
            } finally {
                model.onDispose()
            }
        } finally {
            driver.close()
            dbThread.executor.shutdownNow()
        }
    }

    @Test
    fun `missing extension details screen shows not installed message`() {
        Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
        Injekt.addSingleton(Incognito(store, sourcePrefs, Injekt.getInstance(DesktopExtensionManager::class.java), exhPrefs))
        runDesktopComposeUiTest {
            setContent { Navigator(ExtensionDetailsScreen("eu.kanade.tachiyomi.extension.en.missing")) }
            waitForIdle()
            onNodeWithText("This extension is not installed.").assertExists()
        }
    }
}
