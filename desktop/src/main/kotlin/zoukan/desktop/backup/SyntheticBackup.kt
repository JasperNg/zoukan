package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTitle
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import exh.source.MERGED_SOURCE_ID

/**
 * A backup with every field of the format set to a value that is not its default, for the round-trip
 * test. Like the Phase 0 fixture, it is built from the format, not exported by an app; real exports
 * are tested beside it.
 */
object SyntheticBackup {

    private const val MANGADEX = 2499283573021220255L
    private const val MANGAPILL = 3014735446227467298L

    /**
     * @param legacy writes the older forms of some fields that current apps still read: `viewer`
     * without `viewer_flags`, the `Int` tracker media id, and an extension store from before stores
     * had `isLegacy`.
     */
    @Suppress("DEPRECATION")
    fun create(legacy: Boolean = false): Backup {
        val categories = listOf(
            BackupCategory(name = "Reading", order = 1, id = 11, flags = 0x40, hidden = false),
            BackupCategory(name = "Later", order = 2, id = 12, flags = 0x44, hidden = false),
            BackupCategory(name = "Hidden stash", order = 3, id = 13, flags = 0, hidden = true),
        )

        val dex = BackupManga(
            source = MANGADEX,
            url = "/manga/0aa0e3f2-0000-4000-8000-000000000001",
            title = "Synthetic Dex Title",
            artist = "Artist A",
            author = "Author A",
            description = "Line one\nLine two, with ünïcödé and emoji 📚",
            genre = listOf("Action", "Comedy", "Slice of Life"),
            status = 2,
            thumbnailUrl = "https://example.org/covers/dex.jpg",
            dateAdded = 1_700_000_000_123,
            chapters = listOf(
                BackupChapter(
                    url = "/chapter/dex-1", name = "Ch. 1 - Start", scanlator = "Group One",
                    read = true, bookmark = true, lastPageRead = 17, dateFetch = 1_700_000_100_000,
                    dateUpload = 1_699_000_000_000, chapterNumber = 1f, sourceOrder = 2,
                    memo = """{"note":"chapter memo"}""".encodeToByteArray(),
                ),
                BackupChapter(
                    url = "/chapter/dex-1.5", name = "Ch. 1.5 - Extra", scanlator = null,
                    read = false, bookmark = false, lastPageRead = 4, dateFetch = 1_700_000_200_000,
                    dateUpload = 1_699_100_000_000, chapterNumber = 1.5f, sourceOrder = 1,
                ),
                BackupChapter(
                    url = "/chapter/dex-2", name = "Ch. 2 - Middle", scanlator = "Group Two",
                    read = false, bookmark = true, lastPageRead = 0, dateFetch = 1_700_000_300_000,
                    dateUpload = 1_699_200_000_000, chapterNumber = 2f, sourceOrder = 0,
                ),
            ),
            categories = listOf(1, 2),
            tracking = listOf(
                BackupTracking(
                    syncId = 2, libraryId = 99_001, trackingUrl = "https://anilist.co/manga/1",
                    title = "Synthetic Dex Title (AniList)", lastChapterRead = 1f, totalChapters = 20,
                    score = 85f, status = 1, startedReadingDate = 1_700_000_000_000,
                    finishedReadingDate = 0, private = true,
                ).apply { if (legacy) mediaIdInt = 4242 else mediaId = 4_242_000_000L },
                BackupTracking(
                    syncId = 1, libraryId = 0, trackingUrl = "https://myanimelist.net/manga/2",
                    title = "Synthetic Dex Title (MAL)", lastChapterRead = 1.5f, totalChapters = 0,
                    score = 7f, status = 2, startedReadingDate = 1_699_000_000_000,
                    finishedReadingDate = 1_700_500_000_000, private = false, mediaId = 2,
                ),
            ),
            favorite = true,
            chapterFlags = 0x302,
            history = listOf(
                BackupHistory(url = "/chapter/dex-1", lastRead = 1_700_001_000_000, readDuration = 600_000),
                BackupHistory(url = "/chapter/dex-1.5", lastRead = 1_700_002_000_000, readDuration = 120_000),
            ),
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            lastModifiedAt = 1_700_003_000,
            favoriteModifiedAt = 1_700_004_000,
            excludedScanlators = listOf("Bad Scans", "Other Group"),
            version = 7,
            notes = "My notes about this entry",
            initialized = true,
            memo = """{"pinned":true,"rating":4}""".encodeToByteArray(),
            flatMetadata = BackupFlatMetadata(
                searchMetadata = BackupSearchMetadata(
                    uploader = "uploader-1",
                    extra = """{"mdUuid":"0aa0e3f2"}""",
                    indexedExtra = "indexed",
                    extraVersion = 3,
                ),
                searchTags = listOf(
                    BackupSearchTag(namespace = "genre", name = "action", type = 1),
                    BackupSearchTag(namespace = null, name = "untagged", type = 0),
                ),
                searchTitles = listOf(
                    BackupSearchTitle(title = "Alt Title JP", type = 1),
                    BackupSearchTitle(title = "Alt Title EN", type = 0),
                ),
            ),
            customStatus = 3,
            customThumbnailUrl = "https://example.org/covers/custom.jpg",
            customTitle = "My Custom Title",
            customArtist = "Custom Artist",
            customAuthor = "Custom Author",
            customDescription = "Custom description",
            customGenre = listOf("Custom", "Genres"),
        ).apply {
            if (legacy) viewer = 3 else viewer_flags = 0x13
        }

        val pill = BackupManga(
            source = MANGAPILL,
            url = "/manga/123/synthetic-pill",
            title = "Synthetic Pill Title",
            author = "Author B",
            genre = listOf("Drama"),
            status = 1,
            thumbnailUrl = "https://example.org/covers/pill.jpg",
            dateAdded = 1_700_100_000_000,
            chapters = listOf(
                BackupChapter(
                    url = "/chapters/123-1",
                    name = "Chapter 1",
                    read = true,
                    lastPageRead = 30,
                    dateFetch = 1_700_100_100_000,
                    dateUpload = 1_700_000_000_000,
                    chapterNumber = 1f,
                    sourceOrder = 0,
                ),
            ),
            categories = listOf(3),
            favorite = true,
            viewer_flags = 0x1,
            history = listOf(BackupHistory(url = "/chapters/123-1", lastRead = 1_700_100_200_000, readDuration = 30_000)),
        )

        // In the library's history only ("read entries"), not a favorite
        val read = BackupManga(
            source = MANGAPILL,
            url = "/manga/456/read-not-favorite",
            title = "Read But Not In Library",
            favorite = false,
            chapters = listOf(
                BackupChapter(url = "/chapters/456-1", name = "Chapter 1", read = true, chapterNumber = 1f),
            ),
            history = listOf(BackupHistory(url = "/chapters/456-1", lastRead = 1_700_200_000_000)),
        )

        val merged = BackupManga(
            source = MERGED_SOURCE_ID,
            url = "merged-synthetic-1",
            title = "Synthetic Dex Title",
            favorite = true,
            dateAdded = 1_700_300_000_000,
            categories = listOf(1),
            mergedMangaReferences = listOf(
                BackupMergedMangaReference(
                    isInfoManga = true,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeUrl = "merged-synthetic-1",
                    mangaUrl = "merged-synthetic-1",
                    mangaSourceId = MERGED_SOURCE_ID,
                ),
                BackupMergedMangaReference(
                    isInfoManga = true,
                    getChapterUpdates = true,
                    chapterSortMode = 1,
                    chapterPriority = 1,
                    downloadChapters = false,
                    mergeUrl = "merged-synthetic-1",
                    mangaUrl = dex.url,
                    mangaSourceId = MANGADEX,
                ),
                BackupMergedMangaReference(
                    isInfoManga = false,
                    getChapterUpdates = false,
                    chapterSortMode = 2,
                    chapterPriority = 2,
                    downloadChapters = true,
                    mergeUrl = "merged-synthetic-1",
                    mangaUrl = pill.url,
                    mangaSourceId = MANGAPILL,
                ),
            ),
        )

        val savedSearch = BackupSavedSearch(
            name = "Completed action",
            query = "action",
            filterList = """[{"name":"Status","state":2}]""",
            source = MANGADEX,
        )

        return Backup(
            backupManga = listOf(dex, pill, read, merged),
            backupCategories = categories,
            backupSources = listOf(
                BackupSource(name = "MangaDex", sourceId = MANGADEX),
                BackupSource(name = "MangaPill", sourceId = MANGAPILL),
                BackupSource(name = "MergedSource", sourceId = MERGED_SOURCE_ID),
            ),
            backupPreferences = listOf(
                BackupPreference("pref_library_columns_portrait_key", IntPreferenceValue(4)),
                BackupPreference("last_library_update", LongPreferenceValue(1_700_000_000_000)),
                BackupPreference("pref_reader_brightness", FloatPreferenceValue(0.75f)),
                BackupPreference("app_theme", StringPreferenceValue("MONET")),
                BackupPreference("pref_show_reading_mode", BooleanPreferenceValue(false)),
                BackupPreference("library_update_restriction", StringSetPreferenceValue(setOf("wifi", "ac"))),
                // Category preferences hold category ids, which a restore maps by name
                BackupPreference("default_category", IntPreferenceValue(12)),
                BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("11", "13"))),
                BackupPreference("pinned_catalogues", StringSetPreferenceValue(setOf(MANGADEX.toString()))),
                // Private settings, kept only when a backup includes them
                BackupPreference("__PRIVATE_pref_mangasync_username_2", StringPreferenceValue("synthetic-user")),
            ),
            backupSourcePreferences = listOf(
                BackupSourcePreferences(
                    "source_$MANGADEX",
                    listOf(
                        BackupPreference("dataSaver", BooleanPreferenceValue(true)),
                        BackupPreference("coverQuality", StringPreferenceValue(".512.jpg")),
                        BackupPreference("contentRating", StringSetPreferenceValue(setOf("safe", "suggestive"))),
                        BackupPreference("tryUsingFirstVolumeCover", IntPreferenceValue(1)),
                    ),
                ),
                BackupSourcePreferences(
                    "source_$MANGAPILL",
                    listOf(BackupPreference("rateLimit", LongPreferenceValue(5))),
                ),
            ),
            backupExtensionStores = buildList {
                add(
                    BackupExtensionStore(
                        indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
                        name = "Keiyoushi",
                        badgeLabel = "KEI",
                        signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                        contactWebsite = "https://keiyoushi.github.io",
                        contactDiscord = "https://discord.gg/keiyoushi",
                        isLegacy = false,
                        extensionListUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
                    ),
                )
                if (legacy) {
                    add(
                        BackupExtensionStore(
                            indexUrl = "https://example.org/old-repo/index.min.json",
                            name = "Old repo",
                            badgeLabel = null,
                            signingKey = "0".repeat(64),
                            contactWebsite = "https://example.org",
                            contactDiscord = null,
                            isLegacy = null,
                            extensionListUrl = null,
                        ),
                    )
                }
            },
            backupSavedSearches = listOf(
                savedSearch,
                BackupSavedSearch(name = "Query only", query = "isekai", filterList = "", source = MANGAPILL),
            ),
            backupFeeds = listOf(
                BackupFeed(source = MANGADEX, global = true, savedSearch = null),
                BackupFeed(source = MANGADEX, global = false, savedSearch = savedSearch),
                BackupFeed(source = MANGAPILL, global = true, savedSearch = savedSearch.copy(source = MANGAPILL, name = "Pill search")),
            ),
        )
    }
}
