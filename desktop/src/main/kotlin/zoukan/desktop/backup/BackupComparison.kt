package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Compares a backup with the backup made after restoring it, field by field, and lists what the
 * round trip lost or changed.
 *
 * What a restore is expected to change is not a difference:
 * - Database ids: categories, and the category ids in category preferences and in each entry's
 *   category list, are compared by category name.
 * - Order: lists are compared as sets, keyed by what identifies an item (an entry by source and
 *   URL, a chapter by URL, a track by tracker).
 * - Fields the database keeps for sync, rewritten on every restore on Android too: an entry's and
 *   a chapter's `lastModifiedAt` and `version`, and an entry's `favoriteModifiedAt`. They are
 *   listed in [Report.notCompared].
 * - Normalizations the Android restore applies: a legacy extension store URL gets `/repo.json`,
 *   a missing badge label becomes the store name, `viewer` moves into `viewer_flags`, an `Int`
 *   tracker media id becomes the `Long` one, and empty saved-search filters become `[]`.
 * - What the restore skips on purpose, as Android does: history for chapters the backup does not
 *   have.
 *
 * Extra items in the second backup are not differences (the profile may hold more).
 */
class BackupComparison(private val before: Backup, private val after: Backup) {

    data class Report(
        val differences: List<String>,
        /** How many values were compared, per part of the backup. */
        val compared: Map<String, Int>,
        val notCompared: List<String> = NOT_COMPARED,
    ) {
        val passed: Boolean get() = differences.isEmpty()
    }

    private val differences = mutableListOf<String>()
    private val compared = linkedMapOf<String, Int>()

    private fun count(part: String, n: Int = 1) {
        compared[part] = (compared[part] ?: 0) + n
    }

    private fun <T> check(part: String, where: String, field: String, expected: T, actual: T) {
        count(part)
        if (expected != actual) differences += "$where: $field was $expected, is $actual"
    }

    private fun missing(where: String, what: String) {
        differences += "$where: $what is missing"
    }

    fun compare(): Report {
        compareCategories()
        compareManga()
        compareSources()
        comparePreferences()
        compareSourcePreferences()
        compareExtensionStores()
        compareSavedSearches()
        compareFeeds()
        return Report(differences.toList(), compared.toMap())
    }

    private fun categoryNamesByOrder(backup: Backup) = backup.backupCategories.associate { it.order to it.name }
    private fun categoryNamesById(backup: Backup) = backup.backupCategories.associate { it.id.toString() to it.name }

    private fun compareCategories() {
        val afterByName = after.backupCategories.associateBy { it.name }
        before.backupCategories.forEach { category ->
            val where = "Category \"${category.name}\""
            val other = afterByName[category.name] ?: return@forEach missing(where, "the category")
            check("categories", where, "flags", category.flags, other.flags)
            check("categories", where, "hidden", category.hidden, other.hidden)
        }
        // Relative order is kept, though the numbers may shift
        val beforeOrder = before.backupCategories.sortedBy(BackupCategory::order).map { it.name }
        val afterOrder = after.backupCategories.sortedBy(BackupCategory::order).map { it.name }
            .filter { it in beforeOrder }
        check("categories", "Categories", "order", beforeOrder, afterOrder)
    }

    private fun compareManga() {
        val afterByKey = after.backupManga.associateBy { it.source to it.url }
        val beforeCategories = categoryNamesByOrder(before)
        val afterCategories = categoryNamesByOrder(after)
        before.backupManga.forEach { manga ->
            val where = "Entry \"${manga.title}\" (${manga.source} ${manga.url})"
            val other = afterByKey[manga.source to manga.url] ?: return@forEach missing(where, "the entry")
            compareMangaFields(where, manga, other)
            check(
                "entries",
                where,
                "categories",
                manga.categories.mapNotNull(beforeCategories::get).toSet(),
                other.categories.mapNotNull(afterCategories::get).toSet(),
            )
            compareChapters(where, manga, other)
            compareTracking(where, manga.tracking, other.tracking)
            compareHistory(where, manga, other)
        }
    }

