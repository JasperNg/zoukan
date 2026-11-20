package zoukan.desktop.ui.reader

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.util.DataSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.compat.localized
import zoukan.desktop.image.PageSizeLimits
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.ui.common.sourceErrorText
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.the_page_is_gone
import zoukan.i18n.desktop.the_source_of_this_entry_is
import kotlin.math.abs

/** One page of the open chapter, with its own loading state. */
class ReaderPage(val index: Int, val page: Page) {
    sealed interface State {
        data object Queued : State
        data object Loading : State
        data class Ready(val image: PageImage) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Queued)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Only the reader's loader sets a page's state. */
    internal fun setState(value: State) {
        _state.value = value
    }

    @Volatile
    var bytes: ByteArray? = null

    // ZOUKAN -->
    /** The load that owns [state]; a load that was replaced or cancelled leaves the state alone */
    @Volatile
    internal var loader: Job? = null

    /** The size the page decoded to, kept after its bitmap is released so its placeholder keeps the height */
    @Volatile
    var decodedWidth = 0
        private set

    @Volatile
    var decodedHeight = 0
        private set

    /** Sets the page back to Queued when it is Ready; its image frees its pixels once nothing draws it */
    fun release() {
        val current = state.value
        if (current is State.Ready && _state.compareAndSet(current, State.Queued)) current.image.release()
    }

    fun rememberSize(width: Int, height: Int) {
        decodedHeight = height
        decodedWidth = width
    }

    /** Width over height for a placeholder: the decoded shape, or [DEFAULT_ASPECT] for a page never decoded */
    val placeholderAspect: Float
        get() {
            val width = decodedWidth
            val height = decodedHeight
            return if (width > 0 && height > 0) width.toFloat() / height else DEFAULT_ASPECT
        }

    companion object {
        const val DEFAULT_ASPECT = 0.7f
    }
    // ZOUKAN <--
}

/**
 * Coordinates page downloading, bounded decoding, preload windows, memory release, boost/retry,
 * and on-demand page byte retrieval for a chapter.
 */
