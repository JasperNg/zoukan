package zoukan.desktop.ui.manga

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.Navigator
import coil3.ImageLoader
import coil3.PlatformContext
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.EH_SOURCE_ID
import exh.util.DataSaver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.settings.dataSaverSettings
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

/**
 * Tests for entry metadata display ([MangaInfoPane], [MetadataViewScreen]), [DataSaver],
 * [PagePreviewGrid], [PagePreviewSection], [PagePreviewScreen], and [RelatedTitlesSection].
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MangaMetadataAndPreviewsTest {

    private class FakePreviewAndRelatedSource(
        override val id: Long = 9001L,
        private val perPage: Int = 3,
        private val totalPages: Int = 1,
    ) : CatalogueSource by mockk(relaxed = true),
        PagePreviewSource {
        override val name = "FakePreviewSource"
        override val lang = "en"
        val previewPagesAsked: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val validPng = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(16, 24, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
            previewPagesAsked += page
            val start = (page - 1) * perPage + 1
            val list = (0 until perPage).map { i ->
                PagePreviewInfo(start + i, "https://example.invalid/preview-${start + i}.png")
            }
            return PagePreviewPage(page, list, hasNextPage = page < totalPages, pagePreviewPages = totalPages)
        }

        override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response =
            Response.Builder()
                .request(Request.Builder().url(page.imageUrl).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(validPng.toResponseBody("image/png".toMediaType()))
                .build()

        override suspend fun getRelatedMangaList(
            manga: SManga,
            exceptionHandler: (Throwable) -> Unit,
            pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
        ) {
            pushResults(
                "Related" to listOf(
                    SManga.create().apply {
                        url = "/rel-1"
                        title = "Fixture Second"
                    },
                    SManga.create().apply {
                        url = "/rel-2"
                        title = "Fixture Third"
                    },
                ),
                true,
            )
        }
    }

    private class HostScreen(private val content: @Composable () -> Unit) : Screen {
        @Composable
        override fun Content() = content()
    }

    /**
     * Covers E-Hentai [MetadataDescription] + [NamespaceTags] on [MangaInfoPane] (keeping related titles
     * and page previews hidden when disabled), [MetadataViewScreen] field formatting, and
     * Settings > Data [dataSaverSettings] + [DataSaver.compress].
     */
    @Nested
    inner class MetadataViewerAndDataSaver {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var tempDir: File

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<MangaScreenModel>()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
            Images.init(File(tempDir, "cache").apply { mkdirs() })
            CustomCovers.coverDir = File(tempDir, "covers").apply { mkdirs() }
            Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
            Injekt.addSingleton(mockk<NetworkHelper> { every { client } returns OkHttpClient() })
        }

        @AfterEach
        fun tearDown() {
            models.forEach { it.onDispose() }
            app.cancel()
            Dispatchers.resetMain()
            main.close()
        }

        private fun sampleMetadata(mangaId: Long): EHentaiSearchMetadata = EHentaiSearchMetadata().apply {
            this.mangaId = mangaId
            gId = "123456"
            gToken = "abcdef1234"
            title = "Metadata gallery"
            genre = "doujinshi"
            uploader = "uploader-name"
            language = "english"
            translated = true
            size = 52_428_800
            length = 24
            favorites = 321
            averageRating = 4.5
            ratingCount = 100
            visible = "Yes"
            tags += RaisedTag("artist", "some artist", EHentaiSearchMetadata.TAG_TYPE_NORMAL)
            tags += RaisedTag("female", "glasses", EHentaiSearchMetadata.TAG_TYPE_LIGHT)
        }

        @Test
        fun `MangaInfoPane renders metadata details and namespace tags, and MetadataViewScreen shows gallery fields`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val uiPrefs = UiPreferences(store).apply { previewsRowCount().set(0) }
                val sourcePrefs = SourcePreferences(store).apply { relatedMangas().set(false) }
                val manga = Manga.create().copy(
                    id = 8801L,
                    url = "/g/123456/abcdef1234/",
                    ogTitle = "Metadata gallery",
                    source = EH_SOURCE_ID,
                    favorite = true,
                    initialized = true,
                )
                val meta = sampleMetadata(manga.id)
                val ehentaiSource = mockk<EHentai>(relaxed = true) {
                    every { id } returns EH_SOURCE_ID
                    every { name } returns "E-Hentai"
                    every { metaClass } returns EHentaiSearchMetadata::class
                }
                val getFlatMetadataById = mockk<GetFlatMetadataById> {
                    every { subscribe(manga.id) } returns flowOf(meta.flatten())
                    coEvery { await(manga.id) } returns meta.flatten()
                }
                val sourceManager = mockk<SourceManager>(relaxed = true) {
                    every { get(EH_SOURCE_ID) } returns ehentaiSource
                }
                val downloads = mockk<DownloadManager>(relaxed = true) {
                    every { changes } returns MutableStateFlow(0)
                    every { queue } returns MutableStateFlow(emptyList())
                }
                val ch = Chapter.create().copy(id = 88010L, mangaId = manga.id, url = manga.url, name = "Chapter", sourceOrder = 0)

                Injekt.addSingleton(uiPrefs)
                Injekt.addSingleton(sourcePrefs)
                Injekt.addSingleton(sourceManager)
                Injekt.addSingleton(getFlatMetadataById)
                Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
                Injekt.addSingleton(Application())
                Injekt.addSingleton(mockk<GetMergedMangaById>(relaxed = true))

                val model = MangaScreenModel(
                    mangaId = manga.id,
                    getMangaWithChapters = mockk<GetMangaWithChapters> {
                        coEvery { awaitManga(manga.id) } returns manga
                        coEvery { subscribe(manga.id) } returns flowOf(manga to listOf(ch))
                    },
                    sourceManager = sourceManager,
                    updateChapter = mockk(relaxed = true),
                    refreshManga = mockk(relaxed = true),
                    downloadManager = downloads,
                    getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                    trackPreferences = mockk(relaxed = true),
                    trackerManager = mockk(relaxed = true),
                    trackChapters = mockk(relaxed = true),
                    getMergedChaptersByMangaId = mockk(relaxed = true),
                    getFlatMetadataById = getFlatMetadataById,
                    addToLibraryFlow = mockk(relaxed = true),
                    getCategories = mockk(relaxed = true),
                    setMangaCategories = mockk(relaxed = true),
                    chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
                    filterChaptersForDownload = mockk(relaxed = true),
                    updateErrors = mockk(relaxed = true),
                    refreshTracks = mockk(relaxed = true),
                    libraryPreferences = LibraryPreferences(store),
                    uiPreferences = uiPrefs,
                    chaptersByEntry = mockk<ChaptersByEntry> {
                        coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) }
                    },
                    desktopPreferences = lazyOf(DesktopPreferences(store)),
                    appScope = app,
                ).also { models += it }

                var openedMetadata = false
                setContent {
                    MaterialTheme {
                        Navigator(
                            object : Screen {
                                override val key: ScreenKey = "metadata-host"

                                @Composable
                                override fun Content() {
                                    val state by model.state.collectAsState()
                                    val m = state.manga
                                    if (m != null) {
                                        MangaInfoPane(
                                            manga = m,
                                            state = state,
                                            model = model,
                                            onWebView = null,
                                            onSearch = {},
                                            onOpenMetadata = { openedMetadata = true },
                                            search = EntryInfoSearch({}, {}, {}),
                                        )
                                    }
                                }
                            },
                        ) { it.lastItem.Content() }
                    }
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText("uploader-name")).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasText("glasses")).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText(localized(SYMR.strings.more_info)).onFirst().assertExists()
                onAllNodesWithText("artist").onFirst().assertExists()
                onAllNodesWithText("some artist").onFirst().assertExists()
                onAllNodesWithText("female").onFirst().assertExists()
                onAllNodesWithText(localized(DMR.strings.related_titles)).fetchSemanticsNodes().shouldBeEmpty()
                onAllNodesWithText(localized(SYMR.strings.more_previews)).fetchSemanticsNodes().shouldBeEmpty()

                onAllNodesWithText(localized(SYMR.strings.more_info)).onFirst().performScrollTo().performClick()
                openedMetadata shouldBe true
            }

        @Test
        fun `MetadataViewScreen displays E-Hentai gallery metadata fields`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val manga = Manga.create().copy(id = 8901L, url = "/g/123456/abcdef1234/", ogTitle = "Metadata gallery", source = EH_SOURCE_ID)
                val meta = sampleMetadata(manga.id)
                val ehentaiSource = mockk<EHentai>(relaxed = true) {
                    every { id } returns EH_SOURCE_ID
                    every { metaClass } returns EHentaiSearchMetadata::class
                }
                Injekt.addSingleton(Application())
                Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
                Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { get(EH_SOURCE_ID) } returns ehentaiSource })
                Injekt.addSingleton(mockk<GetFlatMetadataById> { coEvery { await(manga.id) } returns meta.flatten() })

                setContent {
                    MaterialTheme {
                        Navigator(MetadataViewScreen(manga.id, EH_SOURCE_ID)) { it.lastItem.Content() }
                    }
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText("123456")).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasText("52.4 MB")).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasText("321")).fetchSemanticsNodes().isNotEmpty()
                }
            }

        @Test
        fun `dataSaverSettings enables dependent options on wsrv_nl and DataSaver rewrites URLs`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val sourcePrefs = SourcePreferences(store)
                val source = mockk<EHentai>(relaxed = true) { every { id } returns EH_SOURCE_ID }
                Injekt.addSingleton(sourcePrefs)
                Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { getOnlineSources() } returns emptyList() })

                setContent {
                    MaterialTheme {
                        LazyColumn {
                            dataSaverSettings()
                        }
                    }
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty()
                }
                val enabledBefore = onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size

                onAllNodesWithText(localized(SYMR.strings.data_saver_summary)).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText(localized(SYMR.strings.wsrv))).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText(localized(SYMR.strings.wsrv)).onFirst().performClick()

                waitUntil(timeoutMillis = 5_000) {
                    sourcePrefs.dataSaver().get() == SourcePreferences.DataSaver.WSRV_NL &&
                        onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size > enabledBefore
                }
                onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size shouldBeGreaterThan enabledBefore

                DataSaver(source, sourcePrefs).compress("https://example.org/a.png") shouldBe
                    "https://wsrv.nl/?url=https://example.org/a.png&output=webp&q=80"
            }
    }

    @Nested
    inner class PagePreviewGridSuite {

        @Test
        fun `previews that repeat a page number across preview pages all show`() = runDesktopComposeUiTest {
            val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build() // no fetcher: thumbnails stay empty
            var previews by mutableStateOf(
                listOf(
                    PagePreviewImage(1L, 1, "https://example.org/p1-1.jpg"),
                    PagePreviewImage(1L, 2, "https://example.org/p1-2.jpg"),
                ),
            )
            setContent {
                MaterialTheme {
                    PagePreviewGrid(previews, loading = false, error = null, onLoadMore = {}, onOpen = {}, loader = loader)
                }
            }
            waitForIdle()
            // The second preview page restarts its numbering at 1
            previews = previews + listOf(
                PagePreviewImage(1L, 1, "https://example.org/p2-1.jpg"),
                PagePreviewImage(1L, 2, "https://example.org/p2-2.jpg"),
            )
            waitForIdle()
            onAllNodesWithText("1").assertCountEquals(2)
            onAllNodesWithText("2").assertCountEquals(2)
        }

        @Test
        fun `a preview listed on two preview pages shows once`() {
            val a = PagePreviewImage(1L, 1, "https://example.org/a.jpg")
            val b = PagePreviewImage(1L, 2, "https://example.org/b.jpg")
            val c = PagePreviewImage(1L, 3, "https://example.org/c.jpg")
            appendPreviews(listOf(a, b), listOf(b, c)) shouldBe listOf(a, b, c)
        }
    }

    /**
     * Covers [PagePreviewSection] (loading thumbnails, clicking preview 3 to open [ReaderScreen] at
     * page 2, and "More previews" opening [PagePreviewScreen]), [PagePreviewScreen.Model] +
     * [PagePreviewGrid] paging on scroll near end, and [RelatedTitlesSection] opening related entries.
     */
    @Nested
    inner class PagePreviewsAndRelated {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var tempDir: File

        private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(16, 24, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        @BeforeEach
        fun setUp() {
            Images.init(File(tempDir, "cache").apply { mkdirs() })
            CustomCovers.coverDir = File(tempDir, "covers").apply { mkdirs() }
            Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
            val validPng = pngBytes()
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validPng.toResponseBody("image/png".toMediaType()))
                        .build()
                }
                .build()
            Injekt.addSingleton(mockk<NetworkHelper> { every { this@mockk.client } returns client })
        }

        @Test
        fun `PagePreviewSection opens ReaderScreen at clicked preview page and More previews opens PagePreviewScreen`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val uiPrefs = UiPreferences(store)
                val sourcePrefs = SourcePreferences(store)
                val source = FakePreviewAndRelatedSource(id = 9101L, perPage = 3, totalPages = 1)
                val manga = Manga.create().copy(id = 91010L, source = source.id, url = "/m", ogTitle = "Formats", initialized = true)
                val ch1 = Chapter.create().copy(id = 10L, mangaId = manga.id, url = "/c/1", name = "Chapter 1", sourceOrder = 0)
                val relatedSecond = Manga.create().copy(id = 91011L, source = source.id, url = "/rel-1", ogTitle = "Fixture Second")
                val relatedThird = Manga.create().copy(id = 91012L, source = source.id, url = "/rel-2", ogTitle = "Fixture Third")

                Injekt.addSingleton(uiPrefs)
                Injekt.addSingleton(sourcePrefs)
                Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { get(source.id) } returns source })
                Injekt.addSingleton(mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns listOf(ch1) })
                Injekt.addSingleton(
                    mockk<NetworkToLocalManga> {
                        coEvery { this@mockk.invoke(any<List<Manga>>()) } returns listOf(relatedSecond, relatedThird)
                    },
                )
                Injekt.addSingleton(mockk<TrackerManager>(relaxed = true))
                Injekt.addSingleton(mockk<GetTracks> { every { subscribe(any<Long>()) } returns flowOf(emptyList()) })
                Injekt.addSingleton(mockk<TrackPreferences>(relaxed = true))
                Injekt.addSingleton(mockk<AddToLibrary>(relaxed = true))
                Injekt.addSingleton(mockk<GetCategories>(relaxed = true))

                var navigator: Navigator? = null
                setContent {
                    MaterialTheme {
                        Navigator(
                            HostScreen {
                                Column {
                                    RelatedTitlesSection(manga)
                                    PagePreviewSection(manga)
                                }
                            },
                        ) { nav ->
                            navigator = nav
                            // Only compose the host screen so ReaderScreen/MangaScreen push assertions don't boot full screens
                            if (nav.lastItem is HostScreen) nav.lastItem.Content()
                        }
                    }
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasContentDescription(localized(DMR.strings.page, 3))).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasText("Fixture Third")).fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText("Fixture Without Cover").fetchSemanticsNodes().shouldBeEmpty()

                val nav = checkNotNull(navigator)
                // Click preview 3 -> navigates to ReaderScreen at page index 2
                onAllNodes(hasContentDescription(localized(DMR.strings.page, 3))).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) { nav.lastItem is ReaderScreen }
                (nav.lastItem as ReaderScreen) shouldBe ReaderScreen(manga.id, ch1.id, page = 2)

                runOnUiThread { nav.pop() }
                waitForIdle()

                // Click More previews -> navigates to PagePreviewScreen
                onNodeWithText(localized(SYMR.strings.more_previews)).performClick()
                waitUntil(timeoutMillis = 5_000) { nav.lastItem is PagePreviewScreen }
                (nav.lastItem as PagePreviewScreen) shouldBe PagePreviewScreen(manga.id)

                runOnUiThread { nav.pop() }
                waitForIdle()

                // Click a related title -> navigates to MangaScreen for that related entry
                onNodeWithText("Fixture Third").performClick()
                waitUntil(timeoutMillis = 5_000) { nav.lastItem is MangaScreen }
                (nav.lastItem as MangaScreen) shouldBe MangaScreen(relatedThird.id)
            }

        @Test
        fun `PagePreviewScreen loads next preview page only when scrolled near end and stops at last page`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val perPage = 60
                val source = FakePreviewAndRelatedSource(id = 9201L, perPage = perPage, totalPages = 2)
                val manga = Manga.create().copy(id = 92010L, source = source.id, url = "/m2", ogTitle = "Second", initialized = true)
                val ch1 = Chapter.create().copy(id = 20L, mangaId = manga.id, url = "/c/1", name = "Chapter 1", sourceOrder = 0)

                Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { get(source.id) } returns source })
                Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
                Injekt.addSingleton(mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns listOf(ch1) })

                setContent {
                    MaterialTheme {
                        Navigator(PagePreviewScreen(manga.id)) { it.lastItem.Content() }
                    }
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasContentDescription(localized(DMR.strings.page, 1))).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                source.previewPagesAsked shouldBe listOf(1)
                onAllNodes(hasContentDescription(localized(DMR.strings.page, perPage + 1))).fetchSemanticsNodes().shouldBeEmpty()

                // Scroll near the end of page 1 -> loads page 2, then scroll to page 2's first item
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                    .onFirst()
                    .performScrollToIndex(perPage - 1)
                waitUntil(timeoutMillis = 5_000) { source.previewPagesAsked.contains(2) }
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                    .onFirst()
                    .performScrollToIndex(perPage)
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasContentDescription(localized(DMR.strings.page, perPage + 1))).fetchSemanticsNodes().isNotEmpty()
                }

                // Scroll to end of page 2 (hasNextPage = false) -> no page 3 requested
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                    .onFirst()
                    .performScrollToIndex(2 * perPage - 1)
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasContentDescription(localized(DMR.strings.page, 2 * perPage))).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                source.previewPagesAsked shouldBe listOf(1, 2)
            }
    }
}