    @Suppress("DEPRECATION")
    private fun compareMangaFields(where: String, a: BackupManga, b: BackupManga) {
        val p = "entries"
        check(p, where, "title", a.title, b.title)
        check(p, where, "artist", a.artist, b.artist)
        check(p, where, "author", a.author, b.author)
        check(p, where, "description", a.description, b.description)
        check(p, where, "genre", a.genre, b.genre)
        check(p, where, "status", a.status, b.status)
        check(p, where, "thumbnailUrl", a.thumbnailUrl, b.thumbnailUrl)
        check(p, where, "dateAdded", a.dateAdded, b.dateAdded)
        check(p, where, "viewer_flags", a.viewer_flags ?: a.viewer, b.viewer_flags ?: b.viewer)
        check(p, where, "favorite", a.favorite, b.favorite)
        check(p, where, "chapterFlags", a.chapterFlags, b.chapterFlags)
        check(p, where, "updateStrategy", a.updateStrategy, b.updateStrategy)
        check(p, where, "excludedScanlators", a.excludedScanlators.toSet(), b.excludedScanlators.toSet())
        check(p, where, "notes", a.notes, b.notes)
        check(p, where, "initialized", a.initialized, b.initialized)
        check(p, where, "memo", a.memo.decodeToString(), b.memo.decodeToString())
        // SY
        check(p, where, "mergedMangaReferences", a.mergedMangaReferences.toSet(), b.mergedMangaReferences.toSet())
        check(
            p,
            where,
            "flatMetadata",
            a.flatMetadata?.let { it.copy(searchTags = it.searchTags.sortedBy(Any::toString), searchTitles = it.searchTitles.sortedBy(Any::toString)) },
            b.flatMetadata?.let { it.copy(searchTags = it.searchTags.sortedBy(Any::toString), searchTitles = it.searchTitles.sortedBy(Any::toString)) },
        )
        check(p, where, "customStatus", a.customStatus, b.customStatus)
        check(p, where, "customThumbnailUrl", a.customThumbnailUrl, b.customThumbnailUrl)
        check(p, where, "customTitle", a.customTitle, b.customTitle)
        check(p, where, "customArtist", a.customArtist, b.customArtist)
        check(p, where, "customAuthor", a.customAuthor, b.customAuthor)
        check(p, where, "customDescription", a.customDescription, b.customDescription)
        check(p, where, "customGenre", a.customGenre, b.customGenre)
    }

    private fun compareChapters(where: String, a: BackupManga, b: BackupManga) {
        val p = "chapters"
        val afterByUrl = b.chapters.associateBy { it.url }
        a.chapters.forEach { chapter ->
            val w = "$where, chapter ${chapter.url}"
            val other = afterByUrl[chapter.url] ?: return@forEach missing(w, "the chapter")
            check(p, w, "name", chapter.name, other.name)
            check(p, w, "scanlator", chapter.scanlator, other.scanlator)
            check(p, w, "read", chapter.read, other.read)
            check(p, w, "bookmark", chapter.bookmark, other.bookmark)
            check(p, w, "lastPageRead", chapter.lastPageRead, other.lastPageRead)
            check(p, w, "dateFetch", chapter.dateFetch, other.dateFetch)
            check(p, w, "dateUpload", chapter.dateUpload, other.dateUpload)
            check(p, w, "chapterNumber", chapter.chapterNumber, other.chapterNumber)
            check(p, w, "sourceOrder", chapter.sourceOrder, other.sourceOrder)
            check(p, w, "memo", chapter.memo.decodeToString(), other.memo.decodeToString())
        }
    }

    @Suppress("DEPRECATION")
    private fun compareTracking(where: String, a: List<BackupTracking>, b: List<BackupTracking>) {
        val p = "tracking"
        val afterByTracker = b.associateBy { it.syncId }
        a.forEach { track ->
            val w = "$where, tracker ${track.syncId}"
            val other = afterByTracker[track.syncId] ?: return@forEach missing(w, "the track")
            fun BackupTracking.remoteId() = if (mediaIdInt != 0) mediaIdInt.toLong() else mediaId
            check(p, w, "mediaId", track.remoteId(), other.remoteId())
            check(p, w, "libraryId", track.libraryId, other.libraryId)
            check(p, w, "trackingUrl", track.trackingUrl, other.trackingUrl)
            check(p, w, "title", track.title, other.title)
            check(p, w, "lastChapterRead", track.lastChapterRead, other.lastChapterRead)
            check(p, w, "totalChapters", track.totalChapters, other.totalChapters)
            check(p, w, "score", track.score, other.score)
            check(p, w, "status", track.status, other.status)
            check(p, w, "startedReadingDate", track.startedReadingDate, other.startedReadingDate)
            check(p, w, "finishedReadingDate", track.finishedReadingDate, other.finishedReadingDate)
            check(p, w, "private", track.private, other.private)
        }
    }

    private fun compareHistory(where: String, a: BackupManga, b: BackupManga) {
        val p = "history"
        val chapterUrls = a.chapters.map { it.url }.toSet()
        val afterByUrl = b.history.associateBy { it.url }
        a.history
            .filter { it.url in chapterUrls }
            .forEach { history ->
                val w = "$where, history ${history.url}"
                val other = afterByUrl[history.url] ?: return@forEach missing(w, "the history entry")
                check(p, w, "lastRead", history.lastRead, other.lastRead)
                check(p, w, "readDuration", history.readDuration, other.readDuration)
            }
    }

