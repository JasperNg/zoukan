// ZOUKAN -->
@file:JvmName("MangaModelCompat")

package eu.kanade.domain.manga.model

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.domain.toSManga as desktopToSManga

/** From the Android app's domain Manga extensions. */
fun Manga.toSManga(): SManga = desktopToSManga()

fun Manga.copyFrom(other: SManga): Manga = copy(
    ogAuthor = other.author ?: ogAuthor,
    ogArtist = other.artist ?: ogArtist,
    ogThumbnailUrl = other.thumbnail_url ?: ogThumbnailUrl,
    ogDescription = other.description ?: ogDescription,
    ogGenre = if (other.genre != null) other.getGenres() else ogGenre,
    ogStatus = other.status.toLong(),
    updateStrategy = other.update_strategy,
    initialized = other.initialized && initialized,
    memo = other.memo,
)
// ZOUKAN <--
