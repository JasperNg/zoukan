package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
}
