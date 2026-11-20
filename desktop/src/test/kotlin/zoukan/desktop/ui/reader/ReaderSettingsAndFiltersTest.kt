package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
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
import tachiyomi.i18n.action_close
import tachiyomi.i18n.action_filter
import tachiyomi.i18n.automatic_background
import tachiyomi.i18n.pref_category_general
import tachiyomi.i18n.pref_reader_theme
import tachiyomi.i18n.scale_type_smart_fit
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.action_save_combined_page
import tachiyomi.i18n.sy.action_set_second_page_cover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.common.LocalReducedMotion
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderFilter.Companion.readerFilter
import zoukan.desktop.ui.settings.ReaderSettingRow
import zoukan.desktop.ui.settings.ReaderSettingRows
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.inverted_colors
import zoukan.i18n.desktop.page
import zoukan.i18n.desktop.pages_and
import zoukan.i18n.desktop.reading_mode_for_this_entry
import zoukan.i18n.desktop.shift_double_pages
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Tests for reader settings parity and UI ([ReaderSettingRows], [ReaderSettingsDialog]),
 * [ReaderFilter] color matrices/compositing, and reduced-motion app bar transitions.
 */
class ReaderSettingsAndFiltersTest {

    /**
     * The reader's settings sheet and Settings > Reader are built from one list of rows. A setting
     * that only one of them shows has to be named here with its reason, so a setting added to one
     * side and forgotten on the other fails this test.
     */
    @Nested
    inner class SettingsParity {

        private val prefs = DesktopPreferences(TestPreferenceStore())

        private fun keys(rows: List<ReaderSettingRow>): Set<String> =
            rows.filterIsInstance<ReaderSettingRow.Keyed>().map { it.preference.key() }.toSet()

        private fun keysOf(vararg preferences: Preference<*>): Set<String> = preferences.map { it.key() }.toSet()

        /** The rows of the Settings group [id], up to the next group. */
        private fun group(rows: List<ReaderSettingRow>, id: String): List<ReaderSettingRow> =
            rows.dropWhile { !(it is ReaderSettingRow.Group && it.id == id) }.drop(1).takeWhile { it !is ReaderSettingRow.Group }

        private fun isWebtoon(mode: Int) = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL

        // Only the sheet: the Filter tab, the theme and the menu's own options, which are not reading behaviour
        private val sheetOnlyCommon = keysOf(
            prefs.readerTheme(), // the sheet's General tab; Settings has the theme under Appearance
            prefs.showPageNumber(),
            prefs.skipRead(), // chapters to skip: the sheet's General tab
            prefs.skipFiltered(),
            prefs.skipDupe(),
            prefs.folderPerManga(),
            prefs.forceHorizontalSeekbar(), // the menu's slider placement
            prefs.landscapeVerticalSeekbar(),
            prefs.leftVerticalSeekbar(),
            prefs.readerBottomButtons(), // the menu's bottom bar
            prefs.autoscrollInterval(), // auto scroll is started from the reader
            prefs.customBrightness(), // the Filter tab: screen filters belong to the reading session
            prefs.customBrightnessValue(),
            prefs.colorFilter(),
            prefs.colorFilterValue(),
            prefs.colorFilterMode(),
            prefs.grayscale(),
            prefs.invertedColors(),
        )

        // Only the sheet, and only for the viewer in use
        private val sheetOnlyPaged = keysOf(
            prefs.imageScaleType(), // how a page fits the window
            prefs.invertDoublePages(),
        )
        private val sheetOnlyWebtoon = keysOf(
            prefs.webtoonSidePadding(),
        )

        // Only Settings: options that apply before a chapter opens or to every session
        private val settingsOnlyCommon = keysOf(
            prefs.showNavigationOverlayOnStart(),
            prefs.flashOnPageChange(), // e-ink flash: a device setting
            prefs.flashColor(),
            prefs.flashDurationMillis(),
            prefs.flashPageInterval(),
            prefs.useAutoWebtoon(), // decided when the entry opens
            prefs.preserveReadingPosition(),
            prefs.preloadSize(), // page loading: needs no reader open
            prefs.readerThreads(),
            prefs.aggressivePageLoading(),
            prefs.readerInstantRetry(),
        )

        // The sheet reads this as a condition for the webtoon scale type and does not list it
        private val settingsOnlyWebtoonMode = keysOf(prefs.longStripGapSmartScale())

        @Test
        fun `the reader sheet and Settings show the same reader settings except an allowed list`() {
            val settings = ReaderSettingRows.settings(prefs)
            val settingsKeys = keys(settings)
            val pagerKeys = keys(group(settings, "pager"))
            // SY's Vertical+ group sits under the webtoon viewers on the sheet, as on Android
            val webtoonKeys = keys(group(settings, "webtoon")) + keys(group(settings, "vertical_plus"))
            for (mode in ReadingModes.all) {
                val sheetKeys = keys(ReaderSettingRows.sheet(prefs, mode))
                val webtoon = isWebtoon(mode)
                val sheetOnly = sheetOnlyCommon + if (webtoon) sheetOnlyWebtoon else sheetOnlyPaged
                // The other viewer's section is not on the sheet for this mode
                val settingsOnly = settingsOnlyCommon +
                    if (webtoon) pagerKeys + settingsOnlyWebtoonMode else webtoonKeys
                (sheetKeys - settingsKeys) shouldBe sheetOnly
                (settingsKeys - sheetKeys) shouldBe settingsOnly
            }
        }

        @Test
        fun `continuous vertical crop borders is in Settings and the webtoon sheets, under Vertical+`() {
            val key = prefs.cropBordersContinuousVertical().key()
            keys(group(ReaderSettingRows.settings(prefs), "vertical_plus")) shouldBe setOf(key)
            for (mode in ReadingModes.all) {
                (key in keys(ReaderSettingRows.sheet(prefs, mode))) shouldBe isWebtoon(mode)
            }
        }
    }

