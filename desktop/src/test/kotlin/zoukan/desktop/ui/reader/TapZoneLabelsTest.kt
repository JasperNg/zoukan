package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
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
    fun `the overlay leaves the centre clear, as Android, so the reading mode toast can be read`() = runDesktopComposeUiTest(1000, 800) {
        val mode = localized(MR.strings.right_to_left_viewer)
        var layout by mutableIntStateOf(ReaderNavigation.DEFAULT)
        var vertical by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        setContent {
            key(generation) {
                // A fresh state per layout: it shows the zones on start, as when a chapter opens
                val state = remember { TapZonesOverlayState(DesktopPreferences(InMemoryPreferenceStore())) }
                Box(Modifier.fillMaxSize()) {
                    TapZonesOverlay(state, ReaderNavigation.zones(layout, verticalViewer = vertical))
                    ReadingModeToast(visible = true, label = mode)
                }
            }
        }
        for (v in listOf(false, true)) {
            for (l in ReaderNavigation.DEFAULT until ReaderNavigation.DISABLED) {
                vertical = v
                layout = l
                generation++
                waitForIdle()
                val toast = onNodeWithTag("reading-mode-toast").fetchSemanticsNode().boundsInRoot
                val labels = onAllNodesWithTag("tap-zone-label", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                withClue("layout $l, vertical $v") {
                    labels.shouldNotBeEmpty()
                    labels.forEach { it.overlaps(toast) shouldBe false }
                    // Android names only the zones; the menu (everything else) has no label
                    onAllNodesWithText(localized(MR.strings.action_menu)).fetchSemanticsNodes().shouldBeEmpty()
                }
            }
        }
    }
}
