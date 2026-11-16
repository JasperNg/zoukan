package zoukan.desktop.ui.browse

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.ui.common.Images

@OptIn(ExperimentalTestApi::class)
class BrowseSourceContentPagingTest {

    private fun entries(count: Int) = (1..count).map { Manga.create().copy(id = it.toLong(), ogTitle = "Entry $it", source = 1L) }

    @Test
    fun `a page that fits the window asks for the next page once it lands`() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        // The list rows load covers through the app's image loader
        Images.init(kotlin.io.path.createTempDirectory("paging-covers").toFile())
        var items by mutableStateOf(emptyList<Manga>())
        var nearEnd = 0
        setContent {
            BrowseSourceContent(
                source = null,
                items = items,
                metadata = emptyMap(),
                favorites = emptySet(),
                displayMode = LibraryDisplayMode.List,
                ehentaiBrowseDisplayMode = false,
                listingKey = "popular",
                isSelected = { false },
                onMangaClick = {},
                onMangaSecondaryClick = {},
                onNearEnd = { nearEnd++ },
                footer = { Text("footer") },
            )
        }
        waitForIdle()
        // The footer-only first frame asks (the model ignores it while its own first load runs)
        (nearEnd >= 1) shouldBe true
        val beforePage = nearEnd

        // The page lands and fits the window: the end is still visible, so it must ask again
        items = entries(6)
        waitForIdle()
        (nearEnd > beforePage) shouldBe true
    }
}
