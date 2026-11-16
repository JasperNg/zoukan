package zoukan.desktop.ui.browse

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class FeedRowHeaderTest {

    @Test
    fun `a right-click on a feed row does not remove it until confirmed`() = runDesktopComposeUiTest {
        var removed = 0
        setContent { FeedRowHeader(title = "Latest", fixed = false, onOpen = null, onRemove = { removed++ }) }
        onAllNodes(hasText("Latest")).onFirst().performMouseInput { rightClick() }
        waitForIdle()
        removed shouldBe 0
        onAllNodes(hasText("Remove") and hasClickAction()).onLast().performClick()
        waitForIdle()
        removed shouldBe 1
    }

    @Test
    fun `cancelling the question keeps the feed row`() = runDesktopComposeUiTest {
        var removed = 0
        setContent { FeedRowHeader(title = "Latest", fixed = false, onOpen = null, onRemove = { removed++ }) }
        onAllNodes(hasText("Latest")).onFirst().performMouseInput { rightClick() }
        waitForIdle()
        onAllNodes(hasText("Cancel") and hasClickAction()).onLast().performClick()
        waitForIdle()
        removed shouldBe 0
    }
}
