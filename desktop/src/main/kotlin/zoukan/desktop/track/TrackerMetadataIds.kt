package zoukan.desktop.track

import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.metadata.metadata.base.TrackerIdMetadata

// SY -->
/** The tracker ids a source's metadata can hold (MangaDex's links), as SY's getTrackerIdFromMetadata. */
object TrackerMetadataIds {

    // TrackerManager's ids for MyAnimeList and MangaUpdates, which it has no constants for
    internal const val MY_ANIME_LIST = 1L
    internal const val MANGA_UPDATES = 7L

    /** The entry's id on [trackerId] in [metadata], or null when it holds none for that tracker. */
    fun idFor(trackerId: Long, metadata: TrackerIdMetadata?): String? {
        metadata ?: return null
        return when (trackerId) {
            TrackerManager.ANILIST -> metadata.anilistId
            TrackerManager.KITSU -> metadata.kitsuId
            MY_ANIME_LIST -> metadata.myAnimeListId
            MANGA_UPDATES -> metadata.mangaUpdatesId
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
// SY <--
