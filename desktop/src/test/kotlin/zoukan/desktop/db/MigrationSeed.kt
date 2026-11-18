package zoukan.desktop.db

/**
 * Known rows for every table of the schema, written into old databases by [OldDatabases.seed]
 * (each with the columns its version has), and what each must be after migrating to the newest
 * version ([expected]).
 *
 * The values are invented. They include what old Android builds could leave behind: rows whose
 * parent is gone (foreign keys were not always enforced; `47.sqm` removes them), a scanlator with
 * spaces around it (`29.sqm` trims it), a negative score for tracker 7 (`30.sqm`), tracker ids in
 * URLs for trackers 3 and 9 (`42.sqm`) and an extension repository (`46.sqm` moves it to the
 * extension stores).
 *
 * Rows of a table are inserted in list order. `mangas_categories` comes before `mangas`: its insert
 * trigger (from `31.sqm`) would otherwise bump the entry's version and modified time.
 */
internal object MigrationSeed {

    /** A column a migration set to the current time (`strftime('%s', 'now')`), in seconds. */
    object Now {
        override fun toString() = "<now>"
    }

    private const val MERGED_SOURCE = 6969L
    private val SIGNING_A = "aa".repeat(32)
    private val SIGNING_B = "bb".repeat(32)

    private fun row(table: String, vararg values: Pair<String, Any?>) = SeedRow(table, linkedMapOf(*values))

    private fun manga(id: Long, source: Long, url: String, title: String, favorite: Long, vararg extra: Pair<String, Any?>) = row(
        "mangas",
        "_id" to id, "source" to source, "url" to url, "artist" to null, "author" to null, "description" to null,
        "genre" to null, "title" to title, "status" to 0L, "thumbnail_url" to null, "favorite" to favorite,
        "last_update" to null, "next_update" to null, "initialized" to 0L, "viewer" to 0L, "chapter_flags" to 0L,
        "cover_last_modified" to 0L, "date_added" to 0L, "filtered_scanlators" to null, "update_strategy" to 0L,
        "calculate_interval" to 0L,
        // Added by later migrations
        "last_modified_at" to 1_700_000_000L + id, "favorite_modified_at" to null, "version" to 0L, "is_syncing" to 0L,
        "notes" to "", "memo" to Blob.of("{}"),
    ).let { it.copy(values = it.values + extra) }

    private fun chapter(id: Long, mangaId: Long, url: String, name: String, scanlator: String?, vararg extra: Pair<String, Any?>) = row(
        "chapters",
        "_id" to id, "manga_id" to mangaId, "url" to url, "name" to name, "scanlator" to scanlator, "read" to 0L,
        "bookmark" to 0L, "last_page_read" to 0L, "chapter_number" to 1.0, "source_order" to 0L, "date_fetch" to 0L,
        "date_upload" to 0L,
        // Added by later migrations
        "last_modified_at" to 1_700_000_000L + id, "version" to 0L, "is_syncing" to 0L, "memo" to Blob.of("{}"),
    ).let { it.copy(values = it.values + extra) }

    private fun track(id: Long, mangaId: Long, syncId: Long, remoteId: Long, libraryId: Long?, score: Double, url: String, vararg extra: Pair<String, Any?>) = row(
        "manga_sync",
        "_id" to id, "manga_id" to mangaId, "sync_id" to syncId, "remote_id" to remoteId, "library_id" to libraryId,
        "title" to "Tracked $id", "last_chapter_read" to 0.0, "total_chapters" to 0L, "status" to 1L, "score" to score,
        "remote_url" to url, "start_date" to 0L, "finish_date" to 0L, "private" to 0L,
    ).let { it.copy(values = it.values + extra) }

    private fun merged(id: Long, mergeId: Long, mangaId: Long?, mangaUrl: String, source: Long, info: Long) = row(
        "merged",
        "_id" to id, "info_manga" to info, "get_chapter_updates" to 1L, "chapter_sort_mode" to id % 3, "chapter_priority" to id,
        "download_chapters" to 1L, "merge_id" to mergeId, "merge_url" to "merged-1", "manga_id" to mangaId,
        "manga_url" to mangaUrl, "manga_source" to source,
    )

