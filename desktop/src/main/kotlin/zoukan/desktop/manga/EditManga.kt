package zoukan.desktop.manga

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.Images
import java.io.File
import java.time.Instant

/**
 * Edits an entry's details, notes and cover, as the Android app's edit dialog and cover screen:
 * a library entry keeps its edits beside the source's values (custom info and covers/custom), so
 * a refresh does not undo them; a local entry's edits go into its folder.
 */
class EditManga(
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val updateManga: UpdateManga,
    private val sourceManager: SourceManager,
) {
    data class Info(
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val genres: List<String>,
        val status: Long,
        /** SY's thumbnail URL; null leaves it as it is */
        val thumbnailUrl: String? = null,
    )

    suspend fun saveInfo(manga: Manga, info: Info) {
        if (manga.source == LocalSource.ID) {
            val sManga = manga.toSManga().apply {
                title = info.title.trim().ifBlank { manga.url }
                author = info.author.trimOrNull()
                artist = info.artist.trimOrNull()
                description = info.description.trimOrNull()
                genre = info.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
                status = info.status.toInt()
            }
            (sourceManager.get(LocalSource.ID) as? LocalSource)?.mangaDir(manga.url)?.let { dir ->
                File(dir, ComicInfoXml.FILE).writeText(ComicInfoXml.of(sManga).write())
            }
            updateManga.await(
                MangaUpdate(
                    id = manga.id,
                    title = sManga.title,
                    author = sManga.author,
                    artist = sManga.artist,
                    thumbnailUrl = info.thumbnailUrl?.trimOrNull()?.takeIf { it != manga.thumbnailUrl },
                    description = sManga.description,
                    genre = info.genres,
                    status = info.status,
                ),
            )
            return
        }
        // Only what differs from the source's values is kept, as on Android
        setCustomMangaInfo.set(
            CustomMangaInfo(
                id = manga.id,
                title = info.title.trimOrNull()?.takeIf { it != manga.ogTitle },
                author = info.author.trimOrNull()?.takeIf { it != manga.ogAuthor },
                artist = info.artist.trimOrNull()?.takeIf { it != manga.ogArtist },
                thumbnailUrl = info.thumbnailUrl?.trimOrNull()?.takeIf { it != manga.ogThumbnailUrl },
                description = info.description.trimOrNull()?.takeIf { it != manga.ogDescription },
                genre = info.genres.takeIf { it.isNotEmpty() && it != manga.ogGenre },
                status = info.status.takeUnless { it == manga.ogStatus },
            ),
        )
        // The entry is read again with its custom info when its row changes
        updateManga.await(MangaUpdate(id = manga.id, lastUpdate = manga.lastUpdate + 1))
    }

    /** Puts back the source's values. */
    suspend fun resetInfo(manga: Manga) {
        setCustomMangaInfo.set(CustomMangaInfo(manga.id, null))
        updateManga.await(MangaUpdate(id = manga.id, lastUpdate = manga.lastUpdate + 1))
    }

    suspend fun saveNotes(manga: Manga, notes: String) {
        updateManga.await(MangaUpdate(id = manga.id, notes = notes.trim()))
    }

    /** Whether the entry can have a custom cover: library entries and local entries, as on Android. */
    fun canEditCover(manga: Manga) = manga.favorite || manga.source == LocalSource.ID

    fun hasCustomCover(manga: Manga) = manga.source != LocalSource.ID && Images.customCoverFile(manga.id).isFile

    suspend fun setCover(manga: Manga, image: File) {
        if (manga.source == LocalSource.ID) {
            val dir = (sourceManager.get(LocalSource.ID) as? LocalSource)?.mangaDir(manga.url) ?: return
            LocalSource.findCover(dir)?.delete()
            val cover = File(dir, "cover.${image.extension.lowercase().ifBlank { "jpg" }}")
            image.copyTo(cover, overwrite = true)
            updateManga.await(MangaUpdate(id = manga.id, thumbnailUrl = cover.toURI().toString()))
        } else {
            if (!manga.favorite) return
            val target = Images.customCoverFile(manga.id)
            target.parentFile.mkdirs()
            image.copyTo(target, overwrite = true)
        }
        updateManga.await(MangaUpdate(id = manga.id, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun deleteCustomCover(manga: Manga) {
        Images.customCoverFile(manga.id).delete()
        updateManga.await(MangaUpdate(id = manga.id, coverLastModified = Instant.now().toEpochMilli()))
    }

    private fun String.trimOrNull() = trim().ifBlank { null }
}
