package zoukan.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.common.LocalReducedMotion
import zoukan.desktop.app.ReadingModes

@OptIn(ExperimentalTestApi::class)
class ReaderReducedMotionTest {
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
        onBack = {}, onToggleBookmark = {}, onAutoScroll = {}, onKeys = {}, onFullscreen = {},
        onChapters = {}, onWebView = {}, onBrowser = {}, onReadingMode = {}, onFitMode = {},
        onToggleCrop = {}, onPageLayout = {}, onSettings = {}, onPreviousChapter = {},
        onNextChapter = {}, onPage = {},
    )

    @Test
    fun `with reduced motion the reader menu appears without animation`() = runDesktopComposeUiTest(width = 1280, height = 820) {
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
