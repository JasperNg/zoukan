package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.toArgb
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.toSChapter
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadAhead
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.ReadDeletionPolicy
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.local.LocalSource
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.common.sourceErrorText
import zoukan.i18n.desktop.DMR
import java.util.Date

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

    /** Only the reader's screen model sets a page's state. */
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

class ReaderScreenModel(
    private val mangaId: Long,
    private val initialChapterId: Long,
    // ZOUKAN -->
    /** A page to open the first chapter at (a page preview), instead of where reading stopped */
    private var initialPage: Int? = null,
    // ZOUKAN <--
    private val getManga: GetManga = Injekt.get(),
    private val getChapters: GetChaptersByMangaId = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val cbzPassword: CbzPassword = Injekt.get(),
    private val downloadPreferences: tachiyomi.domain.download.service.DownloadPreferences = Injekt.get(),
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences = Injekt.get(),
    val preferences: DesktopPreferences = Injekt.get(),
    // Resolved on first use, as before, so a missing registration only fails where it is used
    private val getMergedChapters: Lazy<GetMergedChaptersByMangaId> = injectLazy(),
    private val incognitoPolicy: Lazy<zoukan.desktop.security.Incognito> = injectLazy(),
    private val syncManager: Lazy<eu.kanade.tachiyomi.data.sync.SyncManager> = injectLazy(),
    private val discordPresence: Lazy<zoukan.desktop.discord.DiscordPresence> = injectLazy(),
    private val trackChapters: Lazy<TrackChapters> = injectLazy(),
    private val sourcePreferences: Lazy<eu.kanade.domain.source.service.SourcePreferences> = injectLazy(),
    private val editManga: Lazy<zoukan.desktop.manga.EditManga> = injectLazy(),
    private val getChapter: Lazy<tachiyomi.domain.chapter.interactor.GetChapter> = injectLazy(),
    private val downloadAheadRule: DownloadAhead = DownloadAhead(downloadManager::isDownloaded),
    /** Scope of the work that outlives the screen: progress, history, deletion after reading */
    private val appScope: CoroutineScope = zoukan.desktop.app.AppScope,
) : StateScreenModel<ReaderScreenModel.State>(State()) {

    data class State(
        val manga: Manga? = null,
        /** In reading order: oldest first */
        val chapters: List<Chapter> = emptyList(),
        val chapter: Chapter? = null,
        val pages: List<ReaderPage> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val currentPage: Int = 0,
        val readingMode: Int = ReadingModes.RIGHT_TO_LEFT,
        /** Bumped on every chapter load, so the viewers reset their scroll position */
        val generation: Int = 0,
        /** The page to land on after a chapter loads */
        val startPage: Int = 0,
        /** The chapter is read from disk: a download or the local source */
        val fromDisk: Boolean = false,
        /** The chapters next and previous go through: [chapters] without the skipped ones */
        val navigable: List<Chapter> = emptyList(),
        /** The first page shown: below [currentPage] when double pages show two */
        val firstShownPage: Int = 0,
        // ZOUKAN -->
        /** Pages decoded so far that are wider than tall, for splitting, turning and double pages */
        val widePages: Set<Int> = emptySet(),
        /** SY's "Shift double pages": pairs from the first page, for this chapter */
        val shiftDoublePages: Boolean = false,
        // ZOUKAN <--
    ) {
        /** "3 / 92", or "2-3 / 92" for two pages side by side, as Android's page number */
        val pageNumber: String
            get() = if (firstShownPage in 0 until currentPage) {
                "${firstShownPage + 1}-${currentPage + 1} / ${pages.size}"
            } else {
                "${currentPage + 1} / ${pages.size}"
            }
        val chapterIndex: Int get() = navigable.indexOfFirst { it.id == chapter?.id }
        val previousChapter: Chapter? get() = chapterIndex.takeIf { it > 0 }?.let { navigable[it - 1] }
        val nextChapter: Chapter? get() = chapterIndex.takeIf { it >= 0 && it < navigable.lastIndex }?.let { navigable[it + 1] }
    }

    private var source: Source? = null

    /** The open chapter's files when it is read from disk */
    @Volatile
    private var format: LocalFormat? = null
    private var loadJob: Job? = null
    // ZOUKAN -->
    // Android's "Reader threads": how many pages download at once
    private val downloads = Semaphore(preferences.readerThreads().get().coerceIn(1, 8))

    // Full-size decodes at once: each holds a whole page in memory, and preloading asks for many
    private val decodes = Semaphore(DECODE_THREADS)

    /** The pages the viewer shows, kept decoded: a zoomed-out webtoon shows pages far from the middle one */
    private var visiblePages: IntRange = IntRange.EMPTY
    // ZOUKAN <--
    /** Only read and changed on the main thread */
    private val pageJobs = mutableMapOf<Int, Job>()
    private var chapterOpenedAt = System.currentTimeMillis()

    /** Writes reading progress in page-turn order; on the app scope, so writes finish after the reader closes */
    private val progressWriter = ProgressWriter(appScope) { updateChapter.await(it) }

    private val _menuVisible = MutableStateFlow(false)
    val menuVisible: StateFlow<Boolean> = _menuVisible.asStateFlow()

    // ZOUKAN -->
    private val _pageActions = MutableStateFlow<PageActionsTarget?>(null)

    /** The page (or double page) whose actions sheet is open, as Android's page actions dialog */
    val pageActions: StateFlow<PageActionsTarget?> = _pageActions.asStateFlow()
    // ZOUKAN <--

    init {
        screenModelScope.launch {
            val manga = getManga.await(mangaId)
            if (manga == null) {
                mutableState.update { it.copy(loading = false, error = localized(DMR.strings.this_entry_no_longer_exists)) }
                return@launch
            }
            // ZOUKAN -->
            val chapters = if (manga.source == MERGED_SOURCE_ID) {
                getMergedChapters.value.await(mangaId)
            } else {
                getChapters.await(mangaId)
            }.sortedByDescending { it.sourceOrder }
            // ZOUKAN <--
            // ZOUKAN -->
            source = sourceManager.get(manga.source)
            // An entry without its own reading mode reads as webtoon when it looks like one, as SY's auto webtoon mode
            val mode = (manga.viewerFlags.toInt() and ReadingModes.MASK).takeIf { it != ReadingModes.DEFAULT }
                ?: ReadingModes.WEBTOON.takeIf { preferences.useAutoWebtoon().get() && AutoWebtoon.isWebtoon(manga, source?.name) }
                ?: preferences.defaultReadingMode().get().takeIf { it in ReadingModes.all }
                ?: ReadingModes.RIGHT_TO_LEFT
            // ZOUKAN <--
            mutableState.update { it.copy(manga = manga, chapters = chapters, readingMode = mode) }
            val chapter = chapters.firstOrNull { it.id == initialChapterId }
            if (chapter == null) {
                mutableState.update { it.copy(loading = false, error = localized(MR.strings.chapter_not_found)) }
            } else {
                loadChapter(chapter, fromStart = false)
            }
        }
    }

    fun toggleMenu() = _menuVisible.update { !it }

    fun setMenu(visible: Boolean) {
        _menuVisible.value = visible
    }

    fun loadChapter(chapter: Chapter, fromStart: Boolean = true, atEnd: Boolean = false) {
        val readerManga = state.value.manga ?: return
        saveHistory()
        loadJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        closeFormat()
        // ZOUKAN -->
        _pageActions.value = null
        prefetchJob?.cancel()
        keepAllBytes = false
        visiblePages = IntRange.EMPTY
        // The old chapter's decoded pages free their pixels once the viewers stop drawing them
        state.value.pages.forEach { it.release() }
        // ZOUKAN <--
        mutableState.update {
            it.copy(
                chapter = chapter,
                pages = emptyList(),
                loading = true,
                error = null,
                currentPage = 0,
                navigable = navigable(it.chapters, chapter),
                // ZOUKAN -->
                widePages = emptySet(),
                shiftDoublePages = false,
                // ZOUKAN <--
            )
        }
        // ZOUKAN -->
        // Unknown until the chapter's own entry is: nothing is written for it before then
        incognito = true
        // ZOUKAN <--
        loadJob = screenModelScope.launch {
            try {
                // ZOUKAN -->
                // A merged entry's chapter is read from the entry and source it comes from
                val manga = chapterManga(readerManga, chapter) { getManga.await(it) }
                    ?: error(localized(DMR.strings.the_merged_entry_of_this_chapter))
                val source = sourceManager.get(manga.source)
                this@ReaderScreenModel.source = source
                // Per-extension incognito follows the chapter's own source, also in a merged entry
                incognito = incognitoPolicy.value.isOn(manga.source)
                if (!incognito) syncManager.value.syncOn { it.syncOnChapterOpen }
                // ZOUKAN <--
                // A download (or the local source's file) first, then the source online
                val file = withContext(Dispatchers.IO) {
                    if (source is LocalSource) source.chapterFile(chapter.url) else downloadManager.findChapter(manga, chapter)
                }
                val pages = if (file != null) {
                    // The open file belongs to this load until it is handed to [format], and is closed
                    // otherwise: a chapter switch during the open makes withContext throw on return and
                    // drop its result, and a file left open stays locked (Windows cannot delete it)
                    var opened: LocalFormat? = null
                    try {
                        val owned = withContext(Dispatchers.IO + NonCancellable) {
                            LocalFormat.open(file) { cbzPassword.get() }.also { opened = it }
                        }
                        ensureActive()
                        format = owned
                        opened = null
                        List(owned.pageCount) { ReaderPage(it, Page(it)) }
                    } finally {
                        opened?.let { orphan -> appScope.launch(Dispatchers.IO) { runCatching { orphan.close() } } }
                    }
                } else {
                    if (source !is HttpSource) error(localized(DMR.strings.the_source_of_this_entry_is))
                    withContext(Dispatchers.IO) { source.getPageList(chapter.toSChapter()) }
                        .mapIndexed { i, page -> ReaderPage(i, page) }
                }
                mutableState.update { it.copy(fromDisk = file != null) }
                if (pages.isEmpty()) error(localized(DMR.strings.the_source_returned_no_pages))
                // ZOUKAN -->
                val requested = initialPage?.takeIf { chapter.id == initialChapterId }
                initialPage = null
                // ZOUKAN <--
                val start = when {
                    // ZOUKAN -->
                    requested != null -> requested.coerceIn(0, pages.lastIndex)
                    // ZOUKAN <--
                    atEnd -> pages.lastIndex
                    // ZOUKAN -->
                    fromStart -> 0
                    chapter.read && !preferences.preserveReadingPosition().get() -> 0
                    // ZOUKAN <--
                    else -> chapter.lastPageRead.toInt().coerceIn(0, pages.lastIndex)
                }
                chapterOpenedAt = System.currentTimeMillis()
                mutableState.update {
                    it.copy(
                        pages = pages,
                        loading = false,
                        currentPage = start,
                        firstShownPage = start,
                        startPage = start,
                        generation = it.generation + 1,
                    )
                }
                onPageChanged(start)
                // ZOUKAN -->
                if (preferences.aggressivePageLoading().get() && file == null) prefetchAll(pages)
                // ZOUKAN <--
                // ZOUKAN -->
                discordPresence.value.setReading(
                    zoukan.desktop.discord.DiscordPresence.Reading(
                        mangaId = readerManga.id,
                        title = readerManga.title,
                        chapter = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        coverUrl = readerManga.thumbnailUrl,
                        sourceId = readerManga.source,
                    ),
                )
                // ZOUKAN <--
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The reader stops loading before a fatal error goes on, or it spins for good
                mutableState.update { it.copy(loading = false, error = sourceErrorText(e)) }
                e.rethrowIfVmFailure()
                logcat(LogPriority.ERROR, e) { "Loading pages of ${chapter.name} failed" }
            }
        }
    }

    fun retryChapter() {
        state.value.chapter?.let { loadChapter(it, fromStart = false) }
    }

    /** Called by the viewers when the visible page changes; [first] is the other page of a double page. */
    fun onPageChanged(index: Int, first: Int = index) {
        val state = state.value
        val chapter = state.chapter ?: return
        if (index !in state.pages.indices) return
        mutableState.update { it.copy(currentPage = index, firstShownPage = first) }
        // ZOUKAN -->
        // After the screen was popped the reader still shows while it fades out, and its viewer can
        // report the page it settled on then: that page is still saved below, but no page loads
        if (!disposed) {
            requestPages(index)
            if ((index + 1).toDouble() / state.pages.size > 0.25) downloadAhead()
        }
        // ZOUKAN <--
        val last = index == state.pages.lastIndex
        val read = chapter.read || last
        // ZOUKAN -->
        // Incognito keeps no progress, history or tracking, as on Android
        if (incognito) return
        // ZOUKAN <--
        progressWriter.submit(progressUpdate(chapter.id, index, read))
        if (last && !chapter.read) {
            // ZOUKAN -->
            syncManager.value.syncOn { it.syncOnChapterRead }
            if (chapter.isRecognizedNumber) {
                appScope.launch {
                    zoukan.desktop.app.runCatchingCancellable { trackChapters.value.afterReading(mangaId, chapter.chapterNumber) }
                }
            }
            // ZOUKAN <--
            mutableState.update { s ->
                s.copy(
                    chapter = s.chapter?.copy(read = true),
                    chapters = s.chapters.map { if (it.id == chapter.id) it.copy(read = true) else it },
                )
            }
            // ZOUKAN -->
            afterChapterRead(chapter.copy(read = true))
            // ZOUKAN <--
        }
    }

    // ZOUKAN -->
    /** Read chapters whose downloads go when the reader closes, as Android's pending deletions */
    private val pendingDeletes = LinkedHashMap<Long, Chapter>()

    /** The chapter download ahead last ran for, so it runs once per chapter */
    private var downloadedAheadFor: Long? = null

    /**
     * After [chapter] was read to the end: "Automatically delete after reading" (the chapter that
     * many chapters back, in the reading list), and "Mark duplicate read chapters as read", as
     * Android's ReaderViewModel.
     */
    private fun afterChapterRead(chapter: Chapter) {
        val state = state.value
        val slots = downloadPreferences.removeAfterReadSlots().get()
        val markDuplicates = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(tachiyomi.domain.library.service.LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        val decision = ReadDeletionPolicy.afterRead(chapter, state.navigable, state.chapters, slots, markDuplicates)
        if (disposed) {
            // Read after the reader closed (see onPageChanged): its file is closed already, so the
            // downloads go now instead of waiting for a close that has happened
            val deletes = decision.toDelete
            if (deletes.isNotEmpty()) appScope.launch(Dispatchers.IO) { ReaderCleanup.closeThenDelete(null, deletes) { downloadManager.deleteRead(it) } }
        } else {
            decision.toDelete.forEach { pendingDeletes[it.id] = it }
        }
        val duplicates = decision.markRead
        if (duplicates.isNotEmpty()) {
            appScope.launch(CoroutineName("duplicate chapters read")) { updateChapter.awaitAll(duplicates.map { ChapterUpdate(id = it.id, read = true) }) }
            val ids = duplicates.mapTo(HashSet()) { it.id }
            mutableState.update { s -> s.copy(chapters = s.chapters.map { if (it.id in ids) it.copy(read = true) else it }) }
        }
    }

    /**
     * Download ahead ("Download ahead" in Settings > Downloads): once a quarter of a downloaded
     * chapter is read and the next one is downloaded too, queues the next unread chapters, as
     * Android's ReaderViewModel.downloadNextChapters.
     */
    private fun downloadAhead() {
        val amount = downloadPreferences.autoDownloadWhileReading().get()
        val state = state.value
        val current = state.chapter ?: return
        if (amount <= 0 || !state.fromDisk || downloadedAheadFor == current.id) return
        val readerManga = state.manga ?: return
        if (readerManga.source == LocalSource.ID) return
        val next = state.nextChapter ?: return
        downloadedAheadFor = current.id
        appScope.launch(Dispatchers.IO + CoroutineName("download ahead")) {
            val mangas = HashMap<Long, Manga?>()
            suspend fun mangaOf(chapter: Chapter): Manga? =
                if (chapter.mangaId == readerManga.id) readerManga else mangas.getOrPut(chapter.mangaId) { getManga.await(chapter.mangaId) }
            downloadAheadRule.chaptersToQueue(readerManga, next, state.navigable, amount) { mangaOf(it) }
                .forEach { (manga, list) -> downloadManager.enqueue(manga, list) }
        }
    }
    // ZOUKAN <--

    /** Loads the pages around [center] and releases decoded images far from it. */
    private fun requestPages(center: Int) {
        val pages = state.value.pages
        // ZOUKAN -->
        val ahead = preferences.preloadSize().get().coerceIn(1, 50)
        val keepAhead = maxOf(KEEP_AHEAD, ahead + 2)
        val kept = PageWindow.kept(
            pages.size,
            center,
            KEEP_BEHIND,
            keepAhead,
            visiblePages,
            pixels = { pages[it].decodedWidth.toLong() * pages[it].decodedHeight },
        )
        // A page of known size that does not fit the budget is not decoded only to be released again
        val wanted = (center - 2..center + ahead).filter { it in pages.indices && (it in kept || pages[it].decodedWidth == 0) }
            // ZOUKAN <--
            .sortedBy { kotlin.math.abs(it - center) + if (it < center) 1 else 0 }
        wanted.forEach(::loadPage)
        pages.forEach { page ->
            // ZOUKAN -->
            if (page.index !in kept) {
                // ZOUKAN <--
                page.release()
            }
            // The downloaded bytes are kept longer, so going back a few pages needs no network
            // ZOUKAN -->
            // Aggressive page loading keeps every page's bytes
            if (page.index !in center - 3 * KEEP_BEHIND..center + 3 * keepAhead && !keepAllBytes) page.bytes = null
            // ZOUKAN <--
        }
    }

    fun loadPage(index: Int, retry: Boolean = false, attempt: Int = 0) {
        val page = state.value.pages.getOrNull(index) ?: return
        val current = page.state.value
        if (current is ReaderPage.State.Ready || current is ReaderPage.State.Loading) return
        if (current is ReaderPage.State.Error && !retry) return
        if (pageJobs[index]?.isActive == true) return
        val format = format
        val source = source as? HttpSource
        if (format == null && source == null) return
        page.setState(ReaderPage.State.Loading)
        // ZOUKAN -->
        // Started once it owns the page, so even a load that ends at once finds itself the owner
        val job = screenModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            fun owns() = page.loader === self
            // ZOUKAN <--
            try {
                val bytes = page.bytes
                    ?: (format?.page(index) ?: downloads.withPermit { fetch(source!!, page.page) }).also { page.bytes = it }
                // ZOUKAN -->
                val widthPx = viewportWidth
                val maxWidth = if (widthPx > 0) (widthPx * MAX_ZOOM).toInt() else null
                val image = decodes.withPermit { PageImage.decode(bytes, cropBorders(), maxWidth) }
                if (!isActive || !owns()) {
                    image.release()
                    ensureActive()
                }
                if (image.width > image.height) {
                    mutableState.update { if (index in it.widePages || it.pages.getOrNull(index) !== page) it else it.copy(widePages = it.widePages + index) }
                }
                if (owns()) {
                    page.rememberSize(image.width, image.height)
                    page.setState(ReaderPage.State.Ready(image))
                } else {
                    image.release()
                }
                // ZOUKAN <--
            } catch (e: CancellationException) {
                // ZOUKAN -->
                // A newer load (Boost page) owns the page now, and its state is not this load's to reset
                if (owns()) page.setState(ReaderPage.State.Queued)
                // ZOUKAN <--
                throw e
            } catch (e: Throwable) {
                // ZOUKAN -->
                // A cancelled download can fail with an I/O error ("Canceled"): that is no page error.
                // The page is an error before a fatal error goes on: a page left loading is never
                // loaded again, and "Retry" passes over it
                val ours = isActive && owns()
                if (ours) {
                    page.bytes = null
                    page.setState(ReaderPage.State.Error(sourceErrorText(e)))
                }
                e.rethrowIfVmFailure()
                if (!ours) return@launch
                // ZOUKAN <--
                logcat(LogPriority.WARN, e) { "Page ${index + 1} failed" }
                // ZOUKAN -->
                // SY's "Instant retry": a failed page is tried once more at once
                if (attempt == 0 && preferences.readerInstantRetry().get()) {
                    // pageJobs is only touched on the main thread
                    withContext(Dispatchers.Main) {
                        if (pageJobs[index] === self) pageJobs.remove(index)
                        loadPage(index, retry = true, attempt = 1)
                    }
                }
                // ZOUKAN <--
            }
        }
        // ZOUKAN -->
        page.loader = job
        pageJobs[index] = job
        job.start()
        // ZOUKAN <--
    }

    // ZOUKAN -->
    @Volatile
    private var keepAllBytes = false

    /** SY's "Aggressive page loading": downloads every page of an online chapter in the background. */
    private fun prefetchAll(pages: List<ReaderPage>) {
        val source = source as? HttpSource ?: return
        keepAllBytes = true
        val job = screenModelScope.launch(Dispatchers.IO) {
            pages.forEach { page ->
                if (page.bytes != null) return@forEach
                try {
                    downloads.withPermit { if (page.bytes == null) page.bytes = fetch(source, page.page) }
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

    private var prefetchJob: Job? = null

    /** SY's "Retry all": loads every failed page again. */
    fun retryAllPages() {
        val failed = state.value.pages.filter { it.state.value is ReaderPage.State.Error }
        if (failed.isEmpty()) {
            Toasts.show(localized(DMR.strings.no_pages_failed))
            return
        }
        val current = state.value.currentPage
        failed.sortedBy { kotlin.math.abs(it.index - current) }.forEach { loadPage(it.index, retry = true) }
    }

    /** SY's "Boost page": the shown page jumps the queue; other pages in flight start again after it. */
    fun boostPage() {
        val index = state.value.currentPage
        val page = state.value.pages.getOrNull(index) ?: return
        if (page.state.value is ReaderPage.State.Ready) return
        val pages = state.value.pages
        // The cancelled loads give up their pages here, so they are queued again below; their own
        // cancel handlers then find a newer owner and leave the state alone
        pageJobs.toList().forEach { (i, job) ->
            job.cancel()
            pageJobs.remove(i)
            val other = pages.getOrNull(i) ?: return@forEach
            if (other.loader === job) {
                other.loader = null
                if (other.state.value is ReaderPage.State.Loading) other.setState(ReaderPage.State.Queued)
            }
        }
        if (page.state.value !is ReaderPage.State.Ready) page.setState(ReaderPage.State.Queued)
        loadPage(index, retry = true)
        requestPages(index)
    }

    /** The viewer's width in pixels, read when a page decodes: no page is decoded wider than it can be zoomed */
    @Volatile
    private var viewportWidth = 0

    fun onViewport(widthPx: Int) {
        viewportWidth = widthPx
    }

    /** Called by the webtoon viewer with the pages in its window; those are never released. */
    fun onVisiblePages(visible: IntRange) {
        visiblePages = visible
    }

    /** SY's "Shift double pages", for this chapter */
    fun toggleShiftDoublePages() {
        mutableState.update { it.copy(shiftDoublePages = !it.shiftDoublePages) }
    }
    // ZOUKAN <--

    private suspend fun fetch(source: HttpSource, page: Page): ByteArray {
        if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
        // ZOUKAN -->
        // The SY data saver, as Android's HttpPageLoader
        val dataSaver = runCatching { sourcePreferences.value }.getOrNull()
            ?.let { exh.util.DataSaver(source, it) } ?: exh.util.DataSaver.NoOp
        return with(exh.util.DataSaver) { source.getImage(page, dataSaver) }.use { response ->
            // ZOUKAN <--
            if (!response.isSuccessful) throw eu.kanade.tachiyomi.network.HttpException(response.code)
            response.body.bytes()
        }
    }

    // ZOUKAN -->
    /** The chapters to go through, skipping as Android's skip read, skip filtered and skip duplicate settings say */
    private fun navigable(chapters: List<Chapter>, current: Chapter): List<Chapter> {
        val manga = state.value.manga ?: return chapters
        val skipRead = preferences.skipRead().get()
        val skipFiltered = preferences.skipFiltered().get()
        var list = chapters.filter { c ->
            c.id == current.id || when {
                skipRead && c.read -> false
                skipFiltered -> !(
                    (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !c.read) ||
                        (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && c.read) ||
                        (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !c.bookmark) ||
                        (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && c.bookmark) ||
                        (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED && !isOnDisk(manga, c)) ||
                        (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED && isOnDisk(manga, c))
                    )
                else -> true
            }
        }
        if (preferences.skipDupe().get()) {
            val keep = list.groupBy { it.chapterNumber }.values.map { group ->
                group.find { it.id == current.id } ?: group.find { it.scanlator == current.scanlator } ?: group.first()
            }.mapTo(HashSet()) { it.id }
            list = list.filter { it.id in keep || it.chapterNumber < 0 }
        }
        return list
    }

    /** Decodes the shown pages again, after a setting that changes decoding (crop borders). */
    fun redecodePages() {
        state.value.pages.forEach { it.release() }
        requestPages(state.value.currentPage)
    }

    private fun cropBorders(): Boolean = preferences.cropBordersFor(state.value.readingMode).get()

    private fun isOnDisk(manga: Manga, chapter: Chapter): Boolean =
        manga.source == LocalSource.ID || runCatching { downloadManager.isDownloaded(manga, chapter) }.getOrDefault(false)
    // ZOUKAN <--

    // ZOUKAN -->
    /** Opens the actions of [index], and of [extra] on a double page; only a loaded page has them, as on Android. */
    fun openPageActions(index: Int, extra: Int? = null, leftFirst: Boolean = true) {
        val pages = state.value.pages
        fun ready(i: Int?) = i != null && pages.getOrNull(i)?.state?.value is ReaderPage.State.Ready
        when {
            ready(index) -> _pageActions.value = PageActionsTarget(index, extra.takeIf { ready(it) }, leftFirst)
            ready(extra) -> _pageActions.value = PageActionsTarget(extra!!)
        }
    }

    fun closePageActions() {
        _pageActions.value = null
    }

    /** Saves the page into the Pictures folder, in a folder per entry when that setting is on. */
    fun savePage(index: Int) = pageAction(localized(DMR.strings.could_not_save_the_picture)) {
        val bytes = pageBytes(index)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${index + 1}")
        Toasts.show(localized(DMR.strings.picture_saved_to, file.parent))
    }

    fun copyPage(index: Int) = pageAction(localized(DMR.strings.could_not_copy_the_picture)) {
        PageImages.copy(pageBytes(index))
        Toasts.show(localized(MR.strings.copied_to_clipboard_plain))
    }

    fun saveCombined(target: PageActionsTarget) = pageAction(localized(DMR.strings.could_not_save_the_picture)) {
        val bytes = combined(target)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${target.page + 1}-${target.extra!! + 1}")
        Toasts.show(localized(DMR.strings.picture_saved_to, file.parent))
    }

    fun copyCombined(target: PageActionsTarget) = pageAction(localized(DMR.strings.could_not_copy_the_picture)) {
        PageImages.copy(combined(target))
        Toasts.show(localized(MR.strings.copied_to_clipboard_plain))
    }

    /** Sets the page as the entry's cover: a library entry's custom cover, or a local entry's cover file, as Android. */
    fun setPageAsCover(index: Int) = pageAction(localized(MR.strings.notification_cover_update_failed)) {
        val manga = getManga.await(mangaId) ?: return@pageAction
        val edit = editManga.value
        if (!edit.canEditCover(manga)) {
            Toasts.show(localized(MR.strings.notification_first_add_to_library))
            return@pageAction
        }
        val bytes = pageBytes(index)
        // ZOUKAN: in the app's own temporary folder, emptied at every start, not the system's
        val dir = zoukan.desktop.AppTempFiles.newDir("cover")
        try {
            val file = java.io.File(dir, "cover.${PageImages.extension(bytes) ?: "jpg"}")
            file.writeBytes(bytes)
            edit.setCover(manga, file)
        } finally {
            zoukan.desktop.download.SafeDelete.deleteTreeNoFollow(dir.toPath())
        }
        Toasts.show(localized(MR.strings.cover_updated))
    }

    /** Runs a page action to the end even when the reader closes, as Android's launchNonCancellable. */
    private fun pageAction(failure: String, block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.rethrowIfVmFailure()
                logcat(LogPriority.ERROR, e) { failure }
                Toasts.error(failure, e)
            }
        }
    }

    /** The page's image as the source or file has it, read again when it was let go. */
    private suspend fun pageBytes(index: Int): ByteArray {
        val page = state.value.pages.getOrNull(index) ?: error(localized(DMR.strings.the_page_is_gone))
        page.bytes?.let { return it }
        format?.let { return it.page(index) }
        val source = source as? HttpSource ?: error(localized(DMR.strings.the_source_of_this_entry_is))
        return downloads.withPermit { fetch(source, page.page) }
    }

    private suspend fun combined(target: PageActionsTarget): ByteArray {
        if (target.extra == null) error(localized(DMR.strings.not_a_double_page))
        val background = readerBackground(preferences.readerTheme().get())
        return PageImages.combine(pageBytes(target.page), pageBytes(target.extra), target.leftFirst, background.toArgb())
    }

    private fun picturesFolder(): java.io.File {
        val root = PageImages.picturesDir()
        val title = state.value.manga?.title
        return if (preferences.folderPerManga().get() && !title.isNullOrBlank()) {
            java.io.File(root, PageImages.validFilename(title))
        } else {
            root
        }
    }

    /** "<title> - <chapter>", as Android names saved pages */
    private fun pageFileName(): String = "${state.value.manga?.title.orEmpty()} - ${state.value.chapter?.name.orEmpty()}"
    // ZOUKAN <--

    fun setReadingMode(mode: Int) {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(readingMode = mode, startPage = it.currentPage, generation = it.generation + 1) }
        screenModelScope.launch {
            val flags = (manga.viewerFlags and ReadingModes.MASK.toLong().inv()) or mode.toLong()
            mangaRepository.update(MangaUpdate(id = manga.id, viewerFlags = flags))
        }
    }

    fun nextChapter(): Boolean {
        val next = state.value.nextChapter ?: return false
        loadChapter(next, fromStart = true)
        return true
    }

    fun previousChapter(atEnd: Boolean = false): Boolean {
        val previous = state.value.previousChapter ?: return false
        loadChapter(previous, fromStart = !atEnd, atEnd = atEnd)
        return true
    }

    // ZOUKAN -->
    /**
     * Whether the open chapter is read in incognito, for the source it comes from: a merged entry's
     * chapter by its own entry's source, not the merged entry's. Set by [loadChapter] once that
     * entry is known, true until then.
     */
    @Volatile
    private var incognito: Boolean = true
    // ZOUKAN <--

    private fun saveHistory() {
        if (incognito) return
        val chapter = state.value.chapter ?: return
        if (state.value.pages.isEmpty()) return
        val duration = System.currentTimeMillis() - chapterOpenedAt
        chapterOpenedAt = System.currentTimeMillis()
        appScope.launch(CoroutineName("reading history")) {
            upsertHistory.await(HistoryUpdate(chapter.id, Date(), duration))
        }
    }

    private fun closeFormat() {
        val old = format ?: return
        format = null
        appScope.launch(Dispatchers.IO) { runCatching { old.close() } }
    }

    // ZOUKAN -->
    /**
     * Set when Voyager disposes the model, which it does as soon as the screen is popped, while the
     * reader still fades out and can report a page. Only read and changed on the main thread.
     */
    private var disposed = false
    // ZOUKAN <--

    override fun onDispose() {
        // ZOUKAN -->
        disposed = true
        // The open file is closed before the read downloads are deleted, in that order: Windows
        // cannot delete a file that is still open
        val old = format
        format = null
        val deletes = pendingDeletes.values.toList()
        if (old != null || deletes.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) { ReaderCleanup.closeThenDelete(old, deletes) { downloadManager.deleteRead(it) } }
        }
        // ZOUKAN <--
        // ZOUKAN -->
        discordPresence.value.clearReading()
        // ZOUKAN <--
        val chapter = state.value.chapter
        if (chapter != null && state.value.pages.isNotEmpty() && !incognito) {
            val duration = System.currentTimeMillis() - chapterOpenedAt
            appScope.launch(CoroutineName("reading history")) {
                upsertHistory.await(HistoryUpdate(chapter.id, Date(), duration))
            }
        }
        super.onDispose()
    }

    // ZOUKAN -->
    /** Bookmarks [chapter] (the open one by default) or removes its bookmark, as the reader's top bar */
    fun toggleBookmark(chapter: Chapter? = state.value.chapter) {
        chapter ?: return
        val bookmark = !(state.value.chapters.find { it.id == chapter.id } ?: chapter).bookmark
        fun Chapter.updated() = if (id == chapter.id) copy(bookmark = bookmark) else this
        mutableState.update { s ->
            s.copy(
                chapter = s.chapter?.updated(),
                chapters = s.chapters.map { it.updated() },
                navigable = s.navigable.map { it.updated() },
            )
        }
        appScope.launch(CoroutineName("bookmark")) {
            updateChapter.await(ChapterUpdate(id = chapter.id, bookmark = bookmark))
        }
    }

    /** Opens [chapter] from the chapter list, where it was left off, as Android's reader chapter list */
    fun openChapter(chapter: Chapter) {
        if (chapter.id == state.value.chapter?.id) return
        screenModelScope.launch {
            val fresh = zoukan.desktop.app.runCatchingCancellable { getChapter.value.await(chapter.id) }
                .getOrNull() ?: chapter
            loadChapter(fresh, fromStart = false)
        }
    }

    private fun chapterUrl(): String? {
        val chapter = state.value.chapter ?: return null
        val source = source as? HttpSource ?: return null
        return runCatching { source.getChapterUrl(chapter.toSChapter()) }.getOrNull()
    }

    /** Opens the chapter's page in the built-in browser, which shares cookies with the app */
    fun openChapterInWebView() {
        val url = chapterUrl() ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        appScope.launch {
            if (!zoukan.desktop.browser.BrowserWindow(url).open()) Toasts.show(localized(DMR.strings.the_built_in_browser_could_not))
        }
    }

    /** Opens the chapter's page in the system browser */
    fun openChapterInBrowser() {
        val url = chapterUrl() ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }
                    .onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_browser), it) }
            }
        }
    }
    // ZOUKAN <--

    companion object {
        private const val KEEP_BEHIND = 4
        private const val KEEP_AHEAD = 10

        // ZOUKAN -->
        private val DECODE_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /** The most a page is zoomed in: the paged viewer's, which is above the webtoon's 3 */
        private const val MAX_ZOOM = PageZoom.MAX_ZOOM
        // ZOUKAN <--
    }
}

