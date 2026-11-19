package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.window.WindowState
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
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
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderKeys.Action
import zoukan.desktop.ui.reader.ReaderNavigation.Region.LEFT
import zoukan.desktop.ui.reader.ReaderNavigation.Region.RIGHT
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Tests for reader input controls and chrome overlays: keyboard navigation in [ReaderScreen],
 * [ReaderKeys] bindings, tap zone labels and overlay positioning, [ReaderOverlay] transitions,
 * and [ReaderAppBars] layout and spread text.
 */
class ReaderControlsAndOverlayTest {

    /**
     * Keyboard navigation in [ReaderScreen]: page description semantics, `M` opens the overlay,
     * `Enter` on a focused menu button activates Settings without stacking overlays, and `Escape`
     * closes overlays before popping the screen.
     */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Nested
    inner class KeyboardNavigation {

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

        private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        @Test
        fun `M opens overlay, Enter activates focused settings button without hiding menu, Escape dismisses overlay`() =
            runDesktopComposeUiTest(width = 1000, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
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
                Injekt.addSingleton(mockk<UpdateChapter>(relaxed = true))
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
                Injekt.addSingleton(mockk<Incognito> { every { isOn(any()) } returns true })
                Injekt.addSingleton(mockk<SyncManager>(relaxed = true))
                Injekt.addSingleton(mockk<TrackChapters>(relaxed = true))

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

    @Nested
    inner class KeyBindings {

        @Test
        fun `left and right follow flipped taps, up and down follow vertical ones, J and K stay physical`() {
            ReaderKeys.actionFor(Key.DirectionRight) shouldBe Action.NEXT_PAGE
            ReaderKeys.actionFor(Key.DirectionRight, flipped = true) shouldBe Action.PREVIOUS_PAGE
            ReaderKeys.actionFor(Key.DirectionLeft, flipped = true) shouldBe Action.NEXT_PAGE
            ReaderKeys.actionFor(Key.DirectionDown, verticalFlipped = true) shouldBe Action.SCROLL_UP
            ReaderKeys.actionFor(Key.J, verticalFlipped = true) shouldBe Action.SCROLL_DOWN
            ReaderKeys.actionFor(Key.K, verticalFlipped = true) shouldBe Action.SCROLL_UP
        }

        @Test
        fun `keys the app owns go on to its shortcuts`() {
            // Alt + Left and Right are Back and Forward, Ctrl/Cmd + F is Find, Ctrl/Cmd + 1 a tab
            ReaderKeys.actionFor(Key.DirectionLeft, alt = true) shouldBe null
            ReaderKeys.actionFor(Key.DirectionRight, alt = true) shouldBe null
            ReaderKeys.actionFor(Key.F, ctrl = true) shouldBe null
            ReaderKeys.actionFor(Key.One, ctrl = true) shouldBe null
            ReaderKeys.actionFor(Key.F) shouldBe Action.TOGGLE_FULLSCREEN
        }

        @Test
        fun `shift turns back and boosts, and every action has a binding the crawl presses`() {
            ReaderKeys.actionFor(Key.Spacebar, shift = true) shouldBe Action.PREVIOUS_PAGE
            ReaderKeys.actionFor(Key.R, shift = true) shouldBe Action.BOOST_PAGE
            ReaderKeys.actionFor(Key.R) shouldBe Action.RETRY_PAGES
            ReaderKeys.bindings.mapNotNull { ReaderKeys.actionFor(it.key, shift = it.shift) }.toSet() shouldContainExactlyInAnyOrder Action.entries
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Nested
    inner class TapZoneLabels {

        private val next = localized(MR.strings.nav_zone_next)
        private val previous = localized(MR.strings.nav_zone_prev)
        private val left = localized(MR.strings.nav_zone_left)
        private val right = localized(MR.strings.nav_zone_right)

        @Test
        fun `right to left labels say which side turns to the next page`() {
            regionLabel(LEFT, rtl = true) shouldContain next
            regionLabel(RIGHT, rtl = true) shouldContain previous
            // Left to right is the opposite
            regionLabel(LEFT, rtl = false) shouldContain previous
            regionLabel(RIGHT, rtl = false) shouldContain next
            // The vertical and webtoon viewers go forward on the right in either direction
            regionLabel(RIGHT, vertical = true) shouldContain next
            regionLabel(LEFT, vertical = true) shouldContain previous
            regionLabel(RIGHT, rtl = true, vertical = true) shouldContain next
            regionLabel(LEFT, rtl = true, vertical = true) shouldContain previous
            // Each label still names its side
            regionLabel(LEFT, rtl = true) shouldContain left
            regionLabel(RIGHT, rtl = true) shouldContain right
        }

        @Test
        fun `the overlay leaves the centre clear, as Android, so the reading mode toast can be read`() =
            runDesktopComposeUiTest(1000, 800) {
                val mode = localized(MR.strings.right_to_left_viewer)
                var layout by mutableIntStateOf(ReaderNavigation.DEFAULT)
                var vertical by mutableStateOf(false)
                var generation by mutableIntStateOf(0)
                setContent {
                    key(generation) {
                        // A fresh state per layout: it shows the zones on start, as when a chapter opens
                        val state = remember { TapZonesOverlayState(DesktopPreferences(InMemoryPreferenceStore())) }
                        Box(Modifier.fillMaxSize()) {
                            TapZonesOverlay(state, ReaderNavigation.zones(layout, verticalViewer = vertical))
                            ReadingModeToast(visible = true, label = mode)
                        }
                    }
                }
                for (v in listOf(false, true)) {
                    for (l in ReaderNavigation.DEFAULT until ReaderNavigation.DISABLED) {
                        vertical = v
                        layout = l
                        generation++
                        waitForIdle()
                        val toast = onNodeWithTag("reading-mode-toast").fetchSemanticsNode().boundsInRoot
                        val labels = onAllNodesWithTag("tap-zone-label", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                        withClue("layout $l, vertical $v") {
                            labels.shouldNotBeEmpty()
                            labels.forEach { it.overlaps(toast) shouldBe false }
                            // Android names only the zones; the menu (everything else) has no label
                            onAllNodesWithText(localized(MR.strings.action_menu)).fetchSemanticsNodes().shouldBeEmpty()
                        }
                    }
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Nested
    inner class Overlays {

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

        @Test
        fun `ReaderOverlay transitions keep at most one overlay active`() {
            val target = PageActionsTarget(page = 1, extra = 2, leftFirst = true)
            var overlay: ReaderOverlay = ReaderOverlay.Hidden

            overlay = overlay.toggleMenu()
            overlay shouldBe ReaderOverlay.Menu

            // Switching to Settings replaces Menu instead of stacking
            overlay = overlay.toggleSettings()
            overlay shouldBe ReaderOverlay.Settings

            // setMenu(false) does not dismiss an unrelated dialog
            overlay = overlay.setMenu(false)
            overlay shouldBe ReaderOverlay.Settings

            // Switching to Chapters or Help replaces the active overlay
            overlay = overlay.toggleChapters()
            overlay shouldBe ReaderOverlay.Chapters
            overlay = overlay.toggleHelp()
            overlay shouldBe ReaderOverlay.Help

            // Toggling the active overlay dismisses it to Hidden
            overlay = overlay.toggleHelp()
            overlay shouldBe ReaderOverlay.Hidden

            // PageActions is only cleared by clearPageActions or dismiss
            overlay = ReaderOverlay.PageActions(target)
            overlay.toggleChapters() shouldBe ReaderOverlay.Chapters
            overlay.clearPageActions() shouldBe ReaderOverlay.Hidden
            ReaderOverlay.Settings.clearPageActions() shouldBe ReaderOverlay.Settings
            ReaderOverlay.PageActions(target).dismiss() shouldBe ReaderOverlay.Hidden
        }

        @Test
        fun `ReaderScreenModel overlay state handles menu, dialogs, and ready page actions`() = runBlocking<Unit> {
            val store = TestPreferenceStore()
            val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
            val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
            val source = mockk<HttpSource>(relaxed = true) {
                coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.invalid/0.png"))
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
                preferences = DesktopPreferences(store),
                incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns true }),
                syncManager = lazyOf(mockk(relaxed = true)),
                trackChapters = lazyOf(mockk(relaxed = true)),
                appScope = app,
            ).also { models += it }

            withTimeout(5_000) { model.state.first { it.pages.size == 1 && !it.loading } }
            model.overlay.value shouldBe ReaderOverlay.Hidden

            // Page actions cannot open for a page that is not Ready
            model.openPageActions(0)
            model.overlay.value shouldBe ReaderOverlay.Hidden

            model.state.value.pages[0].setState(ReaderPage.State.Ready(PageImage(100, 150, emptyList())))
            model.toggleMenu()
            model.overlay.value shouldBe ReaderOverlay.Menu

            model.openPageActions(0)
            model.overlay.value shouldBe ReaderOverlay.PageActions(PageActionsTarget(0))

            model.openSettings()
            model.overlay.value shouldBe ReaderOverlay.Settings

            model.dismissOverlay()
            model.overlay.value shouldBe ReaderOverlay.Hidden
        }

        @Test
        fun `ReaderConfig helpers route crop and nav invert by reading mode`() {
            val config = ReaderConfig(
                cropPaged = true,
                cropWebtoon = false,
                cropContinuousVertical = true,
                paged = PagedReaderConfig(navInverted = "HORIZONTAL"),
                webtoon = WebtoonReaderConfig(navInverted = "VERTICAL"),
            )
            config.cropEnabled(ReadingModes.RIGHT_TO_LEFT) shouldBe true
            config.cropEnabled(ReadingModes.WEBTOON) shouldBe false
            config.cropEnabled(ReadingModes.CONTINUOUS_VERTICAL) shouldBe true
            config.navInvert(webtoonMode = false) shouldBe "HORIZONTAL"
            config.navInvert(webtoonMode = true) shouldBe "VERTICAL"
            config.cropKey shouldBe Triple(true, false, true)
        }
    }

    @Nested
    inner class AppBars {

        private fun type(
            mode: Int,
            portrait: Boolean,
            force: Boolean = false,
            landscape: Boolean = false,
            left: Boolean = false,
        ) = navBarType(mode, portrait, force, landscape, left)

        @Test
        fun `the slider stands on the side only in vertical modes`() {
            type(ReadingModes.WEBTOON, portrait = true) shouldBe NavBarType.VerticalRight
            type(ReadingModes.CONTINUOUS_VERTICAL, portrait = true) shouldBe NavBarType.VerticalRight
            type(ReadingModes.VERTICAL, portrait = true) shouldBe NavBarType.VerticalRight
            type(ReadingModes.RIGHT_TO_LEFT, portrait = true) shouldBe NavBarType.Bottom
            type(ReadingModes.LEFT_TO_RIGHT, portrait = true, landscape = true) shouldBe NavBarType.Bottom
        }

        @Test
        fun `a wide window keeps it at the bottom unless the landscape setting is on`() {
            type(ReadingModes.WEBTOON, portrait = false) shouldBe NavBarType.Bottom
            type(ReadingModes.WEBTOON, portrait = false, landscape = true) shouldBe NavBarType.VerticalRight
            type(ReadingModes.WEBTOON, portrait = false, landscape = true, left = true) shouldBe NavBarType.VerticalLeft
        }

        @Test
        fun `force horizontal wins`() {
            type(ReadingModes.WEBTOON, portrait = true, force = true) shouldBe NavBarType.Bottom
            type(ReadingModes.WEBTOON, portrait = false, force = true, landscape = true) shouldBe NavBarType.Bottom
        }

        @Test
        fun `the page text shows both pages of a spread`() {
            currentPageText(ReaderScreenModel.State(currentPage = 4, firstShownPage = 4)) shouldBe "5"
            currentPageText(ReaderScreenModel.State(currentPage = 4, firstShownPage = 3)) shouldBe "4-5"
        }
    }
}
