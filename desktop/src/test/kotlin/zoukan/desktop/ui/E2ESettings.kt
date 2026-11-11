package zoukan.desktop.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.openSettingsCategory

/**
 * Opens [page] in Settings and returns the list of its settings. Settings shows the list of pages
 * on the left and the open page on the right; both scroll, so the page is the scrolling list that
 * does not list the pages.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.settingsPage(page: SettingsCategory): SemanticsNodeInteraction {
    runOnUiThread { openSettingsCategory(page) }
    waitForIdle()
    val pageList = hasScrollAction() and !hasAnyDescendant(hasText(SettingsCategory.Appearance.subtitle))
    waitUntil(timeoutMillis = 10_000) { onAllNodes(pageList).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(pageList).onFirst()
}
