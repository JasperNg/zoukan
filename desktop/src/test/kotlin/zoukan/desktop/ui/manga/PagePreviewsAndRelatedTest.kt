package zoukan.desktop.ui.manga

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Covers [PagePreviewSection] (loading thumbnails, clicking preview 3 to open [ReaderScreen] at
 * page 2, and "More previews" opening [PagePreviewScreen]), [PagePreviewScreen.Model] +
 * [PagePreviewGrid] paging on scroll near end, and [RelatedTitlesSection] opening related entries.
 */
@OptIn(ExperimentalTestApi::class)
class PagePreviewsAndRelatedTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var tempDir: File

    private class ReactiveMemoryPreferenceStore : PreferenceStore {
        private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()
        private fun <T> pref(key: String, defaultValue: T): Preference<T> {
            val flow = flows.computeIfAbsent(key) { MutableStateFlow(null) }
            @Suppress("UNCHECKED_CAST")
            return object : Preference<T> {
                override fun key(): String = key
                override fun get(): T = (flow.value as T?) ?: defaultValue
                override fun isSet(): Boolean = flow.value != null
                override fun delete() {
                    flow.value = null
                }
                override fun defaultValue(): T = defaultValue
                override fun changes(): Flow<T> = flow.map { (it as T?) ?: defaultValue }
                override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
                override fun set(value: T) {
                    flow.value = value
                }
            }
        }
        override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)
        override fun <T> getObjectFromString(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T) = pref(key, defaultValue)
        override fun <T> getObjectFromInt(key: String, defaultValue: T, serializer: (T) -> Int, deserializer: (Int) -> T) = pref(key, defaultValue)
        override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }
    }

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

        override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: okhttp3.CacheControl?): Response =
            Response.Builder()
                .request(okhttp3.Request.Builder().url(page.imageUrl).build())
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

    private class HostScreen(private val content: @androidx.compose.runtime.Composable () -> Unit) : Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = content()
    }

    @Test
    fun `PagePreviewSection opens ReaderScreen at clicked preview page and More previews opens PagePreviewScreen`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val uiPrefs = UiPreferences(store)
            val sourcePrefs = eu.kanade.domain.source.service.SourcePreferences(store)
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
            Injekt.addSingleton(mockk<eu.kanade.tachiyomi.data.track.TrackerManager>(relaxed = true))
            Injekt.addSingleton(mockk<tachiyomi.domain.track.interactor.GetTracks> { every { subscribe(any<Long>()) } returns kotlinx.coroutines.flow.flowOf(emptyList()) })
            Injekt.addSingleton(mockk<eu.kanade.domain.track.service.TrackPreferences>(relaxed = true))
            Injekt.addSingleton(mockk<zoukan.desktop.library.AddToLibrary>(relaxed = true))
            Injekt.addSingleton(mockk<tachiyomi.domain.category.interactor.GetCategories>(relaxed = true))

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
            onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex))
                .onFirst()
                .performScrollToIndex(perPage - 1)
            waitUntil(timeoutMillis = 5_000) { source.previewPagesAsked.contains(2) }
            onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex))
                .onFirst()
                .performScrollToIndex(perPage)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasContentDescription(localized(DMR.strings.page, perPage + 1))).fetchSemanticsNodes().isNotEmpty()
            }

            // Scroll to end of page 2 (hasNextPage = false) -> no page 3 requested
            onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex))
                .onFirst()
                .performScrollToIndex(2 * perPage - 1)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasContentDescription(localized(DMR.strings.page, 2 * perPage))).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            source.previewPagesAsked shouldBe listOf(1, 2)
        }
}
