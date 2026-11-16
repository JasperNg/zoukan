package zoukan.desktop.ui.library

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import zoukan.desktop.library.LibraryUpdater

@OptIn(ExperimentalTestApi::class)
class UpdateErrorsRecompositionTest {
    @Test
    fun `progress ticks do not recompose a screen that only shows whether an update runs`() = runDesktopComposeUiTest {
        val progress = MutableStateFlow<LibraryUpdater.Progress?>(LibraryUpdater.Progress(0, 10, emptyList()))
        var compositions = 0
        setContent {
            val running by rememberLibraryUpdateRunning(progress)
            SideEffect { compositions++ }
            Text(if (running) "running" else "idle")
        }
        waitForIdle()
        repeat(10) { i ->
            progress.value = LibraryUpdater.Progress(i + 1, 10, listOf("Entry $i"))
            waitForIdle()
        }
        compositions shouldBe 1
        progress.value = null
        waitForIdle()
        onNodeWithText("idle").assertExists()
        compositions shouldBe 2
    }
}
