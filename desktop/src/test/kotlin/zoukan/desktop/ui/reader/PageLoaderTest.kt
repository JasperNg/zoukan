package zoukan.desktop.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import zoukan.desktop.local.LocalFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PageLoaderTest {

    private fun pages(count: Int): List<ReaderPage> =
        List(count) { ReaderPage(it, Page(it, imageUrl = "https://example.invalid/$it.png")) }

    private fun stubSource(): HttpSource = mockk(relaxed = true)

    @Test
    fun `downloads are bounded by readerThreads semaphore`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val gates = List(6) { CompletableDeferred<Unit>() }
            val allStarted = CompletableDeferred<Unit>()
            val startedCount = AtomicInteger(0)

            val loader = PageLoader(
                scope = scope,
                appScope = appScope,
                readerThreads = 2,
                preloadSize = { 10 },
                instantRetry = { false },
                cropBorders = { false },
                ioDispatcher = Dispatchers.Default,
                mainDispatcher = Dispatchers.Default,
                fetchOnlinePage = { _, page ->
                    val current = active.incrementAndGet()
                    maxActive.accumulateAndGet(current, ::maxOf)
                    if (startedCount.incrementAndGet() == 2) allStarted.complete(Unit)
                    gates[page.index].await()
                    active.decrementAndGet()
                    byteArrayOf(page.index.toByte())
                },
                decodeImage = { _, _, _ -> PageImage(80, 120, emptyList()) },
                errorMessage = { it.message ?: "error" },
            )
            val chapterPages = pages(6)
            loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = false)

            repeat(6) { loader.loadPage(it) }
            allStarted.await()
            maxActive.get() shouldBeLessThanOrEqual 2

            gates.forEach { it.complete(Unit) }
            chapterPages.forEach { page ->
                zoukan.desktop.testing.Await.until("page ${page.index} ready", 5_000) {
                    page.state.value is ReaderPage.State.Ready
                }
            }
            maxActive.get() shouldBe 2
        } finally {
            scope.cancel()
            appScope.cancel()
        }
    }

    @Test
    fun `requestPages preloads window, releases distant images, and retains bytes when aggressive`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapterPages = pages(25)
        val decoded = CopyOnWriteArrayList<Int>()
        val loader = PageLoader(
            scope = this,
            appScope = this,
            readerThreads = 4,
            preloadSize = { 2 },
            instantRetry = { false },
            cropBorders = { false },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            fetchOnlinePage = { _, page ->
                decoded += page.index
                byteArrayOf(page.index.toByte())
            },
            decodeImage = { _, _, _ -> PageImage(100, 150, emptyList()) },
            errorMessage = { it.message ?: "error" },
        )
        loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = false)

        loader.requestPages(center = 0)
        advanceUntilIdle()

        // center - 2..center + 2 => pages 0, 1, 2
        chapterPages[0].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        chapterPages[1].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        chapterPages[2].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        chapterPages[3].state.value shouldBe ReaderPage.State.Queued

        // Move far ahead so page 0 leaves both decoded window and byte retention range
        loader.requestPages(center = 20)
        advanceUntilIdle()

        chapterPages[0].state.value shouldBe ReaderPage.State.Queued
        chapterPages[0].bytes shouldBe null
        chapterPages[20].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
    }

    @Test
    fun `aggressive prefetch retains bytes for distant pages`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapterPages = pages(20)
        val loader = PageLoader(
            scope = this,
            appScope = this,
            readerThreads = 2,
            preloadSize = { 1 },
            instantRetry = { false },
            cropBorders = { false },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            fetchOnlinePage = { _, page -> byteArrayOf(page.index.toByte()) },
            decodeImage = { _, _, _ -> PageImage(100, 150, emptyList()) },
            errorMessage = { it.message ?: "error" },
        )
        loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = true)
        advanceUntilIdle()

        chapterPages.all { it.bytes != null } shouldBe true

        loader.requestPages(center = 18)
        advanceUntilIdle()

        // Page 0 image was released, but its bytes stay cached because aggressive prefetch is on
        chapterPages[0].state.value shouldBe ReaderPage.State.Queued
        chapterPages[0].bytes shouldBe byteArrayOf(0)
    }

    @Test
    fun `instant retry attempts once on failure and stops after second failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = AtomicInteger(0)
        var failFirstOnly = true
        val chapterPages = pages(2)
        val loader = PageLoader(
            scope = this,
            appScope = this,
            readerThreads = 2,
            preloadSize = { 2 },
            instantRetry = { true },
            cropBorders = { false },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            fetchOnlinePage = { _, page ->
                val count = attempts.incrementAndGet()
                if (page.index == 0 && count == 1) error("transient")
                if (page.index == 1 && !failFirstOnly) error("persistent")
                byteArrayOf(page.index.toByte())
            },
            decodeImage = { _, _, _ -> PageImage(100, 150, emptyList()) },
            errorMessage = { it.message ?: "error" },
        )
        loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = false)

        loader.loadPage(0)
        advanceUntilIdle()
        chapterPages[0].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        attempts.get() shouldBe 2

        attempts.set(0)
        failFirstOnly = false
        loader.loadPage(1)
        advanceUntilIdle()
        val errorState = chapterPages[1].state.value.shouldBeInstanceOf<ReaderPage.State.Error>()
        errorState.message shouldBe "persistent"
        attempts.get() shouldBe 2
    }

    @Test
    fun `boostPage cancels other in-flight pages and prioritizes currentPage`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = AtomicInteger(0)
            val gate0 = CompletableDeferred<Unit>()
            val gate5Started = CompletableDeferred<Unit>()
            val loader = PageLoader(
                scope = scope,
                appScope = appScope,
                readerThreads = 1,
                preloadSize = { 1 },
                instantRetry = { false },
                cropBorders = { false },
                ioDispatcher = Dispatchers.Default,
                mainDispatcher = Dispatchers.Default,
                fetchOnlinePage = { _, page ->
                    if (page.index == 0) {
                        started.incrementAndGet()
                        gate0.await()
                    } else if (page.index == 5) {
                        gate5Started.complete(Unit)
                    }
                    byteArrayOf(page.index.toByte())
                },
                decodeImage = { _, _, _ -> PageImage(100, 150, emptyList()) },
                errorMessage = { it.message ?: "error" },
            )
            val chapterPages = pages(6)
            loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = false)

            // Page 0 holds the single download permit
            loader.loadPage(0)
            zoukan.desktop.testing.Await.until("page 0 started", 5_000) { started.get() == 1 }

            // Boost page 5: page 0 is cancelled and page 5 gets the permit immediately
            loader.boostPage(currentPage = 5)
            gate5Started.await()
            zoukan.desktop.testing.Await.until("page 5 ready", 5_000) {
                chapterPages[5].state.value is ReaderPage.State.Ready
            }
            chapterPages[0].state.value shouldBe ReaderPage.State.Queued
        } finally {
            scope.cancel()
            appScope.cancel()
        }
    }

    @Test
    fun `retryAllPages retries only error pages and redecodePages passes updated cropBorders`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var shouldFail = true
        var crop = false
        val cropValues = CopyOnWriteArrayList<Boolean>()
        val wideIndices = CopyOnWriteArrayList<Int>()
        val chapterPages = pages(3)
        val loader = PageLoader(
            scope = this,
            appScope = this,
            readerThreads = 2,
            preloadSize = { 3 },
            instantRetry = { false },
            cropBorders = { crop },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            fetchOnlinePage = { _, page ->
                if (page.index == 1 && shouldFail) error("bad page")
                byteArrayOf(page.index.toByte())
            },
            decodeImage = { bytes, cropBorders, _ ->
                cropValues += cropBorders
                if (bytes[0].toInt() == 2) PageImage(200, 100, emptyList()) else PageImage(100, 200, emptyList())
            },
            errorMessage = { it.message ?: "error" },
            onWidePage = { index, _ -> wideIndices += index },
        )
        loader.attachChapter(chapterPages, stubSource(), aggressivePrefetch = false)
        loader.requestPages(center = 0)
        advanceUntilIdle()

        chapterPages[0].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        chapterPages[1].state.value.shouldBeInstanceOf<ReaderPage.State.Error>()
        chapterPages[2].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        wideIndices.shouldContainExactly(2)

        shouldFail = false
        loader.retryAllPages(currentPage = 1) shouldBe 1
        advanceUntilIdle()
        chapterPages[1].state.value.shouldBeInstanceOf<ReaderPage.State.Ready>()
        loader.retryAllPages(currentPage = 1) shouldBe 0

        crop = true
        cropValues.clear()
        loader.redecodePages(currentPage = 0)
        advanceUntilIdle()
        cropValues.all { it } shouldBe true
        cropValues.size shouldBe 3
    }

    @Test
    fun `readPageBytes uses cached bytes or LocalFormat and resetForNewChapter closes format`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val format = mockk<LocalFormat>(relaxed = true) {
            every { page(1) } returns byteArrayOf(42)
        }
        val chapterPages = pages(2).also { it[0].bytes = byteArrayOf(7) }
        val loader = PageLoader(
            scope = this,
            appScope = this,
            readerThreads = 1,
            preloadSize = { 1 },
            instantRetry = { false },
            cropBorders = { false },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            fetchOnlinePage = { _, _ -> error("unexpected network fetch") },
            decodeImage = { _, _, _ -> PageImage(100, 150, emptyList()) },
        )
        loader.setChapterFormat(format)
        loader.attachChapter(chapterPages, source = null, aggressivePrefetch = false)

        loader.readPageBytes(0) shouldBe byteArrayOf(7)
        loader.readPageBytes(1) shouldBe byteArrayOf(42)

        loader.resetForNewChapter(chapterPages)
        advanceUntilIdle()
        verify(exactly = 1) { format.close() }
    }
}
