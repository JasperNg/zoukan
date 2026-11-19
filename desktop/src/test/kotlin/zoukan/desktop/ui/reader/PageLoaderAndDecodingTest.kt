package zoukan.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.window.WindowState
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.image.PageSizeLimits.readPageBody
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.LocalWindowState
import zoukan.desktop.ui.common.UnreadableImageException
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

/**
 * Tests for [PageLoader], corrupt page recovery, [DecodeGate] concurrency bounding, declared size
 * limits, [PageWindow] memory budgeting, border cropping, and [PageImages] saving/combining.
 */
class PageLoaderAndDecodingTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Nested
    inner class Loader {

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
                    Await.until("page ${page.index} ready", 5_000) {
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
                Await.until("page 0 started", 5_000) { started.get() == 1 }

                // Boost page 5: page 0 is cancelled and page 5 gets the permit immediately
                loader.boostPage(currentPage = 5)
                gate5Started.await()
                Await.until("page 5 ready", 5_000) {
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

    /**
     * Corrupt page bytes render [PageError] with a localized title and Retry button while other pages
     * still draw, clicking Retry after the source recovers renders the page, and HTTP 403 renders the
     * localized HTTP reason instead of a raw status line.
     */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Nested
    inner class CorruptPages {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
        }

        @AfterEach
        fun tearDown() {
            Dispatchers.resetMain()
            main.close()
        }

        private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        private fun corruptJpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

        private fun registerDeps(store: PreferenceStore, prefs: DesktopPreferences, manga: Manga, chapters: List<Chapter>, source: HttpSource) {
            Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
            Injekt.addSingleton(mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns chapters })
            Injekt.addSingleton(mockk<SourceManager> { every { get(any<Long>()) } returns source })
            Injekt.addSingleton(mockk<UpdateChapter>(relaxed = true))
            Injekt.addSingleton(mockk<UpsertHistory>(relaxed = true))
            Injekt.addSingleton(mockk<MangaRepository>(relaxed = true))
            Injekt.addSingleton(
                mockk<DownloadManager>(relaxed = true) {
                    every { findChapter(any(), any()) } returns null
                    every { isDownloaded(any(), any()) } returns false
                },
            )
            Injekt.addSingleton(mockk<CbzPassword>(relaxed = true))
            Injekt.addSingleton(DownloadPreferences(store))
            Injekt.addSingleton(LibraryPreferences(store))
            Injekt.addSingleton(prefs)
            Injekt.addSingleton(mockk<Incognito> { every { isOn(any()) } returns true })
            Injekt.addSingleton(mockk<eu.kanade.tachiyomi.data.sync.SyncManager>(relaxed = true))
            Injekt.addSingleton(mockk<TrackChapters>(relaxed = true))
        }

        @Test
        fun `corrupt page shows error and Retry recovers when valid image returns`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val prefs = DesktopPreferences(store).apply {
                    pageLayout().set(0)
                    defaultReadingMode().set(ReadingModes.LEFT_TO_RIGHT)
                    showNavigationOverlayNewUser().set(false)
                    readerInstantRetry().set(false)
                }
                val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
                val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
                val validPng = pngBytes()
                val failPage2 = AtomicBoolean(true)
                val pages = List(2) { Page(it, imageUrl = "https://example.invalid/${it + 1}.png") }
                val source = mockk<HttpSource>(relaxed = true) {
                    coEvery { getPageList(any()) } returns pages
                    coEvery { getImage(any()) } answers {
                        val page = firstArg<Page>()
                        val bodyBytes = if (page.index == 1 && failPage2.get()) corruptJpegBytes() else validPng
                        Response.Builder()
                            .request(Request.Builder().url(page.imageUrl!!).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(bodyBytes.toResponseBody("image/png".toMediaType()))
                            .build()
                    }
                }
                registerDeps(store, prefs, manga, listOf(chapter), source)

                setContent {
                    CompositionLocalProvider(LocalWindowState provides WindowState()) {
                        Navigator(ReaderScreen(manga.id, chapter.id)) { it.lastItem.Content() }
                    }
                }

                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }

                onRoot().performKeyInput { pressKey(Key.DirectionRight) }
                val errorTitle = localized(DMR.strings.page_could_not_load, 2)
                val retryLabel = localized(MR.strings.action_retry)
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasText(retryLabel)).fetchSemanticsNodes().isNotEmpty()
                }

                failPage2.set(false)
                onAllNodesWithText(retryLabel).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty()
                }
            }

        @Test
        fun `HTTP 403 page renders localized exception reason instead of raw status`() =
            runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val prefs = DesktopPreferences(store).apply {
                    pageLayout().set(0)
                    showNavigationOverlayNewUser().set(false)
                    readerInstantRetry().set(false)
                }
                val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
                val chapter = Chapter.create().copy(id = 20, mangaId = 1, name = "Chapter 2", url = "/c/2")
                val source = mockk<HttpSource>(relaxed = true) {
                    coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.invalid/403.png"))
                    coEvery { getImage(any()) } answers {
                        val page = firstArg<Page>()
                        Response.Builder()
                            .request(Request.Builder().url(page.imageUrl!!).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
                registerDeps(store, prefs, manga, listOf(chapter), source)

                setContent {
                    CompositionLocalProvider(LocalWindowState provides WindowState()) {
                        Navigator(ReaderScreen(manga.id, chapter.id)) { it.lastItem.Content() }
                    }
                }

                val errorTitle = localized(DMR.strings.page_could_not_load, 1)
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(errorTitle)).fetchSemanticsNodes().isNotEmpty() }
                onAllNodes(hasText(localized(MR.strings.exception_http, 403))).fetchSemanticsNodes().isNotEmpty() shouldBe true
                onAllNodes(hasText("HTTP 403")).fetchSemanticsNodes().isEmpty() shouldBe true
            }
    }

    /**
     * Skia's decodes are bounded by the pixels in flight, not only per page: ordinary pages keep
     * decoding side by side, a large page decodes alone, so a few large pages at once cannot take
     * several GiB of native memory.
     */
    @Nested
    inner class DecodeConcurrencyGate {

        @Test
        fun `ordinary pages decode side by side`() {
            val gate = DecodeGate(capacity = 100)
            repeat(4) { gate.tryAcquire(gate.weightOf(20)) shouldBe true }
            gate.inUse shouldBe 80
        }

        @Test
        fun `a page that does not fit beside the others waits`() {
            val gate = DecodeGate(capacity = 100)
            gate.tryAcquire(gate.weightOf(60)) shouldBe true
            gate.tryAcquire(gate.weightOf(60)) shouldBe false
            gate.release(gate.weightOf(60))
            gate.tryAcquire(gate.weightOf(60)) shouldBe true
        }

        @Test
        fun `the largest page still decodes, alone`() {
            val gate = DecodeGate(capacity = 100)
            // Larger than the gate (or of unknown size): it takes the whole gate, never more
            gate.weightOf(10_000) shouldBe 100
            gate.weightOf(null) shouldBe 100
            gate.weightOf(0) shouldBe 1
            gate.tryAcquire(gate.weightOf(10_000)) shouldBe true
            gate.tryAcquire(gate.weightOf(1)) shouldBe false
        }

        @Test
        fun `the gate's numbers for real pages`() {
            val gate = DecodeGate(capacity = PageImage.MAX_DECODED_PIXELS)
            // Three of the largest strips seen (about 77 million pixels) at once, not four
            repeat(3) { gate.tryAcquire(gate.weightOf(1080L * 71_500)) shouldBe true }
            gate.tryAcquire(gate.weightOf(1080L * 71_500)) shouldBe false
        }

        @Test
        fun `a waiting large page is not overtaken by smaller ones`() {
            val gate = DecodeGate(capacity = 100)
            gate.acquire(gate.weightOf(50))
            val largeIn = AtomicBoolean(false)
            val large = Thread {
                gate.acquire(gate.weightOf(100))
                largeIn.set(true)
                gate.release(gate.weightOf(100))
            }.apply { start() }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (large.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
            large.state shouldBe Thread.State.WAITING
            // A small page would fit beside the first, but the large one asked first
            gate.tryAcquire(gate.weightOf(10)) shouldBe false
            largeIn.get() shouldBe false

            gate.release(gate.weightOf(50))
            large.join()
            largeIn.get() shouldBe true
            gate.inUse shouldBe 0
            gate.tryAcquire(gate.weightOf(10)) shouldBe true
        }

        @Test
        fun `a decode that fails gives its share back`() {
            val gate = DecodeGate(capacity = 100)
            runCatching { gate.withPermit(100) { throw IllegalStateException("bad page") } }
            gate.inUse shouldBe 0
            val done = CountDownLatch(1)
            gate.withPermit(100) { done.countDown() }
            done.count shouldBe 0
        }

        @Test
        fun `a decode whose thread is interrupted by the time it gets its turn is skipped`() {
            val gate = DecodeGate(capacity = 100)
            val decoded = AtomicBoolean(false)
            Thread.currentThread().interrupt()
            val skipped = runCatching { gate.withPermit(10) { decoded.set(true) } }.exceptionOrNull()
            Thread.interrupted()

            (skipped is InterruptedException) shouldBe true
            decoded.get() shouldBe false
            gate.inUse shouldBe 0
        }

        @Test
        fun `a load cancelled while it waits at the gate leaves the queue and never decodes`() = runBlocking<Unit> {
            val gate = DecodeGate(capacity = 100)
            gate.acquire(100)
            val decoded = AtomicBoolean(false)
            val waiting = AtomicReference<Thread>()
            // As the reader loads a page: the blocking decode runs interruptibly, so a cancel reaches it
            val load = launch(Dispatchers.IO) {
                runInterruptible {
                    waiting.set(Thread.currentThread())
                    gate.withPermit(10) { decoded.set(true) }
                }
            }
            Await.until("the load to wait at the gate") { waiting.get()?.state == Thread.State.WAITING }

            load.cancel()
            load.join()
            gate.release(100)

            decoded.get() shouldBe false
            gate.inUse shouldBe 0
            // Its place in the queue is given up: the next decode goes ahead at once
            gate.tryAcquire(10) shouldBe true
        }
    }

    /**
     * A page from a source or a file that declares more pixels or bytes than the reader can hold is a
     * page error, found before anything that size is allocated: running out of memory on one page
     * would end the whole session.
     */
    @Nested
    inner class SizeLimits {

        /** A grey 1-bit PNG that declares [width] by [height] pixels and holds one row of data. */
        private fun png(width: Int, height: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            fun chunk(type: String, body: ByteArray) {
                data.writeInt(body.size)
                val typed = type.toByteArray() + body
                data.write(typed)
                data.writeInt(CRC32().apply { update(typed) }.value.toInt())
            }
            data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply {
                    writeInt(width)
                    writeInt(height)
                    write(byteArrayOf(1, 0, 0, 0, 0))
                }
            }.toByteArray()
            chunk("IHDR", ihdr)
            val row = ByteArray(1 + (width + 7) / 8)
            val deflater = Deflater().apply {
                setInput(row)
                finish()
            }
            val compressed = ByteArray(row.size + 64).let { it.copyOf(deflater.deflate(it)) }
            chunk("IDAT", compressed)
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }

        @Test
        fun `a page that declares more pixels than the reader can hold is a page error`() {
            // More pixels than Skia's bound, square and as a strip (no side limit applies on Skia's path)
            listOf(20_000 to 20_000, 1_000 to 300_000, 100_000 to 100_000).forEach { (w, h) ->
                val error = shouldThrow<UnreadableImageException> { PageImage.decode(png(w, h)) }
                error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
            }
        }

        @Test
        fun `the declared size is read without decoding the page`() {
            PageImage.declaredSize(png(30_000, 40_000)) shouldBe (30_000 to 40_000)
            PageImage.declaredSize("not an image".toByteArray()) shouldBe null
        }

        @Test
        fun `a page within the limit is not refused by its size`() {
            PageImage.checkDeclaredSize(png(8_000, 8_000))
        }

        /** A real 16 by 16 JPEG whose frame header is rewritten to declare [width] by [height]. */
        private fun jpeg(width: Int, height: Int): ByteArray {
            val out = ByteArrayOutputStream()
            ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", out)
            val bytes = out.toByteArray()
            val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
            bytes[sof + 5] = (height shr 8).toByte()
            bytes[sof + 6] = height.toByte()
            bytes[sof + 7] = (width shr 8).toByte()
            bytes[sof + 8] = width.toByte()
            return bytes
        }

        // 65,500 is the tallest JPEG libjpeg (Skia's and ImageIO's decoder) reads; a taller one never showed
        @Test
        fun `a webtoon strip near JPEG's height limit at an ordinary width is not refused`() {
            listOf(1080 to 65_500, 1280 to 60_000).forEach { (w, h) ->
                val page = jpeg(w, h)
                PageImage.declaredSize(page) shouldBe (w to h)
                PageImage.checkDeclaredSize(page)
            }
        }

        // Skia has no side limit of its own for PNG: a tall strip within the pixel bound showed before
        @Test
        fun `a PNG strip taller than 65,535 pixels within the pixel bound is not refused`() {
            val page = png(720, 88_000)
            PageImage.declaredSize(page) shouldBe (720 to 88_000)
            PageImage.checkDeclaredSize(page)
        }

        @Test
        fun `a page as large as WebP allows is not refused on Skia's path`() {
            PageImage.isTooLarge(16_383, 16_383, PageImage.MAX_DECODED_PIXELS) shouldBe false
            PageImage.isTooLarge(16_384, 16_384, PageImage.MAX_DECODED_PIXELS) shouldBe false
        }

        @Test
        fun `an absurd declared size is a page error`() {
            val error = shouldThrow<UnreadableImageException> { PageImage.decode(jpeg(60_000, 60_000)) }
            error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
        }

        @Test
        fun `ImageIO's path is bounded by the heap it decodes on`() {
            val twoGb = 2L * 1024 * 1024 * 1024
            // A strip fits in the heap the app runs with; the same strip does not fit in a small one
            PageImage.isTooLarge(1080, 65_535, PageImage.imageIoPixelLimit(twoGb)) shouldBe false
            PageImage.isTooLarge(1080, 65_535, PageImage.imageIoPixelLimit(256L * 1024 * 1024)) shouldBe true
            // Never above Skia's bound, however large the heap
            PageImage.imageIoPixelLimit(Long.MAX_VALUE) shouldBe PageImage.MAX_DECODED_PIXELS
        }

        @Test
        fun `the page bytes held during an ImageIO read count against its heap budget`() {
            val twoGb = 2L * 1024 * 1024 * 1024
            val strip = 1080L * 65_535
            // A 100 MB page held twice (its bytes and the read's copy) leaves less room for pixels
            PageImage.imageIoPixelLimit(twoGb, heldBytes = 0) shouldBe twoGb / 2 / 8
            PageImage.imageIoPixelLimit(twoGb, heldBytes = 200L * 1024 * 1024) shouldBe (twoGb / 2 - 200L * 1024 * 1024) / 8
            (strip <= PageImage.imageIoPixelLimit(twoGb, heldBytes = 2L * 100 * 1024 * 1024)) shouldBe true
            // Bytes alone past the budget leave no room at all
            PageImage.imageIoPixelLimit(256L * 1024 * 1024, heldBytes = 512L * 1024 * 1024) shouldBe 0L
        }

        @Test
        fun `ImageIO's path decodes one page at a time`() {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val secondEntered = AtomicBoolean(false)
            val first = Thread {
                PageImage.oneImageIoDecodeAtATime {
                    inside.countDown()
                    release.await()
                }
            }.apply { start() }
            inside.await()
            val second = Thread { PageImage.oneImageIoDecodeAtATime { secondEntered.set(true) } }.apply { start() }
            // The second decode waits for the first: it parks on the gate
            val deadline = System.nanoTime() + 10_000_000_000L
            while (second.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
            second.state shouldBe Thread.State.WAITING
            secondEntered.get() shouldBe false
            release.countDown()
            first.join()
            second.join()
            secondEntered.get() shouldBe true
        }

        @Test
        fun `a page response longer than the limit fails before it is read whole`() {
            val declared = Buffer().write(ByteArray(64)).asResponseBody(contentLength = 1L shl 40)
            shouldThrow<IOException> { readPageBody(declared, limit = 1024) }
            // A response that says no length is cut off at the limit
            val unknown = Buffer().write(ByteArray(4096)).asResponseBody(contentLength = -1)
            shouldThrow<IOException> { readPageBody(unknown, limit = 1024) }
            readPageBody(ByteArray(1024).toResponseBody("image/png".toMediaType()), limit = 1024).size shouldBe 1024
        }

        private fun BufferedSource.asResponseBody(contentLength: Long): ResponseBody {
            val source = this
            return object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = contentLength
                override fun source() = source
            }
        }
    }

    @Nested
    inner class MemoryBudget {

        @Test
        fun `the decoded pages kept stay under the pixel budget across a chapter`() {
            val pixels = { _: Int -> 9_600_000L }
            for (center in 0 until 30) {
                val kept = PageWindow.kept(30, center, behind = 4, ahead = 12, visible = center..center, pixels = pixels)
                kept.sumOf { pixels(it) } shouldBeLessThanOrEqual PageWindow.BUDGET_PIXELS
                kept shouldContain center
            }
        }

        @Test
        fun `pages on screen are kept even over the budget, and nothing else is`() {
            // Zoomed out on large pages: four pages on screen already pass the budget
            val pixels = { _: Int -> 24_000_000L }
            val visible = 10..13
            val kept = PageWindow.kept(30, center = 10, behind = 4, ahead = 12, visible = visible, pixels = pixels)
            kept shouldBe visible.toSet()
        }

        @Test
        fun `a page far wider than the window is decoded smaller`() {
            val source = BufferedImage(4000, 2000, BufferedImage.TYPE_INT_RGB)
            val out = ByteArrayOutputStream()
            ImageIO.write(source, "png", out)
            val image = PageImage.decode(out.toByteArray(), maxWidth = 1000)
            image.width shouldBe 1000
            image.height shouldBe 500
        }
    }

    @Nested
    inner class BorderCropping {

        private fun page(width: Int, height: Int, background: Int, content: Rect?): Bitmap {
            val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(width, height)) }
            Canvas(bitmap).apply {
                clear(background)
                if (content != null) drawRect(content, Paint().apply { color = Color.makeRGB(200, 30, 30) })
            }
            return bitmap
        }

        @Test
        fun `white margins are cut down to the content plus a small pad`() {
            val rect = PageImage.contentRect(page(400, 600, Color.WHITE, Rect.makeLTRB(100f, 150f, 300f, 450f))).shouldNotBeNull()
            // The pad is max(2, 400 / 200) = 2 pixels
            rect.left shouldBe 98
            rect.top shouldBe 148
            rect.right shouldBe 302
            rect.bottom shouldBe 452
        }

        @Test
        fun `black margins are cut too`() {
            val rect = PageImage.contentRect(page(400, 600, Color.BLACK, Rect.makeLTRB(0f, 50f, 400f, 550f))).shouldNotBeNull()
            rect.left shouldBe 0
            rect.top shouldBe 48
            rect.right shouldBe 400
            rect.bottom shouldBe 552
        }

        @Test
        fun `a page that is all margin or has a colored corner is left alone`() {
            PageImage.contentRect(page(400, 600, Color.WHITE, null)).shouldBeNull()
            PageImage.contentRect(page(400, 600, Color.makeRGB(120, 160, 90), Rect.makeLTRB(100f, 100f, 200f, 200f))).shouldBeNull()
        }

        @Test
        fun `continuous vertical crops by its own setting, as Android's crop_borders_continues_vertical`() {
            // The key and default Android backs up, so a backup carries the setting either way
            DesktopPreferences(InMemoryPreferenceStore()).cropBordersContinuousVertical().let {
                it.key() shouldBe "crop_borders_continues_vertical"
                it.defaultValue() shouldBe false
            }
            // Only continuous vertical's crop borders is on, as a restored Android backup can have it
            val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreferenceStore.InMemoryPreference("crop_borders_continues_vertical", true, false)))
            val prefs = DesktopPreferences(store)
            val png = Image.makeFromBitmap(page(400, 600, Color.WHITE, Rect.makeLTRB(100f, 150f, 300f, 450f)))
                .encodeToData(EncodedImageFormat.PNG).shouldNotBeNull().bytes
            val widths = ReadingModes.all.associateWith { mode ->
                PageImage.decode(png, cropBorders = prefs.cropBordersFor(mode).get()).width
            }
            // Only continuous vertical is cropped (to the content plus a 2 pixel pad on each side)
            widths shouldBe ReadingModes.all.associateWith { if (it == ReadingModes.CONTINUOUS_VERTICAL) 204 else 400 }
            // Webtoon keeps its own setting, and paged modes theirs
            prefs.cropBordersFor(ReadingModes.WEBTOON).key() shouldBe "crop_borders_webtoon"
            prefs.cropBordersFor(ReadingModes.RIGHT_TO_LEFT).key() shouldBe "crop_borders"
        }
    }

    @Nested
    inner class ImageFilesAndCombining {

        private fun png(width: Int, height: Int, color: Int): ByteArray {
            val surface = Surface.makeRasterN32Premul(width, height)
            surface.canvas.clear(color)
            return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

        @Test
        fun `the extension comes from the image bytes`() {
            PageImages.extension(png(4, 4, Color.RED)) shouldBe "png"
            PageImages.extension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)) shouldBe "jpg"
            PageImages.extension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()) shouldBe "webp"
            PageImages.extension("<html>".toByteArray()) shouldBe null
        }

        @Test
        fun `file names lose the characters Windows refuses`() {
            PageImages.validFilename("What? A: \"title\" / part 1.") shouldBe "What_ A_ _title_ _ part 1"
            PageImages.validFilename("é".repeat(200), 21).toByteArray().size shouldBe 20
        }

        @Test
        fun `a combined page puts the pages side by side, centered on the taller`() {
            val red = png(30, 40, Color.RED)
            val blue = png(20, 20, Color.BLUE)
            Image.makeFromEncoded(PageImages.combine(red, blue, leftFirst = true, background = Color.WHITE)).use { image ->
                image.width shouldBe 50
                image.height shouldBe 40
            }
            val bitmap = Bitmap.makeFromImage(
                Image.makeFromEncoded(PageImages.combine(red, blue, leftFirst = false, background = Color.WHITE)),
            )
            // Right to left: the first page sits on the right, the second on the left with white above it
            (Color.getR(bitmap.getColor(45, 20)) > 200 && Color.getB(bitmap.getColor(45, 20)) < 60) shouldBe true
            (Color.getB(bitmap.getColor(10, 20)) > 200) shouldBe true
            (Color.getG(bitmap.getColor(10, 2)) > 200) shouldBe true
        }

        @Test
        fun `saving keeps the image as it is, with its extension`() {
            val dir = Files.createTempDirectory("zoukan-pictures").toFile()
            try {
                val bytes = png(8, 8, Color.GREEN)
                val file = PageImages.save(bytes, dir, "Title - Chapter 1 - 3")
                file.name shouldEndWith "Title - Chapter 1 - 3.png"
                file.readBytes().contentEquals(bytes) shouldBe true
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