// ZOUKAN -->
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
        val others = window.filter { it !in kept }.sortedBy { kotlin.math.abs(it - center) + if (it < center) 1 else 0 }
        for (index in others) {
            total += pixels(index)
            if (total > BUDGET_PIXELS) break
            kept += index
        }
        return kept
    }
}

internal object ReaderCleanup {
    /**
     * Closes the reader's [file] (null when it read online), then deletes the read [deletes]. Both
     * run in this order in one coroutine, so a download is never deleted while it is still open.
     */
    suspend fun closeThenDelete(file: AutoCloseable?, deletes: List<Chapter>, delete: suspend (List<Chapter>) -> Unit) {
        if (file != null) {
            try {
                file.close()
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                logcat(LogPriority.WARN, e) { "Closing the chapter file failed" }
            }
        }
        if (deletes.isEmpty()) return
        try {
            delete(deletes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            logcat(LogPriority.ERROR, e) { "Deleting read chapters failed" }
        }
    }
}
// ZOUKAN <--

// ZOUKAN -->
/**
 * The progress write for being on page [index]: the page always, and the read mark only when
 * [read], so a progress write never makes a read chapter unread. Android's
 * ReaderViewModel.updateChapterProgress writes the reader's in-memory read flag, which the reader
 * itself only ever sets to true; leaving read out here also keeps a stale false from undoing a
 * read mark made elsewhere (the library or a tracker) while the chapter is open.
 */
internal fun progressUpdate(chapterId: Long, index: Int, read: Boolean) =
    ChapterUpdate(id = chapterId, read = true.takeIf { read }, lastPageRead = index.toLong())

/**
 * Runs the reader's progress writes one at a time, in the order they were submitted, so a late
 * write never overwrites a newer one (the page going back, or a read chapter becoming unread).
 *
 * Each write is its own job on [scope] (the app scope), which waits for the one before it. Nothing
 * needs closing: a write submitted after the reader was disposed (the last page, reported while the
 * reader fades out) is saved like any other, and once the writes are done no job is left, so
 * quitting, which waits for the app scope's jobs, waits for exactly the writes still to run.
 */
internal class ProgressWriter(private val scope: CoroutineScope, private val write: suspend (ChapterUpdate) -> Unit) {
    /** The last write submitted; guarded by this */
    private var last: Job? = null

    fun submit(update: ChapterUpdate) {
        synchronized(this) {
            val previous = last
            last = scope.launch(CoroutineName("reading progress")) {
                previous?.join()
                try {
                    write(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    logcat(LogPriority.ERROR, e) { "Saving reading progress failed" }
                }
            }
        }
    }

    /** Returns once every write submitted so far has run. */
    suspend fun join() {
        synchronized(this) { last }?.join()
    }
}
// ZOUKAN <--

// ZOUKAN -->
/**
 * The entry [chapter] is read from: [readerManga] itself, or for a merged entry the merged entry the
 * chapter belongs to (null when it is gone), whose source gives the pages, the downloads and the
 * per-extension incognito.
 */
internal suspend fun chapterManga(readerManga: Manga, chapter: Chapter, getManga: suspend (Long) -> Manga?): Manga? =
    if (readerManga.source == MERGED_SOURCE_ID && chapter.mangaId != readerManga.id) getManga(chapter.mangaId) else readerManga
// ZOUKAN <--
