package zoukan.desktop.ui.manga

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class PagePreviewScreenTest {

    @Test
    fun `previews that repeat a page number across preview pages all show`() = runDesktopComposeUiTest {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build() // no fetcher: thumbnails stay empty
        var previews by mutableStateOf(
            listOf(
                PagePreviewImage(1L, 1, "https://example.org/p1-1.jpg"),
                PagePreviewImage(1L, 2, "https://example.org/p1-2.jpg"),
            ),
        )
        setContent {
            MaterialTheme {
                PagePreviewGrid(previews, loading = false, error = null, onLoadMore = {}, onOpen = {}, loader = loader)
            }
        }
        waitForIdle()
        // The second preview page restarts its numbering at 1
        previews = previews + listOf(
            PagePreviewImage(1L, 1, "https://example.org/p2-1.jpg"),
            PagePreviewImage(1L, 2, "https://example.org/p2-2.jpg"),
        )
        waitForIdle()
        onAllNodesWithText("1").assertCountEquals(2)
        onAllNodesWithText("2").assertCountEquals(2)
    }

    @Test
    fun `a preview listed on two preview pages shows once`() {
        val a = PagePreviewImage(1L, 1, "https://example.org/a.jpg")
        val b = PagePreviewImage(1L, 2, "https://example.org/b.jpg")
        val c = PagePreviewImage(1L, 3, "https://example.org/c.jpg")
        appendPreviews(listOf(a, b), listOf(b, c)) shouldBe listOf(a, b, c)
    }
}
