package zoukan.desktop.exh

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.mangaDexSourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MangaDex's two library jobs, as the Android app's LibraryUpdateJob runs them for the
 * SYNC_FOLLOWS and PUSH_FAVORITES targets (Settings > MangaDex):
 *
 * - [syncFollows] adds the MangaDex follows with the chosen statuses to the library, with their
 *   metadata.
 * - [pushFavorites] follows on MangaDex (as "Reading") every MangaDex library entry that MDList
 *   has as unfollowed.
 */
class MangaDexSync(
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    private val getManga: GetManga,
    private val getFavorites: GetFavorites,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
) {
    /** The running job's progress: the entry's title, how many are done and how many there are. */
    data class Progress(val title: String, val done: Int, val total: Int)

    private val guard = AtomicBoolean(false)
    private val _running = MutableStateFlow(false)

    /** True from the start of a job (before its first network call) until it ends. */
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    val isRunning: Boolean get() = guard.get()

    /** Adds the follows with the statuses in mangadexSyncToLibraryIndexes. Returns how many it added or updated. */
    suspend fun syncFollows(): Int = exclusive {
        val mangaDex = MdUtil.getEnabledMangaDex(sourcePreferences, sourceManager) ?: error("No MangaDex source is enabled")
        val statuses = sourcePreferences.mangadexSyncToLibraryIndexes().get().mapNotNull { it.toIntOrNull() }
        val follows = mangaDex.fetchAllFollows().filter { (_, metadata) -> metadata.followStatus in statuses }
        follows.forEachIndexed { index, (networkManga, metadata) ->
            ensureActive()
            _progress.value = Progress(networkManga.title, index, follows.size)
            var dbManga = getManga.await(networkManga.url, mangaDex.id)
            if (dbManga == null) {
                dbManga = networkToLocalManga(
                    Manga.create().copy(
                        url = networkManga.url,
                        ogTitle = networkManga.title,
                        source = mangaDex.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                    ),
                )
            } else if (!dbManga.favorite) {
                updateManga.awaitUpdateFavorite(dbManga.id, true)
            }
            updateMangaFromRemote(manga = dbManga, manualFetch = true)
            metadata.mangaId = dbManga.id
            insertFlatMetadata.await(metadata)
        }
        follows.size
    }

    /** Follows the unfollowed MangaDex library entries. Returns how many it went through. */
    suspend fun pushFavorites(): Int = exclusive {
        val mdList = trackerManager.mdList
        if (!mdList.isLoggedIn) error("Sign in to MangaDex first")
        val entries = getFavorites.await().filter { it.source in mangaDexSourceIds }
        entries.forEachIndexed { index, manga ->
            ensureActive()
            _progress.value = Progress(manga.title, index, entries.size)
            var track = getTracks.await(manga.id).firstOrNull { it.trackerId == TrackerManager.MDLIST }
                ?: mdList.createInitialTracker(manga).toDomainTrack(idRequired = false)
            if (track?.status == FollowStatus.UNFOLLOWED.long) {
                track = track.copy(status = FollowStatus.READING.long)
                val updated = mdList.update(track.toDbTrack())
                insertTrack.await(updated.toDomainTrack(false)!!)
            }
        }
        entries.size
    }

    private suspend fun exclusive(block: suspend kotlinx.coroutines.CoroutineScope.() -> Int): Int {
        if (!guard.compareAndSet(false, true)) error("A MangaDex sync is already running")
        _running.value = true
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaDex sync failed" }
            throw e
        } finally {
            _progress.value = null
            _running.value = false
            guard.set(false)
        }
    }
}
