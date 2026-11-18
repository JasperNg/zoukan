package zoukan.desktop.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.io.IOException

/** What runCatchingCancellable turns into a failure, and what it lets through (Cancellation.kt). */
class RunCatchingCancellableTest {
    @Test
    fun `an exception and an outdated extension's LinkageError become a failure`() {
        runCatchingCancellable { throw IOException("offline") }.exceptionOrNull()?.message shouldBe "offline"
        runCatchingCancellable { throw NoSuchMethodError("removed") }.isFailure shouldBe true
        runCatchingCancellable { 1 }.getOrNull() shouldBe 1
    }

    @Test
    fun `a cancel and running out of memory go on up`() {
        shouldThrow<CancellationException> { runCatchingCancellable { throw CancellationException("closed") } }
        shouldThrow<OutOfMemoryError> { runCatchingCancellable { throw OutOfMemoryError() } }
    }
}
