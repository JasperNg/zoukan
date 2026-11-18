package zoukan.desktop.property

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
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTitle
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlin.random.Random

/**
 * Random backups in the shared format: entries with chapters, categories, history, tracking and
 * the fork's own fields (merged entries, metadata, custom info, saved searches, feeds, extension
 * stores), each optional field sometimes set and sometimes left out, numbers at their edges.
 *
 * Text is valid Unicode only: everything in a backup comes from SQLite or the preferences files,
 * which keep text as UTF-8 and cannot hold half a surrogate pair.
 */
class BackupArbs(private val r: Random) {

    fun backup(): Backup = Backup(
        backupManga = list(3) { manga() },
        backupCategories = list(3) { category() },
        backupSources = list(2) { BackupSource(name = text(), sourceId = long()) },
        backupPreferences = list(4) { preference() },
        backupSourcePreferences = list(2) { BackupSourcePreferences(text(), list(3) { preference() }) },
        backupExtensionStores = list(2) { extensionStore() },
        backupSavedSearches = list(2) { savedSearch() },
        backupFeeds = list(2) { BackupFeed(source = long(), global = r.nextBoolean(), savedSearch = maybe { savedSearch() }) },
    )

    private fun manga() = BackupManga(
        source = long(),
        url = text(),
        title = text(),
        artist = maybe { text() },
        author = maybe { text() },
        description = maybe { text() },
        genre = list(3) { text() },
        status = int(),
        thumbnailUrl = maybe { text() },
        dateAdded = long(),
        viewer = int(),
        chapters = list(4) { chapter() },
        categories = list(3) { long() },
        tracking = list(2) { tracking() },
        favorite = r.nextBoolean(),
        chapterFlags = int(),
        viewer_flags = maybe { int() },
        history = list(3) { BackupHistory(url = text(), lastRead = long(), readDuration = long()) },
        updateStrategy = UpdateStrategy.entries.random(r),
        lastModifiedAt = long(),
        favoriteModifiedAt = maybe { long() },
        excludedScanlators = list(2) { text() },
        version = long(),
        notes = text(),
        initialized = r.nextBoolean(),
        memo = bytes(),
        mergedMangaReferences = list(2) { mergedReference() },
        flatMetadata = maybe { flatMetadata() },
        customStatus = int(),
        customThumbnailUrl = maybe { text() },
        customTitle = maybe { text() },
        customArtist = maybe { text() },
        customAuthor = maybe { text() },
        customDescription = maybe { text() },
        customGenre = maybe { list(3) { text() } },
    )

    private fun chapter() = BackupChapter(
        url = text(),
        name = text(),
        scanlator = maybe { text() },
        read = r.nextBoolean(),
        bookmark = r.nextBoolean(),
        lastPageRead = long(),
        dateFetch = long(),
        dateUpload = long(),
        chapterNumber = float(),
        sourceOrder = long(),
        lastModifiedAt = long(),
        version = long(),
        memo = bytes(),
    )

    @Suppress("DEPRECATION")
    private fun tracking() = BackupTracking(
        syncId = int(),
        libraryId = long(),
        mediaIdInt = int(),
        trackingUrl = text(),
        title = text(),
        lastChapterRead = float(),
        totalChapters = int(),
        score = float(),
        status = int(),
        startedReadingDate = long(),
        finishedReadingDate = long(),
        private = r.nextBoolean(),
        mediaId = long(),
    )

    private fun category() = BackupCategory(name = text(), order = long(), id = long(), flags = long(), hidden = r.nextBoolean())

    private fun preference() = BackupPreference(key = text(), value = preferenceValue())

    private fun preferenceValue(): PreferenceValue = when (r.nextInt(6)) {
        0 -> IntPreferenceValue(int())
        1 -> LongPreferenceValue(long())
        2 -> FloatPreferenceValue(float())
        3 -> StringPreferenceValue(text())
        4 -> BooleanPreferenceValue(r.nextBoolean())
        else -> StringSetPreferenceValue(list(4) { text() }.toSet())
    }

    private fun extensionStore() = BackupExtensionStore(
        indexUrl = text(),
        name = text(),
        badgeLabel = maybe { text() },
        signingKey = text(),
        contactWebsite = text(),
        contactDiscord = maybe { text() },
        isLegacy = maybe { r.nextBoolean() },
        extensionListUrl = maybe { text() },
    )

    private fun savedSearch() = BackupSavedSearch(name = text(), query = text(), filterList = text(), source = long())

    private fun mergedReference() = BackupMergedMangaReference(
        isInfoManga = r.nextBoolean(),
        getChapterUpdates = r.nextBoolean(),
        chapterSortMode = int(),
        chapterPriority = int(),
        downloadChapters = r.nextBoolean(),
        mergeUrl = text(),
        mangaUrl = text(),
        mangaSourceId = long(),
    )

    private fun flatMetadata() = BackupFlatMetadata(
        searchMetadata = BackupSearchMetadata(uploader = maybe { text() }, extra = text(), indexedExtra = maybe { text() }, extraVersion = int()),
        searchTags = list(3) { BackupSearchTag(namespace = maybe { text() }, name = text(), type = int()) },
        searchTitles = list(2) { BackupSearchTitle(title = text(), type = int()) },
    )

    private fun text(): String = NastyStrings.next(r, allowLoneSurrogates = false, maxPieces = 4, padding = r.nextInt(10) == 0)

    private fun int(): Int = when (r.nextInt(6)) {
        0 -> 0
        1 -> Int.MIN_VALUE
        2 -> Int.MAX_VALUE
        3 -> -1
        else -> r.nextInt()
    }

    private fun long(): Long = when (r.nextInt(6)) {
        0 -> 0L
        1 -> Long.MIN_VALUE
        2 -> Long.MAX_VALUE
        3 -> -1L
        else -> r.nextLong()
    }

    private fun float(): Float = when (r.nextInt(8)) {
        0 -> 0f
        1 -> -0f
        2 -> Float.NaN
        3 -> Float.POSITIVE_INFINITY
        4 -> Float.MIN_VALUE
        5 -> Float.MAX_VALUE
        else -> r.nextFloat() * 2000 - 1000
    }

    private fun bytes(): ByteArray = when (r.nextInt(4)) {
        0 -> byteArrayOf()
        1 -> "{}".toByteArray()
        2 -> """{"note":"${NastyStrings.next(r, allowLoneSurrogates = false, maxPieces = 2, padding = false).replace("\\", "").replace("\"", "")}"}""".toByteArray()
        else -> r.nextBytes(r.nextInt(0, 40))
    }

    private fun <T> list(max: Int, item: () -> T): List<T> = List(r.nextInt(0, max + 1)) { item() }

    private fun <T> maybe(value: () -> T): T? = if (r.nextInt(3) == 0) null else value()
}
