package zoukan.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
 * Keyboard navigation in [ReaderScreen]: page description semantics, `M` opens the overlay,
 * `Enter` on a focused menu button activates Settings without stacking overlays, and `Escape`
 * closes overlays before popping the screen.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ReaderKeyboardTest {

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

    @Test
    fun `M opens overlay, Enter activates focused settings button without hiding menu, Escape dismisses overlay`() =
        runDesktopComposeUiTest(width = 1000, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val prefs = DesktopPreferences(store).apply {
                showNavigationOverlayNewUser().set(false)
            }
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
            val pageBytes = pngBytes()
            val pages = List(3) { Page(it, imageUrl = "https://example.invalid/${it + 1}.png") }
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

            var navigator: Navigator? = null
            setContent {
                CompositionLocalProvider(LocalWindowState provides WindowState()) {
                    Navigator(ReaderScreen(manga.id, chapter.id)) { nav ->
                        navigator = nav
                        nav.lastItem.Content()
                    }
                }
            }

            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasContentDescription("1 / 3", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }

            val settingsDesc = localized(DMR.strings.settings_s)
            onRoot().performKeyInput { pressKey(Key.M) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasContentDescription(settingsDesc)).fetchSemanticsNodes().isNotEmpty()
            }

            onNode(hasContentDescription(settingsDesc)).performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            onNode(hasContentDescription(settingsDesc)).performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(localized(MR.strings.pref_category_general))).fetchSemanticsNodes().isNotEmpty()
            }
            // Opening Settings replaces the menu so overlays cannot stack
            onAllNodes(hasContentDescription(settingsDesc)).fetchSemanticsNodes().isEmpty() shouldBe true

            // Closing Settings stays in the reader, then reopening Menu and pressing Escape dismisses it
            onNode(hasText(localized(MR.strings.action_close))).performClick()
            waitForIdle()
            navigator!!.size shouldBe 1

            onRoot().performKeyInput { pressKey(Key.M) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasContentDescription(settingsDesc)).fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasContentDescription(settingsDesc)).fetchSemanticsNodes().isEmpty()
            }
            navigator!!.size shouldBe 1
            onAllNodes(hasContentDescription("1 / 3", substring = true)).fetchSemanticsNodes().isNotEmpty() shouldBe true
        }
}
