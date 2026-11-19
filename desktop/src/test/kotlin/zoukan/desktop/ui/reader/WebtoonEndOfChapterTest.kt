package zoukan.desktop.ui.reader

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
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
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * In [WebtoonViewer] with "Always show chapter transition" disabled, scrolling with the mouse
 * wheel to the end of back-to-back chapters shows the "Next chapter" button, and clicking it
 * advances to the next chapter.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WebtoonEndOfChapterTest {

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

    private fun tallPngBytes(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test
    fun `scrolling WebtoonViewer to end shows Next chapter button and advances cleanly`() =
        runDesktopComposeUiTest(width = 800, height = 600, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                alwaysShowChapterTransition().set(false)
                defaultReadingMode().set(ReadingModes.WEBTOON)
                showNavigationOverlayNewUser().set(false)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter1 = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1", chapterNumber = 1.0, sourceOrder = 1)
            val chapter2 = Chapter.create().copy(id = 20, mangaId = 1, name = "Chapter 2", url = "/c/2", chapterNumber = 2.0, sourceOrder = 0)
            val pageBytes = tallPngBytes()
            val source = mockk<HttpSource>(relaxed = true) {
                coEvery { getPageList(match { it.url == "/c/1" }) } returns List(3) { Page(it, imageUrl = "https://example.invalid/c1-$it.png") }
                coEvery { getPageList(match { it.url == "/c/2" }) } returns listOf(Page(0, imageUrl = "https://example.invalid/c2-0.png"))
                coEvery { getImage(any()) } answers {
                    val page = firstArg<Page>()
                    Response.Builder()
                        .request(Request.Builder().url(page.imageUrl!!).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pageBytes.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            }
            val model = ReaderScreenModel(
                mangaId = manga.id,
                initialChapterId = chapter1.id,
                getManga = mockk { coEvery { await(manga.id) } returns manga },
                getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter1, chapter2) },
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

            runBlocking { withTimeout(5_000) { model.state.first { it.chapter?.id == chapter1.id && it.pages.size == 3 } } }
            model.state.value.pages.forEach { rp ->
                rp.setState(ReaderPage.State.Ready(PageImage(100, 150, listOf(BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB).toComposeImageBitmap()))))
            }

            val actions = ReaderActions()
            setContent {
                val state by model.state.collectAsState()
                val content = ViewerContent(
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
                WebtoonViewer(
                    model = model,
                    content = content,
                    actions = actions,
                    sidePadding = 0,
                    background = Color.Black,
                    foreground = Color.White,
                )
            }

            val nextLabel = localized(DMR.strings.next_chapter_button)
            repeat(6) {
                if (onAllNodes(hasText(nextLabel)).fetchSemanticsNodes().isNotEmpty()) return@repeat
                onRoot().performMouseInput { repeat(40) { scroll(1200f) } }
                waitForIdle()
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(nextLabel)).fetchSemanticsNodes().isNotEmpty() }

            onAllNodesWithText(nextLabel).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { model.state.value.chapter?.id == chapter2.id }
            model.state.value.chapter?.id shouldBe chapter2.id
        }
}
