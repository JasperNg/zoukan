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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
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
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
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
import javax.imageio.ImageIO

/**
 * Tests [ReaderSettingsDialog] preference changes and [ReaderScreen] double-page layout preservation
 * and page actions menu updates after switching between double and single page layouts.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ReaderSettingsUiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

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

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(12, 18, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun registerModelDeps(store: PreferenceStore, prefs: DesktopPreferences, manga: Manga, chapter: Chapter) {
        val pageBytes = pngBytes()
        val pages = List(4) { Page(it, imageUrl = "https://example.invalid/${it + 1}.png") }
        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getPageList(any()) } returns pages
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
        Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
        Injekt.addSingleton(mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns listOf(chapter) })
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
        Injekt.addSingleton(mockk<zoukan.desktop.track.TrackChapters>(relaxed = true))
    }

    @Test
    fun `ReaderSettingsDialog updates scale type, reader theme, and shift double pages`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                pageLayout().set(1)
                defaultReadingMode().set(ReadingModes.LEFT_TO_RIGHT)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
            registerModelDeps(store, prefs, manga, chapter)

            val model = ReaderScreenModel(
                mangaId = manga.id,
                initialChapterId = chapter.id,
                appScope = app,
            ).also { models += it }
            runBlocking { withTimeout(5_000) { model.state.first { !it.loading } } }

            var dismissed = false
            setContent {
                ReaderSettingsDialog(model) { dismissed = true }
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(localized(DMR.strings.reading_mode_for_this_entry)).fetchSemanticsNodes().isNotEmpty()
            }

            onAllNodesWithText(localized(MR.strings.scale_type_smart_fit)).onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { prefs.imageScaleType().get() == FitModes.SMART_FIT }

            onAllNodesWithText(localized(DMR.strings.shift_double_pages)).onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { model.state.value.shiftDoublePages }

            onAllNodesWithText(localized(MR.strings.action_filter)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(localized(DMR.strings.inverted_colors)).fetchSemanticsNodes().isNotEmpty()
            }

            onAllNodesWithText(localized(MR.strings.pref_category_general)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(localized(MR.strings.pref_reader_theme)).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText(localized(MR.strings.automatic_background)).onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { prefs.readerTheme().get() == 3 }

            onAllNodesWithText(localized(MR.strings.action_close)).onFirst().performClick()
            dismissed shouldBe true
        }

    @Test
    fun `switching double and single page layout preserves reading position and updates page actions`() =
        runDesktopComposeUiTest(width = 1000, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                pageLayout().set(1)
                defaultReadingMode().set(ReadingModes.LEFT_TO_RIGHT)
                showNavigationOverlayNewUser().set(false)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
            registerModelDeps(store, prefs, manga, chapter)

            setContent {
                CompositionLocalProvider(LocalWindowState provides WindowState()) {
                    Navigator(ReaderScreen(manga.id, chapter.id)) { it.lastItem.Content() }
                }
            }

            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }

            // Turn to spread [1, 2] -> pages 2-3 / 4
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2-3 / 4")).fetchSemanticsNodes().isNotEmpty() }

            // Switching to single layout keeps page 3 ("3 / 4")
            prefs.pageLayout().set(0)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 / 4")).fetchSemanticsNodes().isNotEmpty() }

            // Right-clicking in single page mode offers "Page 3" and not "Pages 2 and 3"
            onRoot().performMouseInput { rightClick(center) }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(localized(DMR.strings.page, 3))).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(localized(DMR.strings.pages_and, 2, 3)).fetchSemanticsNodes().size shouldBe 0
            onAllNodesWithText(localized(MR.strings.action_close)).onFirst().performClick()

            // Switching back to double layout restores "2-3 / 4" and offers "Save combined page"
            prefs.pageLayout().set(1)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2-3 / 4")).fetchSemanticsNodes().isNotEmpty() }
            onRoot().performMouseInput { rightClick(center) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(localized(SYMR.strings.action_save_combined_page))).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText(localized(SYMR.strings.action_set_second_page_cover))).fetchSemanticsNodes().isNotEmpty()
            }
        }
}
