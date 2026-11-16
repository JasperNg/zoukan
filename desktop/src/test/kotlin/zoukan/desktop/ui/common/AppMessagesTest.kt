package zoukan.desktop.ui.common

import androidx.compose.material3.SnackbarHostState
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
@OptIn(ExperimentalCoroutinesApi::class)
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
}
