package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.toArgb
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.domain.toSChapter
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.local.LocalSource
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.Toasts
import java.util.Date

/** One page of the open chapter, with its own loading state. */
class ReaderPage(val index: Int, val page: Page) {
    sealed interface State {
        data object Queued : State
        data object Loading : State
        data class Ready(val image: PageImage) : State
        data class Error(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Queued)

    @Volatile
    var bytes: ByteArray? = null
}

class ReaderScreenModel(
    private val mangaId: Long,
    private val initialChapterId: Long,
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
    private val downloads = Semaphore(4)
    private val pageJobs = mutableMapOf<Int, Job>()
    private var chapterOpenedAt = System.currentTimeMillis()

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
                mutableState.update { it.copy(loading = false, error = "This entry no longer exists") }
                return@launch
            }
            // ZOUKAN -->
            val chapters = if (manga.source == MERGED_SOURCE_ID) {
                Injekt.get<GetMergedChaptersByMangaId>().await(mangaId)
            } else {
                getChapters.await(mangaId)
            }.sortedByDescending { it.sourceOrder }
            // ZOUKAN <--
            val mode = (manga.viewerFlags.toInt() and ReadingModes.MASK).takeIf { it != ReadingModes.DEFAULT }
                ?: preferences.defaultReadingMode().get().takeIf { it in ReadingModes.all }
                ?: ReadingModes.RIGHT_TO_LEFT
            source = sourceManager.get(manga.source)
            mutableState.update { it.copy(manga = manga, chapters = chapters, readingMode = mode) }
            val chapter = chapters.firstOrNull { it.id == initialChapterId }
            if (chapter == null) {
                mutableState.update { it.copy(loading = false, error = "Chapter not found") }
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
        // ZOUKAN <--
        mutableState.update {
            it.copy(chapter = chapter, pages = emptyList(), loading = true, error = null, currentPage = 0, navigable = navigable(it.chapters, chapter))
        }
        // ZOUKAN -->
        if (!incognito) Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>().syncOn { it.syncOnChapterOpen }
        // ZOUKAN <--
        loadJob = screenModelScope.launch {
            try {
                // ZOUKAN -->
                // A merged entry's chapter is read from the entry and source it comes from
                val manga = if (readerManga.source == MERGED_SOURCE_ID && chapter.mangaId != readerManga.id) {
                    getManga.await(chapter.mangaId) ?: error("The merged entry of this chapter no longer exists")
                } else {
                    readerManga
                }
                val source = sourceManager.get(manga.source)
                this@ReaderScreenModel.source = source
                // ZOUKAN <--
                // A download (or the local source's file) first, then the source online
                val file = withContext(Dispatchers.IO) {
                    if (source is LocalSource) source.chapterFile(chapter.url) else downloadManager.findChapter(manga, chapter)
                }
                val pages = if (file != null) {
                    // Not cancellable: a chapter switch during the open would otherwise drop the open
                    // file without closing it, which keeps it locked (Windows cannot delete it)
                    val opened = withContext(Dispatchers.IO + NonCancellable) { LocalFormat.open(file) { cbzPassword.get() } }
                    if (!isActive) {
                        withContext(Dispatchers.IO + NonCancellable) { runCatching { opened.close() } }
                        throw CancellationException("Another chapter was opened")
                    }
                    format = opened
                    List(opened.pageCount) { ReaderPage(it, Page(it)) }
                } else {
                    if (source !is HttpSource) error("The source of this entry is not installed")
                    withContext(Dispatchers.IO) { source.getPageList(chapter.toSChapter()) }
                        .mapIndexed { i, page -> ReaderPage(i, page) }
                }
                mutableState.update { it.copy(fromDisk = file != null) }
                if (pages.isEmpty()) error("The source returned no pages")
                val start = when {
                    atEnd -> pages.lastIndex
                    fromStart || chapter.read -> 0
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
                Injekt.get<zoukan.desktop.discord.DiscordPresence>().setReading(
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
                logcat(LogPriority.ERROR, e) { "Loading pages of ${chapter.name} failed" }
                mutableState.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
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
        requestPages(index)
        // ZOUKAN -->
        if ((index + 1).toDouble() / state.pages.size > 0.25) downloadAhead()
        // ZOUKAN <--
        val last = index == state.pages.lastIndex
        val read = chapter.read || last
        // ZOUKAN -->
        // Incognito keeps no progress, history or tracking, as on Android
        if (incognito) return
        // ZOUKAN <--
        zoukan.desktop.ui.AppScope.launch {
            updateChapter.await(ChapterUpdate(id = chapter.id, read = read, lastPageRead = index.toLong()))
        }
        if (last && !chapter.read) {
            // ZOUKAN -->
            Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>().syncOn { it.syncOnChapterRead }
            if (chapter.isRecognizedNumber) {
                zoukan.desktop.ui.AppScope.launch {
                    runCatching { Injekt.get<TrackChapters>().afterReading(mangaId, chapter.chapterNumber) }
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
        if (slots >= 0) {
            val position = state.navigable.indexOfFirst { it.id == chapter.id }
            // The read state of this session, not the one the list was made with
            val toDelete = state.navigable.getOrNull(position - slots)
                ?.let { c -> if (c.id == chapter.id) chapter else state.chapters.find { it.id == c.id } ?: c }
            if (toDelete != null && toDelete.read) pendingDeletes[toDelete.id] = toDelete
        }
        val markDuplicates = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(tachiyomi.domain.library.service.LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (markDuplicates && chapter.isRecognizedNumber) {
            val duplicates = state.chapters.filter { !it.read && it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
            if (duplicates.isNotEmpty()) {
                zoukan.desktop.ui.AppScope.launch { updateChapter.awaitAll(duplicates.map { ChapterUpdate(id = it.id, read = true) }) }
                val ids = duplicates.mapTo(HashSet()) { it.id }
                mutableState.update { s -> s.copy(chapters = s.chapters.map { if (it.id in ids) it.copy(read = true) else it }) }
                // Android deletes them too when "Last read chapter" is the delete setting
                if (slots == 0) duplicates.forEach { pendingDeletes[it.id] = it.copy(read = true) }
            }
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
        zoukan.desktop.ui.AppScope.launch(Dispatchers.IO) {
            val mangas = HashMap<Long, Manga?>()
            suspend fun mangaOf(chapter: Chapter): Manga? =
                if (chapter.mangaId == readerManga.id) readerManga else mangas.getOrPut(chapter.mangaId) { getManga.await(chapter.mangaId) }
            val nextManga = mangaOf(next) ?: return@launch
            if (!downloadManager.isDownloaded(nextManga, next)) return@launch
            val from = state.navigable.indexOfFirst { it.id == next.id }
            val chapters = state.navigable.drop(from).filter { !it.read }.take(amount)
            chapters.groupBy { it.mangaId }.forEach { (_, list) ->
                val manga = mangaOf(list.first()) ?: return@forEach
                downloadManager.enqueue(manga, list)
            }
        }
    }
    // ZOUKAN <--

    /** Loads the pages around [center] and releases decoded images far from it. */
    private fun requestPages(center: Int) {
        val pages = state.value.pages
        val wanted = (center - 2..center + 6).filter { it in pages.indices }
            .sortedBy { kotlin.math.abs(it - center) + if (it < center) 1 else 0 }
        wanted.forEach(::loadPage)
        pages.forEach { page ->
            if (page.index !in center - KEEP_BEHIND..center + KEEP_AHEAD) {
                if (page.state.value is ReaderPage.State.Ready) page.state.value = ReaderPage.State.Queued
            }
            // The downloaded bytes are kept longer, so going back a few pages needs no network
            if (page.index !in center - 3 * KEEP_BEHIND..center + 3 * KEEP_AHEAD) page.bytes = null
        }
    }

    fun loadPage(index: Int, retry: Boolean = false) {
        val page = state.value.pages.getOrNull(index) ?: return
        val current = page.state.value
        if (current is ReaderPage.State.Ready || current is ReaderPage.State.Loading) return
        if (current is ReaderPage.State.Error && !retry) return
        if (pageJobs[index]?.isActive == true) return
        val format = format
        val source = source as? HttpSource
        if (format == null && source == null) return
        page.state.value = ReaderPage.State.Loading
        pageJobs[index] = screenModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = page.bytes
                    ?: (format?.page(index) ?: downloads.withPermit { fetch(source!!, page.page) }).also { page.bytes = it }
                page.state.value = ReaderPage.State.Ready(PageImage.decode(bytes, cropBorders()))
            } catch (e: CancellationException) {
                page.state.value = ReaderPage.State.Queued
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Page ${index + 1} failed" }
                page.bytes = null
                page.state.value = ReaderPage.State.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun fetch(source: HttpSource, page: Page): ByteArray {
        if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
        // ZOUKAN -->
        // The SY data saver, as Android's HttpPageLoader
        val dataSaver = runCatching { Injekt.get<eu.kanade.domain.source.service.SourcePreferences>() }.getOrNull()
            ?.let { exh.util.DataSaver(source, it) } ?: exh.util.DataSaver.NoOp
        return with(exh.util.DataSaver) { source.getImage(page, dataSaver) }.use { response ->
            // ZOUKAN <--
            if (!response.isSuccessful) error("HTTP ${response.code}")
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
        state.value.pages.forEach { page ->
            if (page.state.value is ReaderPage.State.Ready) page.state.value = ReaderPage.State.Queued
        }
        requestPages(state.value.currentPage)
    }

    private fun cropBorders(): Boolean {
        val mode = state.value.readingMode
        val webtoon = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL
        return if (webtoon) preferences.cropBordersWebtoon().get() else preferences.cropBorders().get()
    }

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
    fun savePage(index: Int) = pageAction("Could not save the picture") {
        val bytes = pageBytes(index)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${index + 1}")
        Toasts.show("Picture saved to ${file.parent}")
    }

    fun copyPage(index: Int) = pageAction("Could not copy the picture") {
        PageImages.copy(pageBytes(index))
        Toasts.show("Copied to clipboard")
    }

    fun saveCombined(target: PageActionsTarget) = pageAction("Could not save the picture") {
        val bytes = combined(target)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${target.page + 1}-${target.extra!! + 1}")
        Toasts.show("Picture saved to ${file.parent}")
    }

    fun copyCombined(target: PageActionsTarget) = pageAction("Could not copy the picture") {
        PageImages.copy(combined(target))
        Toasts.show("Copied to clipboard")
    }

    /** Sets the page as the entry's cover: a library entry's custom cover, or a local entry's cover file, as Android. */
    fun setPageAsCover(index: Int) = pageAction("Failed to update cover") {
        val manga = getManga.await(mangaId) ?: return@pageAction
        val edit = Injekt.get<zoukan.desktop.manga.EditManga>()
        if (!edit.canEditCover(manga)) {
            Toasts.show("Please add the entry to your library before doing this")
            return@pageAction
        }
        val bytes = pageBytes(index)
        val dir = java.nio.file.Files.createTempDirectory("zoukan-cover").toFile()
        try {
            val file = java.io.File(dir, "cover.${PageImages.extension(bytes) ?: "jpg"}")
            file.writeBytes(bytes)
            edit.setCover(manga, file)
        } finally {
            dir.deleteRecursively()
        }
        Toasts.show("Cover updated")
    }

    /** Runs a page action to the end even when the reader closes, as Android's launchNonCancellable. */
    private fun pageAction(failure: String, block: suspend () -> Unit) {
        zoukan.desktop.ui.AppScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { failure }
                Toasts.error(failure, e)
            }
        }
    }

    /** The page's image as the source or file has it, read again when it was let go. */
    private suspend fun pageBytes(index: Int): ByteArray {
        val page = state.value.pages.getOrNull(index) ?: error("The page is gone")
        page.bytes?.let { return it }
        format?.let { return it.page(index) }
        val source = source as? HttpSource ?: error("The source of this entry is not installed")
        return downloads.withPermit { fetch(source, page.page) }
    }

    private suspend fun combined(target: PageActionsTarget): ByteArray {
        if (target.extra == null) error("Not a double page")
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
    private val incognito: Boolean by lazy {
        Injekt.get<zoukan.desktop.security.Incognito>().isOn(state.value.manga?.source)
    }
    // ZOUKAN <--

    private fun saveHistory() {
        if (incognito) return
        val chapter = state.value.chapter ?: return
        if (state.value.pages.isEmpty()) return
        val duration = System.currentTimeMillis() - chapterOpenedAt
        chapterOpenedAt = System.currentTimeMillis()
        zoukan.desktop.ui.AppScope.launch {
            upsertHistory.await(HistoryUpdate(chapter.id, Date(), duration))
        }
    }

    private fun closeFormat() {
        val old = format ?: return
        format = null
        zoukan.desktop.ui.AppScope.launch(Dispatchers.IO) { runCatching { old.close() } }
    }

    override fun onDispose() {
        closeFormat()
        // ZOUKAN -->
        val deletes = pendingDeletes.values.toList()
        if (deletes.isNotEmpty()) zoukan.desktop.ui.AppScope.launch { runCatching { downloadManager.deleteRead(deletes) } }
        // ZOUKAN <--
        // ZOUKAN -->
        Injekt.get<zoukan.desktop.discord.DiscordPresence>().clearReading()
        // ZOUKAN <--
        val chapter = state.value.chapter
        if (chapter != null && state.value.pages.isNotEmpty() && !incognito) {
            val duration = System.currentTimeMillis() - chapterOpenedAt
            zoukan.desktop.ui.AppScope.launch {
                upsertHistory.await(HistoryUpdate(chapter.id, Date(), duration))
            }
        }
        super.onDispose()
    }

    companion object {
        private const val KEEP_BEHIND = 4
        private const val KEEP_AHEAD = 10
    }
}
