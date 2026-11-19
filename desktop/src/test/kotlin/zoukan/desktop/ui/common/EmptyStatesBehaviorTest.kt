package zoukan.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.search.SearchEngine
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.HomeController
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.HomeTabState
import zoukan.desktop.ui.LocalHomeController
import zoukan.desktop.ui.downloads.DownloadsScreen
import zoukan.desktop.ui.history.HistoryScreenModel
import zoukan.desktop.ui.history.HistoryTab
import zoukan.desktop.ui.library.LibraryScreenModel
import zoukan.desktop.ui.library.LibrarySearch
import zoukan.desktop.ui.library.LibraryTab
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.Executors

/**
 * Isolated Compose UI tests for empty states across Library, History, and Download Queue
 * (pushed down from `EmptyStatesJourneyTest`).
 */
@OptIn(ExperimentalTestApi::class)
class EmptyStatesBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var store: TestPreferenceStore

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Images.init(File(temp, "covers"))
        store = TestPreferenceStore()
        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(DesktopPreferences(store))
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton(SourcePreferences(store))
        Injekt.addSingleton(ExhPreferences(store))
        Injekt.addSingleton(SyncPreferences(store))
        Injekt.addSingleton(HistoryPreferences(store))
        Injekt.addSingleton(ZoukanDirs(temp))
        Injekt.addSingleton(ZoukanStorage(ZoukanDirs(temp), store))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun disposeAndJoin(model: ScreenModel) {
        model.onDispose()
        runBlocking {
            withTimeout(5_000) {
                val job = model.screenModelScope.coroutineContext[Job]!!
                job.cancelChildren()
                job.children.toList().joinAll()
            }
        }
    }

    @Test
    fun `empty library shows message and Browse button, then hides message when an entry is added`() {
        val libraryFlow = MutableStateFlow(emptyList<LibraryManga>())
        val downloadManager = mockk<DownloadManager>(relaxed = true) {
            every { changes } returns MutableStateFlow(0)
            every { queue } returns MutableStateFlow(emptyList())
            every { downloadCounts(any()) } returns emptyMap()
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { sources } returns MutableStateFlow(emptyList())
            every { isInitialized } returns MutableStateFlow(true)
        }
        val extensionManager = mockk<DesktopExtensionManager>(relaxed = true) {
            every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
            every { availableExtensionsFlow } returns MutableStateFlow(emptyMap())
        }
        val trackerManager = mockk<TrackerManager>(relaxed = true) {
            every { trackers } returns emptyList()
        }
        val libraryUpdater = mockk<LibraryUpdater>(relaxed = true) {
            every { progress } returns MutableStateFlow(null)
        }
        Injekt.addSingleton(downloadManager)
        Injekt.addSingleton(libraryUpdater)
        val searchEngine = mockk<SearchEngine>(relaxed = true)
        Injekt.addSingleton(searchEngine)
        Injekt.addSingleton(sourceManager)

        val model = LibraryScreenModel(
            getLibraryManga = mockk { every { subscribe() } returns libraryFlow },
            getCategories = mockk { every { subscribe() } returns flowOf(emptyList()) },
            getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
            preferences = LibraryPreferences(store),
            downloadManager = downloadManager,
            sourceManager = sourceManager,
            trackerManager = trackerManager,
            setSortModeForCategory = mockk(relaxed = true),
            setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            getMergedChaptersByMangaId = mockk(relaxed = true),
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            getNextChapters = mockk<GetNextChapters>(relaxed = true),
            getManga = mockk<GetManga>(relaxed = true),
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            setCustomMangaInfo = mockk<SetCustomMangaInfo>(relaxed = true),
            sourcePreferences = SourcePreferences(store),
            librarySearch = LibrarySearch(
                searchEngine = searchEngine,
                sourceManager = sourceManager,
                trackerManager = trackerManager,
                getIdsOfFavoriteMangaWithMetadata = mockk(relaxed = true),
                getSearchTags = mockk(relaxed = true),
                getSearchTitles = mockk(relaxed = true),
            ),
            extensionManager = extensionManager,
            chaptersByEntry = mockk<ChaptersByEntry>(relaxed = true),
        )
        val controller = HomeController(HomeTabState(HomeTab.Library))
        try {
            runDesktopComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalHomeController provides controller) {
                        Navigator(LibraryHost(model))
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText(localized(DMR.strings.your_library_is_empty_find_something)).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(localized(MR.strings.browse)).performClick()
                waitForIdle()
                controller.tabs.selected shouldBe HomeTab.Browse

                // Adding an entry replaces the empty message
                libraryFlow.value = listOf(
                    LibraryManga(
                        manga = Manga.create().copy(id = 1L, ogTitle = "Alpha", favorite = true),
                        categories = emptyList(),
                        totalChapters = 1,
                        readCount = 0,
                        bookmarkCount = 0,
                        bookmarkReadCount = 0,
                        chapterFlags = 0,
                        latestUpload = 0,
                        chapterFetchedAt = 0,
                        lastRead = 0,
                    ),
                )
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText(localized(DMR.strings.your_library_is_empty_find_something)).fetchSemanticsNodes().isEmpty()
                }
            }
        } finally {
            disposeAndJoin(model)
        }
    }

    @Test
    fun `empty history and empty download queue show their empty messages`() {
        val downloadManager = mockk<DownloadManager>(relaxed = true) {
            every { queue } returns MutableStateFlow(emptyList())
            every { running } returns MutableStateFlow(false)
        }
        Injekt.addSingleton(downloadManager)
        Injekt.addSingleton(mockk<GetCategories>(relaxed = true))
        Injekt.addSingleton(mockk<GetManga>(relaxed = true))
        Injekt.addSingleton(mockk<zoukan.desktop.library.AddToLibrary>(relaxed = true))

        val historyModel = HistoryScreenModel(
            getHistory = mockk<GetHistory> { every { subscribe(any(), any(), any(), any()) } returns flowOf(emptyList()) },
            removeHistory = mockk<RemoveHistory>(relaxed = true),
            getNextChapters = mockk<GetNextChapters>(relaxed = true),
            getChapter = mockk<GetChapter>(relaxed = true),
            preferences = HistoryPreferences(store),
        )
        try {
            runDesktopComposeUiTest {
                setContent { Navigator(HistoryHost(historyModel)) }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText(localized(DMR.strings.nothing_read_yet)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            runDesktopComposeUiTest {
                setContent { Navigator(DownloadsScreen) }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.no_downloads_queued_download_chapters_from)).assertExists()
            }
        } finally {
            disposeAndJoin(historyModel)
        }
    }

    private class LibraryHost(private val model: LibraryScreenModel) : Screen {
        @Composable
        override fun Content() = LibraryTab(model)
    }

    private class HistoryHost(private val model: HistoryScreenModel) : Screen {
        @Composable
        override fun Content() = HistoryTab(model)
    }
}
