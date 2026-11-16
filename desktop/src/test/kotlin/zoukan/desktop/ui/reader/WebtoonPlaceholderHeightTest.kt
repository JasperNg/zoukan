package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class WebtoonPlaceholderHeightTest {

    private fun androidx.compose.ui.test.ComposeUiTest.placeholderHeight(page: ReaderPage): Float {
        setContent {
            Box(Modifier.width(400.dp)) { WebtoonPagePlaceholder(page, number = 1, color = Color.White) }
        }
        val bounds = onNodeWithTag("page-placeholder").getUnclippedBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    @Test
    fun `a released page keeps its decoded height as its placeholder`() = runDesktopComposeUiTest(width = 400, height = 800) {
        val page = ReaderPage(0, Page(0, imageUrl = "x"))
        // Decoded at 800 x 4000, then released the way the model does
        page.rememberSize(800, 4000)
        page.state.value = ReaderPage.State.Ready(PageImage(800, 4000, emptyList()))
        page.state.value = ReaderPage.State.Queued
        // 400 dp wide at 1:5
        placeholderHeight(page) shouldBe (2000f plusOrMinus 1f)
    }

    @Test
    fun `a page never decoded uses the default shape`() = runDesktopComposeUiTest(width = 400, height = 800) {
        placeholderHeight(ReaderPage(0, Page(0, imageUrl = "x"))) shouldBe (400f / 0.7f plusOrMinus 1f)
    }
}
