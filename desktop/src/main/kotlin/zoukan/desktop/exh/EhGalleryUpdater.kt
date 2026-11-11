package zoukan.desktop.exh

import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.debug.DebugToggles
import exh.eh.EHentaiUpdateHelper
import exh.eh.EHentaiUpdateWorkerConstants.UPDATES_PER_ITERATION
import exh.eh.EHentaiUpdaterStats
import exh.eh.GalleryNotUpdatedException
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.tray.Notifications
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.days

/**
 * The Android app's EHentaiUpdateWorker on desktop: every "E-Hentai update interval" hours it
 * checks the library's E-Hentai galleries for new versions, up to 50 at a time, keeping the newest
 * version of each gallery. The background jobs call [runIfDue]; the favorites sync pauses it.
 */
class EhGalleryUpdater(
    private val exhPreferences: ExhPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val sourceManager: SourceManager,
    private val updateHelper: EHentaiUpdateHelper,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var paused = false

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun stats(): EHentaiUpdaterStats? = exhPreferences.exhAutoUpdateStats().get().takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.decodeFromString<EHentaiUpdaterStats>(it) }.getOrNull() }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val hours = exhPreferences.exhAutoUpdateFrequency().get()
        if (hours <= 0 || paused || !exhPreferences.isHentaiEnabled().get()) return false
        return now - (stats()?.startTime ?: 0L) >= hours * 60L * 60 * 1000
    }

    suspend fun runIfDue() {
        if (isDue()) run()
    }

    /** Runs one round now; returns false when one is already running. */
    suspend fun run(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            update()
        } finally {
            running.set(false)
        }
        return true
    }

    private suspend fun update() {
        val startTime = System.currentTimeMillis()
        val curTime = System.currentTimeMillis()
        val allMeta = try {
            loadCandidates(curTime)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Record the attempt, so a failure waits for the next interval instead of the next minute
            exhPreferences.exhAutoUpdateStats().set(Json.encodeToString(EHentaiUpdaterStats(startTime, 0, 0)))
            throw e
        }
        update(startTime, allMeta)
    }

    private suspend fun loadCandidates(curTime: Long): List<Pair<Manga, EHentaiSearchMetadata>> =
        getExhFavoriteMangaWithMetadata.await().mapNotNull { manga ->
            val raised = getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>() ?: return@mapNotNull null
            // Don't update galleries too often
            if (raised.aged ||
                (curTime - raised.lastUpdateCheck < MIN_UPDATE_FREQ && DebugToggles.RESTRICT_EXH_GALLERY_UPDATE_CHECK_FREQUENCY.enabled)
            ) {
                return@mapNotNull null
            }
            manga to raised
        }.sortedBy { it.second.lastUpdateCheck }

    private suspend fun update(startTime: Long, allMeta: List<Pair<Manga, EHentaiSearchMetadata>>) {
        val toUpdate = allMeta.take(UPDATES_PER_ITERATION)
        var failures = 0
        var updated = 0
        val updatedManga = mutableListOf<Pair<Manga, List<Chapter>>>()
        val modified = mutableSetOf<Long>()
        try {
            for ((manga, _) in toUpdate) {
                if (failures > MAX_UPDATE_FAILURES) {
                    logcat(LogPriority.WARN) { "Too many E-Hentai update failures, stopping" }
                    break
                }
                if (manga.id in modified) {
                    updated++
                    continue
                }
                val (new, chapters) = try {
                    updateEntryAndGetChapters(manga)
                } catch (e: GalleryNotUpdatedException) {
                    if (e.network) {
                        failures++
                        logcat(LogPriority.ERROR, e) { "Network error while updating gallery ${manga.id}" }
                    }
                    continue
                }
                if (chapters.isEmpty()) continue
                val (acceptedRoot, discardedRoots, exhNew) = updateHelper.findAcceptedRootAndDiscardOthers(manga.source, chapters)
                if (new.isNotEmpty() && manga.id == acceptedRoot.manga.id) {
                    libraryPreferences.newUpdatesCount().getAndSet { it + new.size }
                    updatedManga += acceptedRoot.manga to new
                } else if (exhNew.isNotEmpty() && updatedManga.none { it.first.id == acceptedRoot.manga.id }) {
                    libraryPreferences.newUpdatesCount().getAndSet { it + exhNew.size }
                    updatedManga += acceptedRoot.manga to exhNew
                }
                modified += acceptedRoot.manga.id
                modified += discardedRoots.map { it.manga.id }
                updated++
            }
        } finally {
            exhPreferences.exhAutoUpdateStats().set(Json.encodeToString(EHentaiUpdaterStats(startTime, allMeta.size, updated)))
            if (updatedManga.isNotEmpty()) {
                Notifications.post(
                    "New gallery versions",
                    updatedManga.joinToString(", ") { it.first.title }.take(300),
                )
            }
        }
    }

    private suspend fun updateEntryAndGetChapters(manga: Manga): Pair<List<Chapter>, List<Chapter>> {
        val source = sourceManager.get(manga.source) as? EHentai
            ?: throw GalleryNotUpdatedException(false, IllegalStateException("Missing E-Hentai source (${manga.source})"))
        try {
            val result = updateMangaFromRemote(source = source, manga = manga, fetchDetails = true, fetchChapters = true).getOrThrow()
            return result.newChapters to getChaptersByMangaId.await(manga.id)
        } catch (t: Throwable) {
            if (t is EHentai.GalleryNotFoundException) {
                val meta = getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // Dead galleries are aged, so they are not checked again
                    meta.aged = true
                    insertFlatMetadata.await(meta)
                }
                throw GalleryNotUpdatedException(false, t)
            }
            throw GalleryNotUpdatedException(true, t)
        }
    }

    companion object {
        private const val MAX_UPDATE_FAILURES = 5
        private val MIN_UPDATE_FREQ = 1.days.inWholeMilliseconds
    }
}
