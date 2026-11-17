package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTitle
import exh.source.MERGED_SOURCE_ID

/**
 * A library-sized backup for restore timing: the synthetic backup's categories, settings, stores and
 * saved searches, with [entries] generated entries spread over its two sources. Each entry has
 * [chapters] chapters, about a fifth of them read with history, and the other per-entry data a real
 * library has (tracks, categories, excluded scanlators, metadata, custom info on a few, merged
 * entries). Deterministic, so two runs restore the same thing.
 */
object LargeBackup {

    private const val MANGADEX = 2499283573021220255L
    private const val MANGAPILL = 3014735446227467298L

    fun create(entries: Int, chapters: Int): Backup {
        val base = SyntheticBackup.create()
        val generated = (0 until entries).map { i -> entry(i, chapters) }
        // One merged entry for every 50, joining two generated entries
        val merged = (0 until entries / 50).map { m ->
            val a = generated[m * 50]
            val b = generated[m * 50 + 1]
            val url = "merged-large-$m"
            BackupManga(
                source = MERGED_SOURCE_ID,
                url = url,
                title = a.title,
                favorite = true,
                dateAdded = 1_700_300_000_000 + m,
                categories = listOf(1),
                mergedMangaReferences = listOf(
                    BackupMergedMangaReference(
                        isInfoManga = true,
                        getChapterUpdates = true,
                        chapterSortMode = 0,
                        chapterPriority = 0,
                        downloadChapters = true,
                        mergeUrl = url,
                        mangaUrl = url,
                        mangaSourceId = MERGED_SOURCE_ID,
                    ),
                    BackupMergedMangaReference(
                        isInfoManga = true,
                        getChapterUpdates = true,
                        chapterSortMode = 1,
                        chapterPriority = 1,
                        downloadChapters = false,
                        mergeUrl = url,
                        mangaUrl = a.url,
                        mangaSourceId = a.source,
                    ),
                    BackupMergedMangaReference(
                        isInfoManga = false,
                        getChapterUpdates = false,
                        chapterSortMode = 2,
                        chapterPriority = 2,
                        downloadChapters = true,
                        mergeUrl = url,
                        mangaUrl = b.url,
                        mangaSourceId = b.source,
                    ),
                ),
            )
        }
        return base.copy(backupManga = base.backupManga + generated + merged)
    }

    private fun entry(i: Int, chapters: Int): BackupManga {
        val source = if (i % 3 == 0) MANGAPILL else MANGADEX
        val url = "/manga/large-$i"
        val readUpTo = chapters / 5
        val chapterList = (0 until chapters).map { c ->
            BackupChapter(
                url = "/chapter/large-$i-$c",
                name = "Ch. ${c + 1}",
                scanlator = if (c % 4 == 0) "Group ${c % 3}" else null,
                read = c < readUpTo,
                bookmark = c % 17 == 0,
                lastPageRead = if (c == readUpTo) 7 else 0,
                dateFetch = 1_700_000_000_000 + c * 1000L,
                dateUpload = 1_690_000_000_000 + c * 86_400_000L,
                chapterNumber = c + 1f,
                sourceOrder = (chapters - 1 - c).toLong(),
            )
        }
        val history = (0 until readUpTo).map { c ->
            BackupHistory(url = "/chapter/large-$i-$c", lastRead = 1_700_001_000_000 + c * 60_000L, readDuration = 300_000L + c)
        }
        val tracking = buildList {
            if (i % 2 == 0) {
                add(
                    BackupTracking(
                        syncId = 2, libraryId = 10_000L + i, trackingUrl = "https://anilist.co/manga/$i",
                        title = "Large $i (AniList)", lastChapterRead = readUpTo.toFloat(), totalChapters = chapters,
                        score = 80f, status = 1, startedReadingDate = 1_700_000_000_000, finishedReadingDate = 0,
                        private = false, mediaId = 100_000L + i,
                    ),
                )
            }
            if (i % 5 == 0) {
                add(
                    BackupTracking(
                        syncId = 1, libraryId = 0, trackingUrl = "https://myanimelist.net/manga/$i",
                        title = "Large $i (MAL)", lastChapterRead = readUpTo.toFloat(), totalChapters = 0,
                        score = 7f, status = 2, startedReadingDate = 1_699_000_000_000,
                        finishedReadingDate = 0, private = false, mediaId = 200_000L + i,
                    ),
                )
            }
        }
        val custom = i % 20 == 0
        return BackupManga(
            source = source,
            url = url,
            title = "Large Title $i",
            artist = "Artist ${i % 40}",
            author = "Author ${i % 30}",
            description = "Description of entry $i.\nSecond line.",
            genre = listOf("Action", "Genre ${i % 7}"),
            status = 1 + i % 3,
            thumbnailUrl = "https://example.org/covers/$i.jpg",
            dateAdded = 1_700_000_000_000 + i * 1000L,
            chapters = chapterList,
            categories = when (i % 4) {
                0 -> listOf(1)
                1 -> listOf(1, 2)
                2 -> listOf(3)
                else -> emptyList()
            },
            tracking = tracking,
            favorite = i % 10 != 9,
            chapterFlags = 0x302,
            viewer_flags = 0x13,
            history = history,
            lastModifiedAt = 1_700_003_000L + i,
            excludedScanlators = if (i % 8 == 0) listOf("Group 1") else emptyList(),
            version = 1,
            initialized = true,
            flatMetadata = if (source == MANGADEX && i % 4 == 1) {
                BackupFlatMetadata(
                    searchMetadata = BackupSearchMetadata(uploader = "uploader-$i", extra = """{"mdUuid":"$i"}""", indexedExtra = "indexed", extraVersion = 3),
                    searchTags = listOf(BackupSearchTag(namespace = "genre", name = "action", type = 1)),
                    searchTitles = listOf(BackupSearchTitle(title = "Alt Title $i", type = 1)),
                )
            } else {
                null
            },
            customTitle = if (custom) "Custom Title $i" else null,
            customAuthor = if (custom) "Custom Author $i" else null,
            customStatus = if (custom) 3 else 0,
        )
    }
}