internal class PageLoader(
    private val scope: CoroutineScope,
    private val appScope: CoroutineScope,
    readerThreads: Int,
    private val preloadSize: () -> Int,
    private val instantRetry: () -> Boolean,
    private val cropBorders: () -> Boolean,
    decodeThreads: Int = DEFAULT_DECODE_THREADS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val fetchOnlinePage: suspend (HttpSource, Page) -> ByteArray,
    private val decodeImage: (ByteArray, Boolean, Int?) -> PageImage = PageImage::decode,
    private val errorMessage: (Throwable) -> String = ::sourceErrorText,
    private val onWidePage: (Int, ReaderPage) -> Unit = { _, _ -> },
) {
    // Android's "Reader threads": how many pages download at once
    private val downloads = Semaphore(readerThreads.coerceIn(1, 8))

    // Full-size decodes at once: each holds a whole page in memory, and preloading asks for many
    private val decodes = Semaphore(decodeThreads.coerceAtLeast(1))

    /** Only read and changed on the main thread */
    private val pageJobs = mutableMapOf<Int, Job>()

    private var pages: List<ReaderPage> = emptyList()

    @Volatile
    private var source: Source? = null

    /** The open chapter's files when it is read from disk */
    @Volatile
    private var format: LocalFormat? = null

    @Volatile
    private var keepAllBytes = false

    /** The pages the viewer shows, kept decoded: a zoomed-out webtoon shows pages far from the middle one */
    @Volatile
    private var visiblePages: IntRange = IntRange.EMPTY

    /** The viewer's width in pixels, read when a page decodes: no page is decoded wider than it can be zoomed */
    @Volatile
    private var viewportWidth = 0

    private var prefetchJob: Job? = null

    fun setChapterFormat(format: LocalFormat?) {
        this.format = format
    }

    fun attachChapter(pages: List<ReaderPage>, source: Source?, aggressivePrefetch: Boolean) {
        this.pages = pages
        this.source = source
        if (aggressivePrefetch && format == null) {
            prefetchAll(pages)
        }
    }

    fun resetForNewChapter(oldPages: List<ReaderPage>) {
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        prefetchJob?.cancel()
        prefetchJob = null
        closeFormat()
        keepAllBytes = false
        visiblePages = IntRange.EMPTY
        pages = emptyList()
        source = null
        // The old chapter's decoded pages free their pixels once the viewers stop drawing them
        oldPages.forEach { it.release() }
    }

    fun detachFormat(): LocalFormat? {
        val old = format ?: return null
        format = null
        return old
    }

    fun closeFormat() {
        val old = detachFormat() ?: return
        appScope.launch(ioDispatcher) { runCatching { old.close() } }
    }

    fun onViewport(widthPx: Int) {
        viewportWidth = widthPx
    }

    fun onVisiblePages(visible: IntRange) {
        visiblePages = visible
    }

    /** Loads the pages around [center] and releases decoded images far from it. */
    fun requestPages(center: Int) {
        val currentPages = pages
        val ahead = preloadSize().coerceIn(1, 50)
        val keepAhead = maxOf(KEEP_AHEAD, ahead + 2)
        val kept = PageWindow.kept(
            currentPages.size,
            center,
            KEEP_BEHIND,
            keepAhead,
            visiblePages,
            pixels = { currentPages[it].decodedWidth.toLong() * currentPages[it].decodedHeight },
        )
        // A page of known size that does not fit the budget is not decoded only to be released again
        val wanted = (center - 2..center + ahead)
            .filter { it in currentPages.indices && (it in kept || currentPages[it].decodedWidth == 0) }
            .sortedBy { abs(it - center) + if (it < center) 1 else 0 }
        wanted.forEach(::loadPage)
        currentPages.forEach { page ->
            if (page.index !in kept) {
                page.release()
            }
            // The downloaded bytes are kept longer, so going back a few pages needs no network;
            // aggressive page loading keeps every page's bytes.
            if (page.index !in center - 3 * KEEP_BEHIND..center + 3 * keepAhead && !keepAllBytes) {
                page.bytes = null
            }
        }
    }

    fun loadPage(index: Int, retry: Boolean = false, attempt: Int = 0) {
        val page = pages.getOrNull(index) ?: return
        val current = page.state.value
        if (current is ReaderPage.State.Ready || current is ReaderPage.State.Loading) return
        if (current is ReaderPage.State.Error && !retry) return
        if (pageJobs[index]?.isActive == true) return
        val localFormat = format
        val httpSource = source as? HttpSource
        if (localFormat == null && httpSource == null) return
        page.setState(ReaderPage.State.Loading)
        // Started once it owns the page, so even a load that ends at once finds itself the owner
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            fun owns() = page.loader === self
            try {
                val bytes = page.bytes
                    ?: (localFormat?.page(index) ?: downloads.withPermit { fetchOnlinePage(httpSource!!, page.page) })
                        .also { page.bytes = it }
                val widthPx = viewportWidth
                val maxWidth = if (widthPx > 0) (widthPx * PageZoom.MAX_ZOOM).toInt() else null
                // Interruptible: a load cancelled while it waits at PageImage's decode gate leaves
                // the queue instead of decoding a page nobody waits for
                val image = decodes.withPermit { runInterruptible { decodeImage(bytes, cropBorders(), maxWidth) } }
                if (!isActive || !owns()) {
                    image.release()
                    ensureActive()
                }
                if (image.width > image.height) {
                    onWidePage(index, page)
                }
                if (owns()) {
                    page.rememberSize(image.width, image.height)
                    page.setState(ReaderPage.State.Ready(image))
                } else {
                    image.release()
                }
            } catch (e: CancellationException) {
                // A newer load (Boost page) owns the page now, and its state is not this load's to reset
                if (owns()) page.setState(ReaderPage.State.Queued)
                throw e
            } catch (e: Throwable) {
                // A cancelled download can fail with an I/O error ("Canceled"): that is no page error.
                // The page is an error before a fatal error goes on: a page left loading is never
                // loaded again, and "Retry" passes over it
                val ours = isActive && owns()
                if (ours) {
                    page.bytes = null
                    page.setState(ReaderPage.State.Error(errorMessage(e)))
                }
                e.rethrowIfVmFailure()
                if (!ours) return@launch
                logcat(LogPriority.WARN, e) { "Page ${index + 1} failed" }
                // SY's "Instant retry": a failed page is tried once more at once
                if (attempt == 0 && instantRetry()) {
                    // pageJobs is only touched on the main thread
                    withContext(mainDispatcher) {
                        if (pageJobs[index] === self) pageJobs.remove(index)
                        loadPage(index, retry = true, attempt = 1)
                    }
                }
            }
        }
        page.loader = job
        pageJobs[index] = job
        job.start()
    }

    /** SY's "Aggressive page loading": downloads every page of an online chapter in the background. */
    fun prefetchAll(targetPages: List<ReaderPage> = pages) {
        val httpSource = source as? HttpSource ?: return
        keepAllBytes = true
        val job = scope.launch(ioDispatcher) {
            targetPages.forEach { page ->
                if (page.bytes != null) return@forEach
                try {
                    downloads.withPermit { if (page.bytes == null) page.bytes = fetchOnlinePage(httpSource, page.page) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e.rethrowIfVmFailure()
                    // The page loads again when it is shown
                    logcat(LogPriority.WARN, e) { "Prefetching page ${page.index + 1} failed" }
                }
            }
        }
        prefetchJob?.cancel()
        prefetchJob = job
    }

    /** SY's "Retry all": loads every failed page again and returns how many were queued. */
    fun retryAllPages(currentPage: Int): Int {
        val failed = pages.filter { it.state.value is ReaderPage.State.Error }
        if (failed.isEmpty()) return 0
        failed.sortedBy { abs(it.index - currentPage) }.forEach { loadPage(it.index, retry = true) }
        return failed.size
    }

    /** SY's "Boost page": the shown page jumps the queue; other pages in flight start again after it. */
    fun boostPage(currentPage: Int) {
        val currentPages = pages
        val page = currentPages.getOrNull(currentPage) ?: return
        if (page.state.value is ReaderPage.State.Ready) return
        // The cancelled loads give up their pages here, so they are queued again below; their own
        // cancel handlers then find a newer owner and leave the state alone
        pageJobs.toList().forEach { (i, job) ->
            job.cancel()
            pageJobs.remove(i)
            val other = currentPages.getOrNull(i) ?: return@forEach
            if (other.loader === job) {
                other.loader = null
                if (other.state.value is ReaderPage.State.Loading) other.setState(ReaderPage.State.Queued)
            }
        }
        if (page.state.value !is ReaderPage.State.Ready) page.setState(ReaderPage.State.Queued)
        loadPage(currentPage, retry = true)
        requestPages(currentPage)
    }

    /** Decodes the shown pages again, after a setting that changes decoding (crop borders). */
    fun redecodePages(currentPage: Int) {
        pages.forEach { it.release() }
        requestPages(currentPage)
    }

    /** The page's image as the source or file has it, read again when it was let go. */
    suspend fun readPageBytes(index: Int): ByteArray {
        val page = pages.getOrNull(index) ?: error(localized(DMR.strings.the_page_is_gone))
        page.bytes?.let { return it }
        format?.let { return it.page(index) }
        val httpSource = source as? HttpSource ?: error(localized(DMR.strings.the_source_of_this_entry_is))
        return downloads.withPermit { fetchOnlinePage(httpSource, page.page) }
    }

    companion object {
        const val KEEP_BEHIND = 4
        const val KEEP_AHEAD = 10
        private val DEFAULT_DECODE_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        suspend fun fetchHttpPage(
            source: HttpSource,
            page: Page,
            sourcePreferences: Lazy<SourcePreferences>,
        ): ByteArray {
            if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
            // The SY data saver, as Android's HttpPageLoader
            val dataSaver = runCatching { sourcePreferences.value }.getOrNull()
                ?.let { DataSaver(source, it) } ?: DataSaver.NoOp
            return with(DataSaver) { source.getImage(page, dataSaver) }.use { response ->
                if (!response.isSuccessful) throw HttpException(response.code)
                PageSizeLimits.readPageBody(response.body)
            }
        }
    }
}

