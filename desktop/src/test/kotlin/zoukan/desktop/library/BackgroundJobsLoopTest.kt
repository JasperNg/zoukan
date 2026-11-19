package zoukan.desktop.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The background-work loop survives an ordinary failure of one check (and an outdated extension's
 * LinkageError), but not the VM's own failures, and stops when it is stopped.
 */
class BackgroundJobsLoopTest {
    private val scheduler = TestCoroutineScheduler()
    private val uncaught = AtomicReference<Throwable?>(null)
    private val scope = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> uncaught.set(e) },
    )
    private val ticks = AtomicInteger()
    private val stoppedMessages = AtomicInteger()

    /** Jobs whose every check fails with [failure] at its first step (is the library update due?). */
    private fun jobs(failure: () -> Throwable): BackgroundJobs {
        val preferences = mockk<LibraryPreferences> {
            every { autoUpdateInterval() } answers {
                ticks.incrementAndGet()
                throw failure()
            }
        }
        return BackgroundJobs(
            preferences,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk { every { isInitialized } returns MutableStateFlow(true) },
            onStopped = { stoppedMessages.incrementAndGet() },
        )
    }

    /** The start delay and [checks] checks a minute apart. */
    private fun runChecks(checks: Int) {
        scheduler.advanceTimeBy(20_000L + (checks - 1) * 60_000L + 1)
        scheduler.runCurrent()
    }

    @Test
    fun `an ordinary failure is logged and the loop keeps running`() {
        val jobs = jobs { IllegalStateException("database busy") }
        jobs.start(scope)

        runChecks(3)

        ticks.get() shouldBe 3
        jobs.isRunning shouldBe true
        uncaught.get() shouldBe null
        scope.cancel()
    }

    @Test
    fun `an outdated extension's LinkageError does not end the loop`() {
        val jobs = jobs { NoClassDefFoundError("eu/kanade/tachiyomi/source/Removed") }
        jobs.start(scope)

        runChecks(3)

        ticks.get() shouldBe 3
        jobs.isRunning shouldBe true
        uncaught.get() shouldBe null
        scope.cancel()
    }

    @Test
    fun `an extension's runaway recursion does not end the loop`() {
        val jobs = jobs { StackOverflowError() }
        jobs.start(scope)

        runChecks(3)

        ticks.get() shouldBe 3
        jobs.isRunning shouldBe true
        uncaught.get() shouldBe null
        scope.cancel()
    }

    @Test
    fun `an OutOfMemoryError ends the loop and reaches the scope's handler`() {
        val jobs = jobs { OutOfMemoryError("Java heap space") }
        jobs.start(scope)

        runChecks(3)

        ticks.get() shouldBe 1
        jobs.isRunning shouldBe false
        uncaught.get().shouldBeInstanceOf<OutOfMemoryError>()
        scope.cancel()
    }

    @Test
    fun `an OutOfMemoryError tells the user once that background work stopped`() {
        val jobs = jobs { OutOfMemoryError("Java heap space") }
        jobs.start(scope)

        runChecks(3)

        stoppedMessages.get() shouldBe 1
        uncaught.get().shouldBeInstanceOf<OutOfMemoryError>()
        scope.cancel()
    }

    @Test
    fun `a message that cannot be posted still lets the OutOfMemoryError reach the handler`() {
        val preferences = mockk<LibraryPreferences> { every { autoUpdateInterval() } throws OutOfMemoryError("Java heap space") }
        val jobs = BackgroundJobs(
            preferences,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk { every { isInitialized } returns MutableStateFlow(true) },
            onStopped = { throw IllegalStateException("no strings") },
        )
        jobs.start(scope)

        runChecks(1)

        uncaught.get().shouldBeInstanceOf<OutOfMemoryError>()
        scope.cancel()
    }

    @Test
    fun `ordinary failures and runaway recursion tell the user nothing`() {
        val jobs = jobs { StackOverflowError() }
        jobs.start(scope)
        runChecks(3)
        stoppedMessages.get() shouldBe 0
        scope.cancel()
    }

    @Test
    fun `stopping ends the loop and no check runs after it`() {
        val jobs = jobs { IllegalStateException("database busy") }
        jobs.start(scope)
        runChecks(2)

        jobs.stop()
        runChecks(5)

        ticks.get() shouldBe 2
        jobs.isRunning shouldBe false
        uncaught.get() shouldBe null
        scope.cancel()
    }
}
