package zoukan.desktop.track

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

/**
 * Sends reading progress to the trackers, as the Android app's TrackChapter does after a chapter
 * is read. A failed update is queued in the same store as on Android and retried by
 * [retryDelayed], which the background jobs call, in place of DelayedTrackingUpdateJob.
 */
class TrackChapters(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
    private val trackPreferences: TrackPreferences,
) {
    /** After the reader marks a chapter read, when "Update progress after reading" is on. */
    suspend fun afterReading(mangaId: Long, chapterNumber: Double) {
        if (!trackPreferences.autoUpdateTrack().get()) return
        await(mangaId, chapterNumber)
    }

    suspend fun await(mangaId: Long, chapterNumber: Double) = withNonCancellableContext {
        val tracks = getTracks.await(mangaId)
        if (tracks.isEmpty()) return@withNonCancellableContext
        coroutineScope {
            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }
                async {
                    runCatching {
                        try {
                            val updated = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastChapterRead = chapterNumber)
                            service.update(updated.toDbTrack(), true)
                            insertTrack.await(updated)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            throw e
                        }
                    }
                }
            }.awaitAll().mapNotNull { it.exceptionOrNull() }.forEach {
                logcat(LogPriority.WARN, it) { "Updating a tracker failed; it is retried later" }
            }
        }
    }

    /** Retries the queued updates; what fails stays queued. */
    suspend fun retryDelayed() {
        val queued = delayedTrackingStore.getItems()
        if (queued.isEmpty()) return
        queued.forEach { item ->
            val track = getTracks.awaitOne(item.trackId) ?: run {
                delayedTrackingStore.remove(item.trackId)
                return@forEach
            }
            // A later update already got through: nothing left to send. [await] skips it, so it
            // would otherwise stay queued and be retried for good
            if (item.lastChapterRead.toDouble() <= track.lastChapterRead) {
                delayedTrackingStore.remove(item.trackId)
                return@forEach
            }
            await(track.mangaId, item.lastChapterRead.toDouble())
        }
    }
}
