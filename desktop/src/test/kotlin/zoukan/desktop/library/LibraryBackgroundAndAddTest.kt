package zoukan.desktop.library

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.testing.Await
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Adding many entries at once (bulk add) must not fetch them all from the site at the same time. */
class AddToLibraryFetchTest {

    private fun manga(id: Long) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { source } returns 10L
        every { title } returns "Entry $id"
    }

    @Test
    fun `fetches after adding run a few at a time`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val finished = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val allStarted = CompletableDeferred<Unit>()
        val refreshManga = mockk<RefreshManga>()
        coEvery { refreshManga.invoke(any(), any(), any(), any(), any(), any()) } coAnswers {
            val now = running.incrementAndGet()
            most.accumulateAndGet(now, ::maxOf)
            if (now >= AddToLibrary.PARALLEL_FETCHES) allStarted.complete(Unit)
            release.await()
            running.decrementAndGet()
            finished.incrementAndGet()
            mockk(relaxed = true)
        }
        val preferences = mockk<LibraryPreferences> {
            every { fetchMetadataOnAdd() } returns mockk { every { get() } returns true }
            every { fetchChaptersOnAdd() } returns mockk { every { get() } returns true }
        }
        val addToLibrary = AddToLibrary(
            getCategories = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            getDuplicateLibraryManga = mockk(relaxed = true),
            libraryPreferences = preferences,
            addTracks = mockk(relaxed = true),
            refreshManga = refreshManga,
            sourceManager = mockk { every { get(10L) } returns mockk<Source>(relaxed = true) },
        )

        val count = 12
        // The limit's semaphore counts who asks for a permit
        val original = AddToLibrary.fetches
        val permits = Semaphore(AddToLibrary.PARALLEL_FETCHES)
        val asked = AtomicInteger()
        AddToLibrary.fetches = object : Semaphore by permits {
            override suspend fun acquire() {
                asked.incrementAndGet()
                permits.acquire()
            }
        }
        try {
            (1L..count).forEach { addToLibrary.add(manga(it), emptyList(), fetchOnAdd = true) }

            withTimeout(10_000) { allStarted.await() }
            // Every fetch has asked for a permit: the first ones hold them until released, so the
            // rest wait, and none past the limit can have started
            Await.untilSuspending("every fetch to ask for a permit") { asked.get() == count }
            running.get() shouldBe AddToLibrary.PARALLEL_FETCHES
            most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
            release.complete(Unit)
            Await.untilSuspending("every fetch to finish") { finished.get() >= count }
            most.get() shouldBeLessThanOrEqual AddToLibrary.PARALLEL_FETCHES
            finished.get() shouldBe count
        } finally {
            AddToLibrary.fetches = original
        }
    }
}

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

class PowerStatusTest {

    /** A process that never ends: reading its output blocks until it is destroyed. */
    private class HungProcess : Process() {
        private val ended = CountDownLatch(1)

        @Volatile var destroyed = false

        private val input = object : InputStream() {
            override fun read(): Int {
                ended.await()
                return -1
            }
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            ended.await()
            return 143
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int = if (ended.count == 0L) 143 else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            ended.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun `a power tool that never answers is given up after the timeout`() {
        val process = HungProcess()
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            PowerStatus.readOutput(process, timeoutMillis = 200) shouldBe null
        }
        process.destroyed.shouldBeTrue()
    }

    @Test
    fun `pmset output is read`() {
        PowerStatus.parsePmset("Now drawing from 'AC Power'") shouldBe true
        PowerStatus.parsePmset("Now drawing from 'Battery Power'") shouldBe false
        PowerStatus.parsePmset(null) shouldBe null
    }
}
