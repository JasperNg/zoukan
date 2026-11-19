package zoukan.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.window.WindowState
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Corrupt page bytes render [PageError] with a localized title and Retry button while other pages
 * still draw, clicking Retry after the source recovers renders the page, and HTTP 403 renders the
 * localized HTTP reason instead of a raw status line.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CorruptPageTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
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

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun corruptJpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

    private fun registerDeps(store: PreferenceStore, prefs: DesktopPreferences, manga: Manga, chapters: List<Chapter>, source: HttpSource) {
        Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
        Injekt.addSingleton(mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns chapters })
        Injekt.addSingleton(mockk<SourceManager> { every { get(any<Long>()) } returns source })
        Injekt.addSingleton(mockk<tachiyomi.domain.chapter.interactor.UpdateChapter>(relaxed = true))
        Injekt.addSingleton(mockk<UpsertHistory>(relaxed = true))
        Injekt.addSingleton(mockk<MangaRepository>(relaxed = true))
        Injekt.addSingleton(
            mockk<DownloadManager>(relaxed = true) {
                every { findChapter(any(), any()) } returns null
                every { isDownloaded(any(), any()) } returns false
            },
        )
        Injekt.addSingleton(mockk<CbzPassword>(relaxed = true))
        Injekt.addSingleton(DownloadPreferences(store))
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton(prefs)
        Injekt.addSingleton(mockk<zoukan.desktop.security.Incognito> { every { isOn(any()) } returns true })
        Injekt.addSingleton(mockk<eu.kanade.tachiyomi.data.sync.SyncManager>(relaxed = true))
        Injekt.addSingleton(mockk<zoukan.desktop.discord.DiscordPresence>(relaxed = true))
        Injekt.addSingleton(mockk<zoukan.desktop.track.TrackChapters>(relaxed = true))
    }

    @Test
    fun `corrupt page shows error and Retry recovers when valid image returns`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                pageLayout().set(0)
                defaultReadingMode().set(ReadingModes.LEFT_TO_RIGHT)
                showNavigationOverlayNewUser().set(false)
                readerInstantRetry().set(false)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
            val validPng = pngBytes()
            val failPage2 = AtomicBoolean(true)
            val pages = List(2) { Page(it, imageUrl = "https://example.invalid/${it + 1}.png") }
            val source = mockk<HttpSource>(relaxed = true) {
                coEvery { getPageList(any()) } returns pages
                coEvery { getImage(any()) } answers {
                    val page = firstArg<Page>()
                    val bodyBytes = if (page.index == 1 && failPage2.get()) corruptJpegBytes() else validPng
                    Response.Builder()
                        .request(Request.Builder().url(page.imageUrl!!).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bodyBytes.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            }
            registerDeps(store, prefs, manga, listOf(chapter), source)

            setContent {
                CompositionLocalProvider(LocalWindowState provides WindowState()) {
                    Navigator(ReaderScreen(manga.id, chapter.id)) { it.lastItem.Content() }
                }
            }

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            val errorTitle = localized(DMR.strings.page_could_not_load, 2)
            val retryLabel = localized(MR.strings.action_retry)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText(retryLabel)).fetchSemanticsNodes().isNotEmpty()
            }

            failPage2.set(false)
            onAllNodesWithText(retryLabel).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isEmpty() &&
                    onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun `HTTP 403 page renders localized exception reason instead of raw status`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                pageLayout().set(0)
                showNavigationOverlayNewUser().set(false)
                readerInstantRetry().set(false)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 20, mangaId = 1, name = "Chapter 2", url = "/c/2")
            val source = mockk<HttpSource>(relaxed = true) {
                coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.invalid/403.png"))
                coEvery { getImage(any()) } answers {
                    val page = firstArg<Page>()
                    Response.Builder()
                        .request(Request.Builder().url(page.imageUrl!!).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            registerDeps(store, prefs, manga, listOf(chapter), source)

            setContent {
                CompositionLocalProvider(LocalWindowState provides WindowState()) {
                    Navigator(ReaderScreen(manga.id, chapter.id)) { it.lastItem.Content() }
                }
            }

            val errorTitle = localized(DMR.strings.page_could_not_load, 1)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText(localized(MR.strings.exception_http, 403))).fetchSemanticsNodes().isNotEmpty() shouldBe true
            onAllNodes(hasText("HTTP 403")).fetchSemanticsNodes().isEmpty() shouldBe true
        }
}