    val rows: List<SeedRow> = listOf(
        row("sources", "_id" to 1001L, "lang" to "en", "name" to "Northwind Comics"),
        row("sources", "_id" to 1002L, "lang" to "ja", "name" to "Paper Lantern"),

        row("categories", "_id" to 1L, "name" to "Reading", "sort" to 1L, "flags" to 64L, "manga_order" to "", "hidden" to 1L),
        row("categories", "_id" to 2L, "name" to "Later", "sort" to 2L, "flags" to 0L, "manga_order" to "", "hidden" to 0L),

        row("mangas_categories", "_id" to 1L, "manga_id" to 1L, "category_id" to 1L),
        row("mangas_categories", "_id" to 2L, "manga_id" to 1L, "category_id" to 2L),
        // Orphans: the entry, or the category, is gone
        row("mangas_categories", "_id" to 3L, "manga_id" to 999L, "category_id" to 1L),
        row("mangas_categories", "_id" to 4L, "manga_id" to 1L, "category_id" to 999L),

        manga(
            1, 1001, "/series/harbor-lights", "Harbor Lights", 1,
            "artist" to "Ada Artist", "author" to "Ben Writer", "description" to "A keeper of lights.\nSecond line, ünïcödé.",
            "genre" to "Drama, Sea", "status" to 1L, "thumbnail_url" to "https://example.org/c/1.jpg",
            "last_update" to 1_700_000_000_000L, "next_update" to 1_700_086_400_000L, "initialized" to 1L, "viewer" to 19L,
            "chapter_flags" to 770L, "cover_last_modified" to 1_700_000_000_001L, "date_added" to 1_690_000_000_000L,
            "calculate_interval" to 7L,
            "last_modified_at" to 1_700_000_002L, "favorite_modified_at" to 1_700_000_003L, "version" to 5L,
            "notes" to "Seeded note", "memo" to Blob.of("""{"pinned":true}"""),
        ),
        manga(
            3,
            1002,
            "/series/paper-days",
            "Paper Days",
            0,
            "update_strategy" to 1L,
            "filtered_scanlators" to "Bad Scans",
            "last_modified_at" to 1_700_000_010L,
        ),
        manga(4, MERGED_SOURCE, "merged-1", "Harbor Lights", 1, "date_added" to 1_695_000_000_000L),

        chapter(
            10, 1, "/c/10", "Ch. 1 - Arrival", "Moonlit Scans",
            "read" to 1L, "bookmark" to 1L, "last_page_read" to 17L, "source_order" to 2L,
            "date_fetch" to 1_700_000_100_000L, "date_upload" to 1_699_000_000_000L,
            "last_modified_at" to 1_700_000_004L, "version" to 2L, "memo" to Blob.of("""{"note":"x"}"""),
        ),
        chapter(11, 1, "/c/11", "Ch. 1.5", "  Padded Group  ", "chapter_number" to 1.5, "last_page_read" to 4L, "source_order" to 1L),
        chapter(12, 3, "/p/12", "Chapter 1", null, "read" to 1L),
        // Orphan: its entry is gone
        chapter(99, 999, "/gone/99", "Gone", null),

        row("history", "_id" to 1L, "chapter_id" to 10L, "last_read" to 1_700_001_000_000L, "time_read" to 600_000L),
        row("history", "_id" to 2L, "chapter_id" to 12L, "last_read" to null, "time_read" to 0L),
        // Orphans: the chapter of an orphan chapter, and a chapter that is gone
        row("history", "_id" to 3L, "chapter_id" to 99L, "last_read" to 1L, "time_read" to 1L),
        row("history", "_id" to 4L, "chapter_id" to 9999L, "last_read" to 1L, "time_read" to 1L),

        track(
            1, 1, 2, 4242, 77, 85.0, "https://example.org/track/4242",
            "title" to "Harbor Lights (tracker)", "last_chapter_read" to 1.0, "total_chapters" to 20L,
            "start_date" to 1_700_000_000_000L, "private" to 1L,
        ),
        // Tracker 7 with a negative score; tracker 3 with its id only in the URL
        track(2, 1, 7, 31, null, -1.0, "https://example.org/t7/31"),
        track(3, 3, 3, 5, 6, 0.0, "https://example.org/manga/12345"),
        track(4, 999, 2, 1, null, 0.0, "https://example.org/gone"),

        row("excluded_scanlators", "manga_id" to 1L, "scanlator" to "Bad Scans"),
        row("excluded_scanlators", "manga_id" to 999L, "scanlator" to "Gone"),

        row("saved_search", "_id" to 1L, "source" to 1001L, "name" to "Completed", "query" to "action", "filters_json" to "[]"),
        row("feed_saved_search", "_id" to 1L, "source" to 1001L, "saved_search" to 1L, "global" to 0L, "feed_order" to 3L),
        row("feed_saved_search", "_id" to 2L, "source" to 1002L, "saved_search" to null, "global" to 1L, "feed_order" to 4L),
        row("feed_saved_search", "_id" to 3L, "source" to 1001L, "saved_search" to 77L, "global" to 0L, "feed_order" to 5L),

        merged(1, mergeId = 4, mangaId = 4, mangaUrl = "merged-1", source = MERGED_SOURCE, info = 1),
        merged(2, mergeId = 4, mangaId = 1, mangaUrl = "/series/harbor-lights", source = 1001, info = 0),
        // A member whose entry is gone (kept, without it) and a merge whose entry is gone (removed)
        merged(3, mergeId = 4, mangaId = 555, mangaUrl = "/gone", source = 1002, info = 0),
        merged(4, mergeId = 888, mangaId = 1, mangaUrl = "/series/harbor-lights", source = 1001, info = 0),

        row("search_metadata", "manga_id" to 1L, "uploader" to "uploader", "extra" to """{"k":1}""", "indexed_extra" to "idx", "extra_version" to 2L),
        row("search_metadata", "manga_id" to 999L, "uploader" to null, "extra" to "{}", "indexed_extra" to null, "extra_version" to 1L),
        row("search_tags", "_id" to 1L, "manga_id" to 1L, "namespace" to "genre", "name" to "sea", "type" to 1L),
        row("search_tags", "_id" to 2L, "manga_id" to 999L, "namespace" to null, "name" to "gone", "type" to 0L),
        row("search_titles", "_id" to 1L, "manga_id" to 1L, "title" to "Alt Title", "type" to 0L),
        row("search_titles", "_id" to 2L, "manga_id" to 999L, "title" to "Gone", "type" to 0L),

        row("eh_favorites", "gid" to "100", "token" to "abc", "title" to "Favorite Title", "category" to 2L),
        row("eh_favorites_alternatives", "id" to 1L, "gid" to "100", "token" to "abc", "otherGid" to "101", "otherToken" to "def"),

        row("libraryUpdateErrorMessage", "_id" to 1L, "message" to "Timed out"),
        row("libraryUpdateError", "_id" to 1L, "manga_id" to 1L, "message_id" to 1L, "last_update" to 1_700_000_500L),

        row(
            "extension_repos",
            "base_url" to "https://example.org/store",
            "name" to "Example store",
            "short_name" to "EX",
            "website" to "https://example.org",
            "signing_key_fingerprint" to SIGNING_A,
        ),
        row(
            "extension_store",
            "index_url" to "https://example.org/store2/index.min.json", "name" to "Second store", "badge_label" to "S2",
            "signing_key" to SIGNING_B, "contact_website" to "https://example.org", "contact_discord" to null,
            "is_legacy" to 0L, "extension_list_url" to "https://example.org/store2/index.pb",
        ),
    )