    /**
     * Tests [ReaderSettingsDialog] preference changes and [ReaderScreen] double-page layout preservation
     * and page actions menu updates after switching between double and single page layouts.
     */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Nested
    inner class SettingsUi {

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
            try {
                models.forEach { it.onDispose() }
                runBlocking {
                    withTimeout(5_000) {
                        models.forEach { model ->
                            val job = model.screenModelScope.coroutineContext[Job]!!
                            job.cancelChildren()
                            job.children.toList().joinAll()
                        }
                        app.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
                models.clear()
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
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
        }

        @Test
        fun `ReaderSettingsDialog updates scale type, reader theme, and shift double pages`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
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
                val store = TestPreferenceStore()
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

    @OptIn(ExperimentalTestApi::class)
    @Nested
    inner class DisplayFilters {

        @Test
        fun `grayscale and inverted together give the product of the two matrices`() {
            val expected = floatArrayOf(
                -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
                -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
                -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
                0f, 0f, 0f, 1f, 0f,
            )
            val actual = ReaderFilter.matrix(ReaderFilter(grayscale = true, inverted = true))!!.mat
            actual.size shouldBe 20
            expected.forEachIndexed { i, e -> actual[i] shouldBe (e plusOrMinus 1e-4f) }
        }

        @Test
        fun `only a blend overlay needs offscreen compositing`() {
            ReaderFilter.compositingFor(ReaderFilter(grayscale = true)) shouldBe CompositingStrategy.Auto
            ReaderFilter.compositingFor(ReaderFilter(color = 0x80FF0000.toInt(), mode = 1)) shouldBe CompositingStrategy.Offscreen
            ReaderFilter.compositingFor(ReaderFilter(brightness = -40)) shouldBe CompositingStrategy.Offscreen
        }

        @Test
        fun `the colour filter is built once while the filter does not change`() = runDesktopComposeUiTest {
            var tick by mutableStateOf(0)
            setContent {
                Text("$tick")
                val f = ReaderFilter(grayscale = true)
                Box(Modifier.size(100.dp).readerFilter(f))
            }
            waitForIdle()
            ReaderFilter.effectsBuilt = 0
            repeat(5) {
                tick++
                waitForIdle()
            }
            ReaderFilter.effectsBuilt shouldBeLessThanOrEqualTo 1
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Nested
    inner class ReducedMotion {

        private val menu = ReaderMenuState(
            mangaTitle = "Title",
            chapterTitle = "Chapter 1",
            bookmarked = false,
            autoScroll = false,
            fullscreen = false,
            readingMode = ReadingModes.RIGHT_TO_LEFT,
            fitMode = 0,
            cropEnabled = false,
            pageLayout = 0,
            buttons = emptySet(),
            forceHorizontalSlider = false,
            verticalSliderInLandscape = false,
            leftHandedSlider = false,
            currentPage = 0,
            currentPageText = "1",
            totalPages = 10,
            hasPrevious = false,
            hasNext = false,
            generation = 0,
        )

        private val actions = ReaderMenuActions(
            onBack = {},
            onToggleBookmark = {},
            onAutoScroll = {},
            onKeys = {},
            onFullscreen = {},
            onChapters = {},
            onWebView = {},
            onBrowser = {},
            onReadingMode = {},
            onFitMode = {},
            onToggleCrop = {},
            onPageLayout = {},
            onSettings = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onPage = {},
        )

        @Test
        fun `with reduced motion the reader menu appears without animation`() =
            runDesktopComposeUiTest(width = 1280, height = 820) {
                var visible by mutableStateOf(false)
                setContent {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        ReaderAppBars(visible, menu, actions)
                    }
                }
                mainClock.autoAdvance = false
                visible = true
                mainClock.advanceTimeByFrame()
                val top = onNodeWithText("Title").getUnclippedBoundsInRoot().top
                // The bar is already in its final place, not still sliding in from above the window
                top.value shouldBeGreaterThanOrEqual 0f
                (top < 60.dp) shouldBe true

                visible = false
                // A cut needs a frame to notice the end of its empty transition; a 150 ms fade would still show
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                onAllNodesWithText("Title").fetchSemanticsNodes().size shouldBe 0
            }
    }
}
