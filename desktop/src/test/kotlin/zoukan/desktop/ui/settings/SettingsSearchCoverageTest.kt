package zoukan.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test

/** What settings search can find: the choices of a setting and the terms of sections that are not shown. */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchCoverageTest {

    @Test
    fun `a choice setting is found by any of its choices`() = runDesktopComposeUiTest {
        val texts = SearchTexts()
        setContent {
            CompositionLocalProvider(LocalSearchTexts provides texts, LocalSearchQuery provides "Weekly") {
                ChoiceSetting("Interval", 1, listOf(1 to "Daily", 2 to "Weekly")) {}
            }
        }
        texts.texts shouldContain "Weekly"
        onNodeWithText("Interval").assertExists()
    }

    @Test
    fun `search terms of a hidden section are reported`() = runDesktopComposeUiTest {
        val texts = SearchTexts()
        setContent {
            CompositionLocalProvider(LocalSearchTexts provides texts) { SearchTerms("Use Japanese titles") }
        }
        texts.texts shouldContain "Use Japanese titles"
    }
}