    /** The columns that identify a row of each table. */
    fun keyOf(table: String): List<String> = when (table) {
        "search_metadata" -> listOf("manga_id")
        "excluded_scanlators" -> listOf("manga_id", "scanlator")
        "eh_favorites" -> listOf("gid", "token")
        "eh_favorites_alternatives" -> listOf("id")
        "extension_store" -> listOf("index_url")
        "extension_repos" -> listOf("base_url")
        else -> listOf("_id")
    }

    /**
     * Every table's rows after [seeded] (inserted at version [from]) was migrated to the newest
     * version, with every column of the newest schema ([newestColumns]). Each seeded row sets every
     * column its version has ([OldDatabases.seed] checks this), so a column missing from it is one a
     * later migration added: it takes the value that migration gives it. A column with no rule here
     * fails, so a new migration that adds a column must say what old rows get.
     */
    fun expected(from: Long, seeded: List<SeedRow>, newestColumns: Map<String, List<String>>): Map<String, List<Map<String, Any?>>> {
        val tables = newestColumns.keys.associateWith { mutableListOf<MutableMap<String, Any?>>() }
        // The system category every schema has
        tables.getValue("categories") += linkedMapOf<String, Any?>(
            "_id" to 0L,
            "name" to "",
            "sort" to -1L,
            "flags" to 0L,
            "manga_order" to "",
            "hidden" to 0L,
        )
        for (row in seeded) {
            if (row.table == "extension_repos") {
                // 46.sqm: every repository becomes a legacy store
                if (from <= 46) {
                    tables.getValue("extension_store") += mutableMapOf(
                        "index_url" to "${row.values["base_url"]}/repo.json",
                        "name" to row.values["name"],
                        "badge_label" to (row.values["short_name"] ?: row.values["name"]),
                        "signing_key" to row.values["signing_key_fingerprint"],
                        "contact_website" to row.values["website"],
                        "contact_discord" to null,
                        "is_legacy" to 1L,
                        "extension_list_url" to null,
                    )
                }
                continue
            }
            val columns = newestColumns[row.table] ?: error("The newest schema has no table ${row.table}")
            tables.getValue(row.table) += columns.associateWithTo(linkedMapOf()) { column ->
                if (column in row.values) row.values[column] else added(row.table, column, row.values)
            }
        }
        transform(from, tables)
        if (from <= 47) removeOrphans(tables)
        return tables
    }