/**
 * Whether page [index] keeps its decoded image with [center] the current page: the pages near it,
 * and every page in [visible], which a zoomed-out webtoon shows far from the middle one.
 */
internal fun keepsImage(index: Int, center: Int, behind: Int, ahead: Int, visible: IntRange): Boolean =
    index in center - behind..center + ahead || index in visible

internal object PageWindow {
    const val BUDGET_PIXELS = 64_000_000L

    /**
     * The pages that keep their decoded image with [center] current: the window of [keepsImage],
     * nearest first (ahead before behind) while the decoded [pixels] stay within [BUDGET_PIXELS].
     * The current page and the [visible] ones are always kept, whatever their size: releasing a page
     * on screen would only make it decode again. A page not decoded yet has no pixels.
     */
    fun kept(count: Int, center: Int, behind: Int, ahead: Int, visible: IntRange, pixels: (Int) -> Long): Set<Int> {
        val window = (0 until count).filter { keepsImage(it, center, behind, ahead, visible) }
        if (center !in 0 until count) return window.toSet()
        val kept = linkedSetOf(center)
        window.filterTo(kept) { it in visible }
        var total = kept.sumOf(pixels)
        val others = window.filter { it !in kept }.sortedBy { abs(it - center) + if (it < center) 1 else 0 }
        for (index in others) {
            total += pixels(index)
            if (total > BUDGET_PIXELS) break
            kept += index
        }
        return kept
    }
}
