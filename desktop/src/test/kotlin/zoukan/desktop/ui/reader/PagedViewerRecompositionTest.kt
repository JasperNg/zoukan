package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.e2eEffectContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Turning a page in [PagedViewer] updates the reading position without recomposing [PagedViewer]
 * when [ViewerContent] inputs have not changed.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PagedViewerRecompositionTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<ReaderScreenModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

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

    private fun pngBytes(rgb: Int): ByteArray = ByteArrayOutputStream().also { out ->
        val img = BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 60) for (x in 0 until 40) img.setRGB(x, y, rgb)
        ImageIO.write(img, "png", out)
    }.toByteArray()

    @Composable
    private fun TrackedPagedViewer(
        model: ReaderScreenModel,
        content: ViewerContent,
        actions: ReaderActions,
        compositions: AtomicInteger,
    ) {
        SideEffect { compositions.incrementAndGet() }
        PagedViewer(
            model = model,
            content = content,
            actions = actions,
            fitMode = FitModes.FIT_SCREEN,
            background = Color.Black,
            foreground = Color.White,
        )
    }

    @Test
    fun `turning a page does not recompose PagedViewer when ViewerContent is unchanged`() = runDesktopComposeUiTest(width = 800, height = 600, effectContext = e2eEffectContext()) {
        val store = ReactiveMemoryPreferenceStore()
        val prefs = DesktopPreferences(store).apply {
            pageLayout().set(0)
            pageTransitionsPager().set(false)
            showNavigationOverlayNewUser().set(false)
            defaultReadingMode().set(ReadingModes.RIGHT_TO_LEFT)
        }
        val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
        val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
        val pages = listOf(
            Page(0, imageUrl = "https://example.invalid/1.png"),
            Page(1, imageUrl = "https://example.invalid/2.png"),
            Page(2, imageUrl = "https://example.invalid/3.png"),
        )
        val bytes = listOf(pngBytes(0xFF0000), pngBytes(0x00FF00), pngBytes(0x0000FF))
        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getPageList(any()) } returns pages
            coEvery { getImage(any()) } answers {
                val page = firstArg<Page>()
                Response.Builder()
                    .request(Request.Builder().url(page.imageUrl!!).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes[page.index].toResponseBody("image/png".toMediaType()))
                    .build()
            }
        }
        val model = ReaderScreenModel(
            mangaId = manga.id,
            initialChapterId = chapter.id,
            getManga = mockk { coEvery { await(manga.id) } returns manga },
            getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
            sourceManager = mockk { every { get(any<Long>()) } returns source },
            updateChapter = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { findChapter(any(), any()) } returns null
                every { isDownloaded(any(), any()) } returns false
            },
            cbzPassword = mockk(relaxed = true),
            downloadPreferences = DownloadPreferences(store),
            libraryPreferences = LibraryPreferences(store),
            preferences = prefs,
            incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns true }),
            syncManager = lazyOf(mockk(relaxed = true)),
            discordPresence = lazyOf(mockk(relaxed = true)),
            trackChapters = lazyOf(mockk(relaxed = true)),
            appScope = app,
        ).also { models += it }

        runBlocking { withTimeout(5_000) { model.state.first { it.pages.size == 3 && !it.loading } } }

        val actions = ReaderActions()
        val viewerCompositions = AtomicInteger(0)
        var latestPageText = ""
        setContent {
            val state by model.state.collectAsState()
            latestPageText = state.pageNumber
            val content = remember(
                state.pages, state.chapter, state.previousChapter, state.nextChapter,
                state.readingMode, state.startPage, state.widePages, state.shiftDoublePages, state.generation,
            ) {
                ViewerContent(
                    pages = state.pages,
                    chapter = state.chapter,
                    previousChapter = state.previousChapter,
                    nextChapter = state.nextChapter,
                    readingMode = state.readingMode,
                    startPage = state.startPage,
                    widePages = state.widePages,
                    shiftDoublePages = state.shiftDoublePages,
                    generation = state.generation,
                )
            }
            TrackedPagedViewer(model, content, actions, viewerCompositions)
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() &&
                model.state.value.pages[0].state.value is ReaderPage.State.Ready
        }
        waitForIdle()
        val beforeTurn = viewerCompositions.get()
        beforeTurn shouldBeGreaterThan 0

        runOnUiThread { actions.next() }
        waitUntil(timeoutMillis = 5_000) { model.state.value.currentPage == 1 }
        waitForIdle()
        latestPageText shouldBe "2 / 3"
        viewerCompositions.get() shouldBe beforeTurn

        // Verify the counter does increment when ViewerContent actually changes
        model.toggleShiftDoublePages()
        waitUntil(timeoutMillis = 5_000) { viewerCompositions.get() > beforeTurn }
    }
}