    private fun added(table: String, column: String, row: Map<String, Any?>): Any? = when ("$table.$column") {
        // 28.sqm
        "mangas.last_modified_at", "chapters.last_modified_at" -> Now
        "mangas.favorite_modified_at" -> if (row["favorite"] == 1L) Now else null
        // 31.sqm
        "mangas.version", "mangas.is_syncing", "chapters.version", "chapters.is_syncing" -> 0L
        // 33.sqm: the feed order starts as the row's id
        "feed_saved_search.feed_order" -> row["_id"]
        // 34.sqm, 36.sqm, 38.sqm, 39.sqm
        "categories.hidden" -> 0L
        "manga_sync.private" -> 0L
        "mangas.notes" -> ""
        "libraryUpdateError.last_update" -> 0L
        // 46.sqm
        "mangas.memo", "chapters.memo" -> Blob.of("{}")
        else -> error("No expectation for $table.$column on rows from before the migration that added it")
    }

    /** What the data migrations do to rows they apply to. */
    private fun transform(from: Long, tables: Map<String, MutableList<MutableMap<String, Any?>>>) {
        // 29.sqm trims scanlators; the update fires 28.sqm's trigger, which sets last_modified_at
        if (from <= 29) {
            tables.getValue("chapters").filter { it["scanlator"] != null }.forEach {
                it["scanlator"] = (it["scanlator"] as String).trim()
                it["last_modified_at"] = Now
            }
        }
        // 30.sqm: no negative scores for tracker 7
        if (from <= 30) {
            tables.getValue("manga_sync").filter { it["sync_id"] == 7L }.forEach { it["score"] = maxOf(it["score"] as Double, 0.0) }
        }
        // 42.sqm: tracker 3 keeps its old remote id as the library id; 3 and 9 take the id from the URL
        if (from <= 42) {
            tables.getValue("manga_sync").forEach { track ->
                if (track["sync_id"] == 3L) track["library_id"] = track["remote_id"]
                if (track["sync_id"] == 3L || track["sync_id"] == 9L) {
                    track["remote_id"] = (track["remote_url"] as String).substringAfterLast('/').toLong()
                }
            }
        }
    }

    /** 47.sqm: the rows whose parent is gone, removed with each foreign key's own ON DELETE action. */
    private fun removeOrphans(tables: Map<String, MutableList<MutableMap<String, Any?>>>) {
        fun ids(table: String, column: String = "_id") = tables.getValue(table).map { it[column] }.toSet()
        val mangas = ids("mangas")
        tables.getValue("chapters").removeAll { it["manga_id"] !in mangas }
        val chapters = ids("chapters")
        tables.getValue("history").removeAll { it["chapter_id"] !in chapters }
        val categories = ids("categories") + 0L
        tables.getValue("mangas_categories").removeAll { it["manga_id"] !in mangas || it["category_id"] !in categories }
        for (table in listOf("manga_sync", "excluded_scanlators", "search_metadata", "search_tags", "search_titles")) {
            tables.getValue(table).removeAll { it["manga_id"] !in mangas }
        }
        tables.getValue("merged").removeAll { it["merge_id"] !in mangas }
        tables.getValue("merged").filter { it["manga_id"] != null && it["manga_id"] !in mangas }.forEach { it["manga_id"] = null }
        val searches = ids("saved_search")
        tables.getValue("feed_saved_search").removeAll { it["saved_search"] != null && it["saved_search"] !in searches }
    }
}
