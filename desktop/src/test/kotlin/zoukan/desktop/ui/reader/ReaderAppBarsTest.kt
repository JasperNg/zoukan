package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.common.ReadingModes

class ReaderAppBarsTest {
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
