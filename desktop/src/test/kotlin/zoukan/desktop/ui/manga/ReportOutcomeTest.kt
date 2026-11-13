package zoukan.desktop.ui.manga

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Merge and migration report real failures, never a cancellation. */
class ReportOutcomeTest {

    @Test
    fun `a failure is reported and rethrown`() {
        val reported = mutableListOf<Throwable>()
        val failure = IllegalStateException("boom")

        shouldThrow<IllegalStateException> {
            runBlocking { reportOutcome(onFailure = { reported += it }) { throw failure } }
        }

        reported shouldBe listOf(failure)
    }

    @Test
    fun `a cancellation is rethrown without a failure report`() {
        val reported = mutableListOf<Throwable>()

        shouldThrow<CancellationException> {
            runBlocking { reportOutcome(onFailure = { reported += it }) { throw CancellationException("left the screen") } }
        }

        reported shouldBe emptyList()
    }

    @Test
    fun `a success returns the result without a report`() {
        val reported = mutableListOf<Throwable>()

        runBlocking { reportOutcome(onFailure = { reported += it }) { 42 } } shouldBe 42
        reported shouldBe emptyList()
    }
}
