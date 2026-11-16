package zoukan.desktop.ui.reader

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.reader.ReaderNavigation.Region.LEFT
import zoukan.desktop.ui.reader.ReaderNavigation.Region.RIGHT

@OptIn(ExperimentalTestApi::class)
class TapZoneLabelsTest {

    private val next = localized(MR.strings.nav_zone_next)
    private val previous = localized(MR.strings.nav_zone_prev)
    private val left = localized(MR.strings.nav_zone_left)
    private val right = localized(MR.strings.nav_zone_right)

    @Test
    fun `right to left labels say which side turns to the next page`() {
        regionLabel(LEFT, rtl = true) shouldContain next
        regionLabel(RIGHT, rtl = true) shouldContain previous
        // Left to right is the opposite
        regionLabel(LEFT, rtl = false) shouldContain previous
        regionLabel(RIGHT, rtl = false) shouldContain next
        // The vertical and webtoon viewers go forward on the right in either direction
        regionLabel(RIGHT, vertical = true) shouldContain next
        regionLabel(LEFT, vertical = true) shouldContain previous
        regionLabel(RIGHT, rtl = true, vertical = true) shouldContain next
        regionLabel(LEFT, rtl = true, vertical = true) shouldContain previous
        // Each label still names its side
        regionLabel(LEFT, rtl = true) shouldContain left
        regionLabel(RIGHT, rtl = true) shouldContain right
    }

    @Test
    fun `the overlay labels the centre as the menu`() = runDesktopComposeUiTest {
        val state = TapZonesOverlayState(DesktopPreferences(InMemoryPreferenceStore()))
        setContent {
            TapZonesOverlay(state, ReaderNavigation.zones(ReaderNavigation.DEFAULT, verticalViewer = false))
        }
        waitForIdle()
        // The label is drawn twice, an outline and the fill
        onAllNodesWithText(localized(MR.strings.action_menu)).onFirst().assertIsDisplayed()
    }
}
