package zoukan.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class HomeTabSelectionTest {
    private val withoutUpdates = HomeTab.entries - HomeTab.Updates

    @Test
    fun `a tab the rail hides is not selected by its shortcut`() {
        val tabs = HomeTabState(HomeTab.Browse).apply { showOnly(withoutUpdates) }
        tabs.select(HomeTab.Updates)
        tabs.selected shouldBe HomeTab.Browse
    }

    @Test
    fun `turning off the shown tab goes back to the library`() {
        val tabs = HomeTabState(HomeTab.Updates)
        tabs.showOnly(withoutUpdates)
        tabs.selected shouldBe HomeTab.Library
    }

    @Test
    fun `a tab chosen before the home screen is shown is applied when it is`() {
        HomeScreen.selectTab(HomeTab.Browse)
        val tabs = HomeTabState()
        HomeScreen.register(tabs)
        try {
            tabs.selected shouldBe HomeTab.Browse
        } finally {
            HomeScreen.unregister(tabs)
        }
    }

    @Test
    fun `two window-scoped HomeControllers isolate tab selection and library search`() {
        val windowOne = HomeController()
        val windowTwo = HomeController()
        val searchesOne = mutableListOf<String>()
        val searchesTwo = mutableListOf<String>()
        val unbindOne = windowOne.bindLibrarySearch { searchesOne += it }
        val unbindTwo = windowTwo.bindLibrarySearch { searchesTwo += it }
        try {
            windowOne.selectTab(HomeTab.Browse)
            windowTwo.selectTab(HomeTab.Updates)
            windowOne.tabs.selected shouldBe HomeTab.Browse
            windowTwo.tabs.selected shouldBe HomeTab.Updates

            windowOne.searchLibrary("alpha")
            windowOne.tabs.selected shouldBe HomeTab.Library
            windowTwo.tabs.selected shouldBe HomeTab.Updates
            searchesOne shouldBe listOf("alpha")
            searchesTwo shouldBe emptyList()
        } finally {
            unbindOne()
            unbindTwo()
        }
    }

    @Test
    fun `LocalHomeController scopes tab updates to each window composition`() = runDesktopComposeUiTest {
        val windowOne = HomeController()
        val windowTwo = HomeController()

        setContent {
            Column {
                CompositionLocalProvider(LocalHomeController provides windowOne) {
                    val home = LocalHomeController.current!!
                    Button(
                        onClick = { home.selectTab(HomeTab.Browse) },
                        modifier = Modifier.testTag("select-browse-one"),
                    ) { Text("One") }
                }
                CompositionLocalProvider(LocalHomeController provides windowTwo) {
                    val home = LocalHomeController.current!!
                    Button(
                        onClick = { home.selectTab(HomeTab.More) },
                        modifier = Modifier.testTag("select-more-two"),
                    ) { Text("Two") }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("select-browse-one").performClick()
        waitForIdle()
        windowOne.tabs.selected shouldBe HomeTab.Browse
        windowTwo.tabs.selected shouldBe HomeTab.Library

        onNodeWithTag("select-more-two").performClick()
        waitForIdle()
        windowOne.tabs.selected shouldBe HomeTab.Browse
        windowTwo.tabs.selected shouldBe HomeTab.More
    }
}
