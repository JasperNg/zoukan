package zoukan.desktop.manga

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.compat.localized
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.download.ImageType
import zoukan.desktop.image.MemoryImageIo
import zoukan.desktop.local.LocalSource
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    fun hasCustomCover(manga: Manga) = manga.source != LocalSource.ID && CustomCovers.file(manga.id).isFile

    suspend fun setCover(manga: Manga, image: File) = setCover(manga, image.readBytes())

    /** Sets the cover from [image]'s bytes, held in memory only (a page of an encrypted chapter included). */
    suspend fun setCover(manga: Manga, image: ByteArray) {
        if (manga.source == LocalSource.ID) {
            val local = sourceManager.get(LocalSource.ID) as? LocalSource ?: return
            val dir = local.mangaDir(manga.url) ?: return
            val bytes = coverImage(image)
            val old = LocalSource.findCover(dir)
            // The new cover is in place before the old one goes, so a failed write keeps the cover.
            // An encrypted entry's cover (cover.cbi, or encrypted chapters) is written encrypted;
            // without the password it is refused, never written in plain
            val cover = local.writeCover(dir, bytes, encrypted = local.coverMustBeEncrypted(dir))
            // The old cover can be the new one under another letter case (Cover.JPG, cover.jpg) on a
            // file system that ignores case: then the move replaced it, and deleting it would delete
            // the new cover. When the two cannot be compared, the old one is kept.
            val replaced = old != null && runCatching { Files.isSameFile(old.toPath(), cover.toPath()) }.getOrDefault(true)
            if (old != null && !replaced && !old.delete()) {
                logcat(LogPriority.WARN) { "Could not delete the old cover ${old.name} of a local entry" }
            }
            updateManga.await(MangaUpdate(id = manga.id, thumbnailUrl = cover.toURI().toString()))
        } else {
            if (!manga.favorite) return
            // ZOUKAN: kept encrypted with the file key
            CustomCovers.save(manga.id, image)
        }
        updateManga.await(MangaUpdate(id = manga.id, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun deleteCustomCover(manga: Manga) {
        // ZOUKAN: under the custom covers' lock, so a lazy rewrite cannot put it back
        CustomCovers.delete(manga.id)
        updateManga.await(MangaUpdate(id = manga.id, coverLastModified = Instant.now().toEpochMilli()))
    }

    private fun String.trimOrNull() = trim().ifBlank { null }

    /**
     * The picked image's [bytes] as a local cover: as it is when it is a type both apps find as a cover
     * (by its content, not its file name), a PNG when it is another image Java reads (BMP, TIFF),
     * and an error when it is no image at all.
     */
    private fun coverImage(bytes: ByteArray): ByteArray {
        if (ImageType.fromBytes(bytes) != null) return bytes
        // Exception, not Throwable: running out of memory on a huge image is not "not an image"
        val decoded = try {
            MemoryImageIo.read(bytes)
        } catch (e: Exception) {
            null
        } ?: throw IOException(localized(DMR.strings.not_an_image))
        return MemoryImageIo.png(decoded)
    }
}
