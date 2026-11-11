package zoukan.desktop.migration

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
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
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val refreshManga: RefreshManga,
    private val getHistory: GetHistory,
    private val upsertHistory: UpsertHistory,
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
        if (MigrationFlag.CHAPTER in flags) {
            val previous = getChaptersByMangaId.await(current.id)
            val chapters = getChaptersByMangaId.await(target.id)
            val maxRead = previous.filter { it.read }.maxOfOrNull { it.chapterNumber }
            val history = getHistory.await(current.id).associateBy { it.chapterId }
            val historyUpdates = mutableListOf<HistoryUpdate>()
            val updated = chapters.map { chapter ->
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
            updateChapter.awaitAll(updated.map { it.toChapterUpdate() })
            upsertHistory.awaitAll(historyUpdates)
        }

        if (MigrationFlag.CATEGORY in flags) {
            setMangaCategories.await(target.id, getCategories.await(current.id).map { it.id })
        }

        if (MigrationFlag.TRACK in flags) {
            val enhanced = trackerManager.trackers.filterIsInstance<EnhancedTracker>()
            getTracks.await(current.id).mapNotNull { track ->
                val moved = track.copy(mangaId = target.id)
                val service = enhanced.firstOrNull { it.isTrackFrom(moved, current, currentSource) }
                if (service != null) service.migrateTrack(moved, target, targetSource) else moved
            }.takeIf { it.isNotEmpty() }?.let { insertTrack.awaitAll(it) }
        }

        if (MigrationFlag.REMOVE_DOWNLOAD in flags && replace) {
            downloadManager.delete(current, getChaptersByMangaId.await(current.id))
        }

        if (MigrationFlag.CUSTOM_COVER in flags) {
            zoukan.desktop.ui.common.Images.customCoverFile(current.id).takeIf { it.isFile }?.let { cover ->
                cover.copyTo(zoukan.desktop.ui.common.Images.customCoverFile(target.id).apply { parentFile.mkdirs() }, overwrite = true)
            }
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
        updateManga.awaitAll(listOfNotNull(currentUpdate, targetUpdate))
    }

    companion object {
        fun label(flag: MigrationFlag): String = when (flag) {
            MigrationFlag.CHAPTER -> "Chapters read and history"
            MigrationFlag.CATEGORY -> "Categories"
            MigrationFlag.TRACK -> "Tracking"
            MigrationFlag.CUSTOM_COVER -> "Custom cover"
            MigrationFlag.NOTES -> "Notes"
            MigrationFlag.REMOVE_DOWNLOAD -> "Delete the old downloads"
            MigrationFlag.EXTRA -> "Chapter and reader settings"
        }
    }
}
