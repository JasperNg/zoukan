package zoukan.desktop.migration

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.migrationConfigScreen_removeDownloadsTitle
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.migration_flag_categories
import zoukan.i18n.desktop.migration_flag_chapters
import zoukan.i18n.desktop.migration_flag_custom_cover
import zoukan.i18n.desktop.migration_flag_extra
import zoukan.i18n.desktop.migration_flag_notes
import zoukan.i18n.desktop.migration_flag_tracking
import java.time.Instant

/**
 * Moves an entry to another source, as the Android app's MigrateMangaUseCase: reading progress,
 * history, categories, tracking, notes, the custom cover and reader settings follow when their [MigrationFlag] is on.
 */
class MigrateManga(
    private val preferenceStore: PreferenceStore,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    // The writes use the repositories, not their interactors: the interactors log and swallow a
    // failure, which would roll the transaction back without telling the migration
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterRepository: ChapterRepository,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val trackRepository: TrackRepository,
    private val refreshManga: RefreshManga,
    private val getHistory: GetHistory,
    private val handler: DatabaseHandler,
) {
    /** The Android app's "migration_flags" preference, so a restored backup keeps the choice. */
    fun flags() = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    /**
     * Migrates [current] to [target]. With [replace] the old entry leaves the library; otherwise
     * both stay (a copy). Throws when the target's chapters cannot be fetched.
     */
    suspend operator fun invoke(current: Manga, target: Manga, replace: Boolean, flags: Set<MigrationFlag> = flags().get()) {
        val targetSource = sourceManager.get(target.source) ?: error("The target source is not installed")
        val currentSource = sourceManager.get(current.source)

        refreshManga(target, fetchDetails = !target.initialized, fetchChapters = true, source = targetSource)

        // Past the network step the migration runs to the end even when its screen goes away
        // (the mouse's back button), so it never leaves half the data moved
        withNonCancellableContext { move(current, target, replace, flags, currentSource, targetSource) }
    }

    private suspend fun move(
        current: Manga,
        target: Manga,
        replace: Boolean,
        flags: Set<MigrationFlag>,
        currentSource: eu.kanade.tachiyomi.source.Source?,
        targetSource: eu.kanade.tachiyomi.source.Source,
    ) {
        // Prepare: every read and every network call comes first, so a failure here writes nothing
        var updated = emptyList<tachiyomi.domain.chapter.model.Chapter>()
        val historyUpdates = mutableListOf<HistoryUpdate>()
        if (MigrationFlag.CHAPTER in flags) {
            val previous = getChaptersByMangaId.await(current.id)
            val chapters = getChaptersByMangaId.await(target.id)
            val maxRead = previous.filter { it.read }.maxOfOrNull { it.chapterNumber }
            val history = getHistory.await(current.id).associateBy { it.chapterId }
            updated = chapters.map { chapter ->
                if (!chapter.isRecognizedNumber) return@map chapter
                val match = previous.find { it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
                when {
                    match != null -> {
                        history[match.id]?.let { h ->
                            h.readAt?.let { historyUpdates += HistoryUpdate(chapter.id, it, h.readDuration) }
                        }
                        chapter.copy(
                            read = match.read,
                            dateFetch = match.dateFetch,
                            bookmark = match.bookmark,
                            lastPageRead = match.lastPageRead,
                        )
                    }
                    maxRead != null && chapter.chapterNumber <= maxRead -> chapter.copy(read = true)
                    else -> chapter
                }
            }
        }

        val categoryIds = if (MigrationFlag.CATEGORY in flags) getCategories.await(current.id).map { it.id } else null

        val movedTracks = if (MigrationFlag.TRACK in flags) {
            val enhanced = trackerManager.trackers.filterIsInstance<EnhancedTracker>()
            getTracks.await(current.id).mapNotNull { track ->
                val moved = track.copy(mangaId = target.id)
                val service = enhanced.firstOrNull { it.isTrackFrom(moved, current, currentSource) }
                if (service != null) service.migrateTrack(moved, target, targetSource) else moved
            }
        } else {
            emptyList()
        }

        val currentUpdate = MangaUpdate(id = current.id, favorite = false, dateAdded = 0).takeIf { replace }
        val targetUpdate = MangaUpdate(
            id = target.id,
            favorite = true,
            chapterFlags = current.chapterFlags.takeIf { MigrationFlag.EXTRA in flags },
            viewerFlags = current.viewerFlags.takeIf { MigrationFlag.EXTRA in flags },
            dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
            notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            coverLastModified = Instant.now().toEpochMilli().takeIf { MigrationFlag.CUSTOM_COVER in flags },
        )

        // Write: the database steps are one transaction, so a failing step leaves both entries as
        // they were and throws, which stops the migration before the downloads below
        handler.await(inTransaction = true) {
            if (MigrationFlag.CHAPTER in flags) {
                chapterRepository.updateAll(updated.map { it.toChapterUpdate() })
                historyUpdates.forEach { historyQueries.upsert(it.chapterId, it.readAt, it.sessionReadDuration) }
            }
            if (categoryIds != null) mangaRepository.setMangaCategories(target.id, categoryIds)
            if (movedTracks.isNotEmpty()) trackRepository.insertAll(movedTracks)
            check(mangaRepository.updateAll(listOfNotNull(currentUpdate, targetUpdate))) { "Moving the library entry failed" }
        }

        if (MigrationFlag.CUSTOM_COVER in flags) {
            // Written whole before it replaces the target's cover, under the custom covers' lock
            zoukan.desktop.manga.CustomCovers.copy(current.id, target.id)
        }

        // Last, once every database step succeeded: a failure above leaves the user on the old
        // entry, which must still have its downloads
        if (MigrationFlag.REMOVE_DOWNLOAD in flags && replace) {
            try {
                // The same folder (a source with the same name, the same title): its chapters are
                // the target's downloads now, and deleting them would lose the target's
                if (downloadManager.sharesDownloadFolder(current, target)) {
                    logcat(LogPriority.INFO) { "Kept the downloads of ${current.title}: the new entry uses the same folder" }
                    return
                }
                downloadManager.delete(current, getChaptersByMangaId.await(current.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The entry has moved; leftover downloads are only disk space
                logcat(LogPriority.WARN, e) { "Deleting the downloads of ${current.title} after migrating failed" }
            }
        }
    }

    companion object {
        fun label(flag: MigrationFlag): StringResource = when (flag) {
            MigrationFlag.CHAPTER -> DMR.strings.migration_flag_chapters
            MigrationFlag.CATEGORY -> DMR.strings.migration_flag_categories
            MigrationFlag.TRACK -> DMR.strings.migration_flag_tracking
            MigrationFlag.CUSTOM_COVER -> DMR.strings.migration_flag_custom_cover
            MigrationFlag.NOTES -> DMR.strings.migration_flag_notes
            MigrationFlag.REMOVE_DOWNLOAD -> MR.strings.migrationConfigScreen_removeDownloadsTitle
            MigrationFlag.EXTRA -> DMR.strings.migration_flag_extra
        }
    }
}
