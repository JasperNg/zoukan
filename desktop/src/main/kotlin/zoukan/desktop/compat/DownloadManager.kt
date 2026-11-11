// ZOUKAN -->
package eu.kanade.tachiyomi.data.download

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** The Android DownloadManager calls the shared merged-source code makes, on the desktop's one. */
class DownloadManager(private val downloads: zoukan.desktop.download.DownloadManager) {
    fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        downloads.enqueue(manga, chapters)
    }
}
// ZOUKAN <--
