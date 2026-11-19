package zoukan.desktop.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The app's messages: the newest one replaces the one shown, and held-back ones show only the newest. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class AppMessagesTest {

    // showSnackbar suspends until the message is dismissed when no host is composed, which is what these need

    @Test
    fun `a new message replaces the one on screen`() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val snackbar = SnackbarHostState()
        val shown = launch { showMessages(messages, snackbar) {} }
        runCurrent()
        messages.emit("Added to library")
        runCurrent()
        snackbar.currentSnackbarData?.visuals?.message shouldBe "Added to library"
        messages.emit("Restored 1 entry")
        runCurrent()
        snackbar.currentSnackbarData?.visuals?.message shouldBe "Restored 1 entry"
        shown.cancel()
    }

    @Test
    fun `messages held back show only the newest when released`() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val snackbar = SnackbarHostState()
        val held = MutableStateFlow(1)
        val shown = launch { showMessages(messages, snackbar) { held.first { it == 0 } } }
        runCurrent()
        messages.emit("one")
        messages.emit("two")
        runCurrent()
        snackbar.currentSnackbarData shouldBe null
        held.value = 0
        runCurrent()
        snackbar.currentSnackbarData?.visuals?.message shouldBe "two"
        shown.cancel()
    }

    @Test
    fun `overlapping insets keep the highest active reservation when disposed out of order`() {
        val messages = AppMessages()
        messages.bottomInset shouldBe 0.dp

        val releaseLarger = messages.reserveBottomInset(80.dp)
        messages.bottomInset shouldBe 80.dp

        val releaseSmaller = messages.reserveBottomInset(48.dp)
        messages.bottomInset shouldBe 80.dp

        // Disposing the first (larger) inset falls back to the still-active smaller inset, not 0.dp
        releaseLarger()
        messages.bottomInset shouldBe 48.dp

        // Releasing the same token twice is a no-op
        releaseLarger()
        messages.bottomInset shouldBe 48.dp

        releaseSmaller()
        messages.bottomInset shouldBe 0.dp
    }

    @Test
    fun `equal overlapping insets stay active until every reservation is released`() {
        val messages = AppMessages()
        val releaseFirst = messages.reserveBottomInset(56.dp)
        val releaseSecond = messages.reserveBottomInset(56.dp)
        messages.bottomInset shouldBe 56.dp

        releaseFirst()
        messages.bottomInset shouldBe 56.dp

        releaseSecond()
        messages.bottomInset shouldBe 0.dp
    }

    @Test
    fun `zero inset reservation does not disturb an active reservation`() {
        val messages = AppMessages()
        val releaseActive = messages.reserveBottomInset(40.dp)
        val releaseZero = messages.reserveBottomInset(0.dp)
        messages.bottomInset shouldBe 40.dp

        releaseZero()
        messages.bottomInset shouldBe 40.dp

        releaseActive()
        messages.bottomInset shouldBe 0.dp
    }

    @Test
    fun `overlapping ReserveSnackbarSpace composables keep remaining inset on out-of-order dispose`() = runDesktopComposeUiTest {
        val messages = AppMessages()
        var showOuter by mutableStateOf(true)
        var showInner by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalAppMessages provides messages) {
                if (showOuter) ReserveSnackbarSpace(72.dp)
                if (showInner) ReserveSnackbarSpace(48.dp)
            }
        }
        waitForIdle()
        messages.bottomInset shouldBe 72.dp

        runOnUiThread { showOuter = false }
        waitForIdle()
        messages.bottomInset shouldBe 48.dp

        runOnUiThread { showInner = false }
        waitForIdle()
        messages.bottomInset shouldBe 0.dp
    }
}
