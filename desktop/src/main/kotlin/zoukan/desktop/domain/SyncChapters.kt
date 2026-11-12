package zoukan.desktop.domain

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.isEhBasedManga
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import java.time.Instant
import java.time.ZonedDateTime
import java.util.TreeSet

/**
 * Saves a source's chapter list to the database, as the Android app's SyncChaptersWithSource:
 * renames the downloads of chapters the source renamed, and leaves chapters of excluded
 * scanlators out of the new chapters it returns.
 */
class SyncChapters(
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val fetchInterval: FetchInterval,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
    private val downloadPaths: DownloadPaths,
    private val getExcludedScanlators: GetExcludedScanlators,
) {
    /** @return the new chapters */
    suspend fun await(rawSourceChapters: List<SChapter>, manga: Manga, source: Source): List<Chapter> {
        if (rawSourceChapters.isEmpty()) throw NoChaptersException()

        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = i.toLong())
            }

        val dbChapters = getChaptersByMangaId.await(manga.id)
        val sourceUrls = sourceChapters.mapTo(HashSet()) { it.url }

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<Chapter>()
        val removedChapters = dbChapters.filterNot { it.url in sourceUrls }
        var maxSeenUploadDate = 0L

        for (sourceChapter in sourceChapters) {
            var chapter = sourceChapter
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                @Suppress("DEPRECATION")
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }
            chapter = chapter.copy(
                chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapterNumber),
            )

            val dbChapter = dbChapters.find { it.url == chapter.url }
            if (dbChapter == null) {
                newChapters += if (chapter.dateUpload == 0L) {
                    chapter.copy(dateUpload = if (maxSeenUploadDate == 0L) nowMillis else maxSeenUploadDate)
                } else {
                    maxSeenUploadDate = maxOf(maxSeenUploadDate, sourceChapter.dateUpload)
                    chapter
                }
            } else if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                val shouldRenameChapter = downloadPaths.isChapterDirNameChanged(dbChapter, chapter) &&
                    downloadManager.isDownloaded(manga, dbChapter)
                if (shouldRenameChapter) downloadManager.renameChapter(manga, dbChapter, chapter)

                var changed = dbChapter.copy(
                    name = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    scanlator = chapter.scanlator,
                    sourceOrder = chapter.sourceOrder,
                    memo = chapter.memo,
                )
                if (chapter.dateUpload != 0L) changed = changed.copy(dateUpload = chapter.dateUpload)
                updatedChapters += changed
            }
        }

        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            mangaRepository.update(fetchInterval.toMangaUpdate(manga, now, fetchInterval.getWindow(now)))
            return emptyList()
        }

        val duplicateOrRestoredUrls = mutableSetOf<String>()
        val deletedNumbers = TreeSet<Double>()
        val deletedReadNumbers = TreeSet<Double>()
        val deletedBookmarkedNumbers = TreeSet<Double>()
        val readNumbers = dbChapters.filter { it.read && it.isRecognizedNumber }.mapTo(HashSet()) { it.chapterNumber }
        removedChapters.forEach {
            if (it.read) deletedReadNumbers += it.chapterNumber
            if (it.bookmark) deletedBookmarkedNumbers += it.chapterNumber
            deletedNumbers += it.chapterNumber
        }
        val deletedFetchDates = removedChapters.sortedByDescending { it.dateFetch }
            .associate { it.chapterNumber to it.dateFetch }
        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

        // Sources list chapters newest first, so the upper ones get the larger fetch date
        var itemCount = newChapters.size
        var toAdd = newChapters.map { item ->
            var chapter = item.copy(dateFetch = nowMillis + itemCount--)
            if (chapter.chapterNumber in readNumbers && markDuplicateAsRead) {
                duplicateOrRestoredUrls += chapter.url
                chapter = chapter.copy(read = true)
            }
            if (!chapter.isRecognizedNumber || chapter.chapterNumber !in deletedNumbers) return@map chapter
            chapter = chapter.copy(
                read = chapter.chapterNumber in deletedReadNumbers,
                bookmark = chapter.chapterNumber in deletedBookmarkedNumbers,
            )
            deletedFetchDates[chapter.chapterNumber]?.let { chapter = chapter.copy(dateFetch = it) }
            duplicateOrRestoredUrls += chapter.url
            chapter
        }

        // --> EXH (carry over reading progress)
        if (manga.isEhBasedManga()) {
            val hasNewChapters = toAdd.any { it.url !in duplicateOrRestoredUrls }
            val max = dbChapters.maxOfOrNull { it.lastPageRead }
            if (hasNewChapters && max != null && max > 0) {
                toAdd = toAdd.map { if (it.url !in duplicateOrRestoredUrls) it.copy(lastPageRead = max) else it }
            }
        }
        // <-- EXH

        if (removedChapters.isNotEmpty()) chapterRepository.removeChaptersWithIds(removedChapters.map { it.id })
        if (toAdd.isNotEmpty()) toAdd = chapterRepository.addAll(toAdd)
        if (updatedChapters.isNotEmpty()) updateChapter.awaitAll(updatedChapters.map { it.toChapterUpdate() })
        mangaRepository.update(fetchInterval.toMangaUpdate(manga, now, fetchInterval.getWindow(now)))
        mangaRepository.update(MangaUpdate(id = manga.id, lastUpdate = Instant.now().toEpochMilli()))

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()
        return toAdd.filterNot { it.url in duplicateOrRestoredUrls || it.scanlator in excludedScanlators }
    }

    companion object {
        /**
         * Whether the chapter's upload date came from its source. A source without dates (MangaPill,
         * for one) leaves it 0, and syncing then sets it to the fetch time, as Android does: those
         * chapters get `dateUpload = now` and `dateFetch = now + 1…n`. Showing that date would claim
         * every chapter came out the day it was added.
         */
        fun hasSourceDate(chapter: Chapter): Boolean =
            chapter.dateUpload > 0 && chapter.dateFetch - chapter.dateUpload !in 1..MAX_FETCH_OFFSET

        // Fetch dates are spread one millisecond apart per new chapter
        private const val MAX_FETCH_OFFSET = 100_000L
    }
}
