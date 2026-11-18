package zoukan.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
        val requesting = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch {
                try {
                    withSourceTimeout(10_000) {
                        requesting.complete(Unit)
                        delay(10_000)
                    }
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }
            requesting.await()
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

    @Test
    fun `a blocked site asks to open it in the browser`() {
        sourceErrorMessage(HttpException(403)) shouldBe localized(DMR.strings.source_blocked_open_the_site)
        sourceErrorMessage(HttpException(503)) shouldBe localized(DMR.strings.source_blocked_open_the_site)
        sourceErrorMessage(HttpException(500)) shouldBe "HTTP error 500"
    }

    @Test
    fun `no network and an unknown host get their own message`() {
        sourceErrorMessage(UnknownHostException("example.org")) shouldBe localized(DMR.strings.source_no_connection)
        sourceErrorMessage(ConnectException()) shouldBe localized(DMR.strings.source_no_connection)
        sourceErrorMessage(SocketTimeoutException()) shouldBe localized(DMR.strings.source_timed_out)
    }
}
