package zoukan.desktop.ui.browse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.util.concurrent.TimeoutException

/** The browse screens' source time limit: a slow source fails, leaving the screen still cancels. */
class SourceTimeoutTest {

    @Test
    fun `a fast source returns its result`() {
        runBlocking { withSourceTimeout(10_000) { "page" } } shouldBe "page"
    }

    @Test
    fun `a slow source fails with an ordinary exception, not a cancellation the callers rethrow`() {
        shouldThrow<TimeoutException> {
            runBlocking { withSourceTimeout(50) { delay(10_000) } }
        }
    }

    @Test
    fun `cancelling the caller still cancels the request`() {
        var caught: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    withSourceTimeout(10_000) { delay(10_000) }
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }
            delay(50)
            job.cancel()
            job.join()
            job.isCancelled shouldBe true
        }
        caught.shouldBeInstanceOf<CancellationException>()
        (caught is TimeoutException) shouldBe false
    }

    @Test
    fun `a timeout shows the translated message, other errors their own`() {
        sourceErrorMessage(TimeoutException("Timed out waiting for 60000 ms")) shouldBe
            localized(DMR.strings.source_timed_out)
        sourceErrorMessage(IllegalStateException("HTTP 503")) shouldBe "HTTP 503"
    }
}