    private fun compareSources() {
        val afterById = after.backupSources.associateBy { it.sourceId }
        before.backupSources.forEach { source ->
            val where = "Source ${source.sourceId}"
            val other = afterById[source.sourceId] ?: return@forEach missing(where, "the source")
            if (source.name.isNotBlank() && source.name != source.sourceId.toString()) {
                check("sources", where, "name", source.name, other.name)
            }
        }
    }

    private fun comparePreferences() {
        val categoryKeys = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        val beforeCategories = categoryNamesById(before)
        val afterCategories = categoryNamesById(after)
        fun PreferenceValue.categoryNames(names: Map<String, String>): Any = when (this) {
            is StringSetPreferenceValue -> value.mapNotNull(names::get).toSet()
            is eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue -> names[value.toString()] ?: value
            else -> this
        }
        compareKeyed("app settings", "Setting", before.backupPreferences, after.backupPreferences) { key, a, b ->
            if (key in categoryKeys) a.categoryNames(beforeCategories) to b.categoryNames(afterCategories) else a to b
        }
    }

    private fun compareSourcePreferences() {
        val afterByKey = after.backupSourcePreferences.associateBy { it.sourceKey }
        before.backupSourcePreferences.forEach { source ->
            val where = "Source settings ${source.sourceKey}"
            val other = afterByKey[source.sourceKey] ?: return@forEach missing(where, "the settings")
            compareKeyed("source settings", where, source.prefs, other.prefs) { _, a, b -> a to b }
        }
    }

    private fun compareKeyed(
        part: String,
        where: String,
        a: List<BackupPreference>,
        b: List<BackupPreference>,
        normalize: (String, PreferenceValue, PreferenceValue) -> Pair<Any, Any>,
    ) {
        val afterByKey = b.associateBy { it.key }
        a.forEach { pref ->
            val w = "$where ${pref.key}"
            val other = afterByKey[pref.key] ?: return@forEach missing(w, "the setting")
            val (x, y) = normalize(pref.key, pref.value, other.value)
            check(part, w, "value", x, y)
        }
    }

    private fun BackupExtensionStore.normalized(): List<Any?> {
        val url = if (isLegacy == null) {
            indexUrl.removeSuffix("/index.min.json").removeSuffix("/index.json").removeSuffix("/repo.json") + "/repo.json"
        } else {
            indexUrl
        }
        return listOf(url, name, badgeLabel ?: name, signingKey, contactWebsite, contactDiscord, isLegacy ?: true, extensionListUrl)
    }

    private fun compareExtensionStores() {
        val afterStores = after.backupExtensionStores.map { it.normalized() }.associateBy { it[0] }
        before.backupExtensionStores.forEach { store ->
            val n = store.normalized()
            val where = "Extension store ${n[0]}"
            val other = afterStores[n[0]] ?: return@forEach missing(where, "the store")
            check("extension stores", where, "fields", n, other)
        }
    }

    private fun BackupSavedSearch.normalized() =
        copy(filterList = filterList.ifBlank { "[]" })

    private fun compareSavedSearches() {
        val afterSet = after.backupSavedSearches.map { it.normalized() }.toSet()
        before.backupSavedSearches.forEach { search ->
            count("saved searches")
            if (search.normalized() !in afterSet) missing("Saved search \"${search.name}\" (${search.source})", "the search")
        }
    }

    private fun BackupFeed.normalized() = copy(savedSearch = savedSearch?.normalized())

    private fun compareFeeds() {
        val afterSet = after.backupFeeds.map { it.normalized() }.toSet()
        before.backupFeeds.forEach { feed ->
            count("feeds")
            if (feed.normalized() !in afterSet) {
                missing("Feed for source ${feed.source} (global ${feed.global}, ${feed.savedSearch?.name ?: "no saved search"})", "the feed")
            }
        }
    }

    companion object {
        val NOT_COMPARED = listOf(
            "entry lastModifiedAt, favoriteModifiedAt and version, chapter lastModifiedAt and version: " +
                "kept by the database for sync and rewritten by every restore, on Android too",
            "database ids of categories (compared by name)",
        )

        /** For a unit test or report: the protobuf size of each part, to see that parts are not empty. */
        @Suppress("unused")
        fun sizes(backup: Backup): Map<String, Int> = mapOf(
            "entries" to backup.backupManga.size,
            "categories" to backup.backupCategories.size,
            "sources" to backup.backupSources.size,
            "app settings" to backup.backupPreferences.size,
            "source settings" to backup.backupSourcePreferences.size,
            "extension stores" to backup.backupExtensionStores.size,
            "saved searches" to backup.backupSavedSearches.size,
            "feeds" to backup.backupFeeds.size,
        )
    }
}
