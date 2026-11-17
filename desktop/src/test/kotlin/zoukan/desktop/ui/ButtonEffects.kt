package zoukan.desktop.ui

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * The rows of the tables a click can change, read straight from the database, so the button
 * crawl can say which table, column and seeded row a click wrote ("chapters.read[Alpha/Chapter 1]").
 * Each row is named by what it is (an entry's title, a chapter's entry and name), not by its id,
 * so that the names are the same in every run.
 */
internal class DbState(private val driver: SqlDriver) {
    /** table -> row name -> column -> value. */
    class Rows(val tables: Map<String, Map<String, Map<String, String>>>)

    private companion object {
        const val SEP = "\u001f"

        /** The row names; a table not here is named by its row id. */
        val LABELS = mapOf(
            "mangas" to "t.title",
            "chapters" to "(SELECT title FROM mangas WHERE _id = t.manga_id) || '/' || t.name",
            "categories" to "t.name",
            "mangas_categories" to "(SELECT title FROM mangas WHERE _id = t.manga_id) || '/' || ifnull((SELECT name FROM categories WHERE _id = t.category_id), '?')",
            "history" to "ifnull((SELECT (SELECT title FROM mangas WHERE _id = c.manga_id) || '/' || c.name FROM chapters c WHERE c._id = t.chapter_id), '?')",
            "manga_sync" to "(SELECT title FROM mangas WHERE _id = t.manga_id) || '/' || t.sync_id",
            "excluded_scanlators" to "(SELECT title FROM mangas WHERE _id = t.manga_id) || '/' || t.scanlator",
            "saved_search" to "t.name",
        )

        /** The tables a click on the seeded app can write. */
        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "merged",
            "manga_sync", "excluded_scanlators", "saved_search", "feed_saved_search",
        )

        /** Columns the app writes as a side effect of any change: they say nothing about which button ran. */
        val VOLATILE = setOf("_id", "last_modified_at", "version", "is_syncing")
    }

    private val columns = mutableMapOf<String, List<String>>()

    private fun columnsOf(table: String): List<String> = columns.getOrPut(table) {
        driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(1)!!
            QueryResult.Value(names)
        }, 0).value.filter { it !in VOLATILE }
    }

    fun read(): Rows = Rows(
        TABLES.associateWith { table ->
            val cols = columnsOf(table)
            if (cols.isEmpty()) return@associateWith emptyMap()
            val label = LABELS[table] ?: "t.rowid"
            val values = cols.joinToString(" || '$SEP' || ") { "quote(t.\"$it\")" }
            driver.executeQuery(null, "SELECT $label, $values FROM $table AS t ORDER BY t.rowid", { cursor ->
                val rows = linkedMapOf<String, Map<String, String>>()
                while (cursor.next().value) {
                    var name = cursor.getString(0) ?: "?"
                    // Two rows with one name (two entries with one title) stay apart
                    var n = 1
                    while (name in rows) name = "${cursor.getString(0)}#${++n}"
                    rows[name] = cols.zip(cursor.getString(1).orEmpty().split(SEP)).toMap()
                }
                QueryResult.Value(rows)
            }, 0).value
        },
    )

    /**
     * What changed between [before] and [after]: "table.column[row]" for a changed value,
     * "table+[row]" for a new row and "table-[row]" for a row that went.
     */
    fun diff(before: Rows, after: Rows): List<String> = TABLES.flatMap { table ->
        val old = before.tables[table].orEmpty()
        val new = after.tables[table].orEmpty()
        (new.keys - old.keys).map { "$table+[$it]" } +
            (old.keys - new.keys).map { "$table-[$it]" } +
            new.keys.intersect(old.keys).flatMap { row ->
                val a = old.getValue(row)
                val b = new.getValue(row)
                b.keys.filter { a[it] != b[it] }.map { "$table.$it[$row]" }
            }
    }
}

/**
 * A wiring contract: a button whose key matches [button] must leave evidence (a signature token
 * or a database change, see [DbState.diff]) that matches [expects]. The generic signature says
 * that a click wrote something; a contract says it wrote the right thing, to the right row.
 */
internal class Contract(val button: Regex, val expects: Regex, val means: String)

internal object Contracts {
    val all = listOf(
        // The entry screen and the selection act on the entry and chapters of the seeded Alpha
        Contract(Regex("""^flow:manga\.chapter-selection/"Mark as read""""), Regex("""^chapters\.read\[Alpha/"""), "Mark as read writes Alpha's chapters"),
        Contract(Regex("""^flow:manga\.chapter-selection/"Bookmark""""), Regex("""^chapters\.bookmark\[Alpha/"""), "Bookmark writes Alpha's chapters"),
        Contract(Regex("""^flow:reader\.bars/"Bookmark \(B\)""""), Regex("""^chapters\.bookmark\[Alpha/"""), "the reader's Bookmark writes a chapter of the open entry"),
        Contract(Regex("""^flow:reader\.bars/"Reading mode: .*" > "Paged \(left to right\)""""), Regex("""^mangas\.viewer\[Alpha]$"""), "the reader's reading mode writes Alpha's own reading mode"),
        Contract(Regex("""^flow:reader\.bars/"Scale type: .*" > """), Regex("""^pref:pref_image_scale_type_key$"""), "the reader's scale type writes its preference"),
        Contract(Regex("""^screen:manga\.MangaScreen/"In library"$"""), Regex("""^mangas\.favorite\[Alpha]$"""), "In library on Alpha's screen removes Alpha"),
        Contract(Regex("""^flow:library\.selection/"Mark as read""""), Regex("""^chapters\.read\[Alpha/"""), "the library selection's Mark as read writes the selected entry's chapters"),
        // Settings that write their preference at once: these dialogs apply each choice as it is made
        Contract(Regex("""^dialog:library\.LibrarySettingsDialog/.*\((dialog )?toggle\)$"""), Regex("""^(pref:|db:categories\.)"""), "the library's filter, sort and display options write their preference"),
        Contract(Regex("""^dialog:reader\.ReaderSettingsDialog/.*\((dialog )?toggle\)$"""), Regex("""^(pref:|mangas\.viewer\[Alpha]$)"""), "the reader settings write their preference, or Alpha's own reading mode"),
        Contract(Regex("""^dialog:manga\.ChapterSettingsDialog/.*\((dialog )?toggle\)$"""), Regex("""^(mangas\.chapter_flags\[Alpha]|pref:)"""), "the chapter filter and sort write Alpha's chapter flags"),
        Contract(Regex("""^settings:[A-Za-z]+/.*\(toggle\)$"""), Regex("""^(pref:|calls|dialog:|menu$|nav:)"""), "a Settings switch or choice on the page writes its preference (or asks first); one in a dialog may wait for OK"),
        // The keyboard shortcuts (ButtonCrawlTest.KEYS): the app's on the library, the reader's on the reader
        Contract(Regex("""^tab:Library/key:Ctrl\+[2-5]$"""), Regex("""^tab:"""), "Ctrl/Cmd + a number opens that tab"),
        Contract(Regex("""^tab:More/key:Ctrl\+1$"""), Regex("""^tab:Library$"""), "Ctrl/Cmd + 1 opens the library"),
        Contract(Regex("""^tab:Library/key:Ctrl\+Shift\+U$"""), Regex("""^job:library-update$"""), "Ctrl/Cmd + Shift + U starts a library update"),
        Contract(Regex("""^flow:library\.selection/key:Ctrl\+A$"""), Regex("""^selection$"""), "Ctrl/Cmd + A selects the library"),
        Contract(Regex("""^tab:Library/key:Ctrl\+F$"""), Regex("""^focus:"""), "Ctrl/Cmd + F puts the cursor in the search box"),
        Contract(Regex("""^screen:manga\.MangaScreen/key:(Escape|Alt\+Left)$"""), Regex("""^nav:back$"""), "Escape and Alt + Left go back"),
        Contract(Regex("""^flow:reader\.keys/key:(Right|Space|Page Down|End)$"""), Regex("""^reader:page$"""), "the reader's next and last page keys turn the page"),
        Contract(Regex("""^flow:reader\.keys/key:=$"""), Regex("""^reader:zoom$"""), "= zooms in"),
        Contract(Regex("""^flow:reader\.keys/key:M$"""), Regex("""^reader:menu$"""), "M shows the reader's bars"),
        Contract(Regex("""^flow:reader\.keys/key:F$"""), Regex("""^reader:fullscreen$"""), "F makes the reader fullscreen"),
        Contract(Regex("""^flow:reader\.keys/key:/$"""), Regex("""^reader:help$"""), "/ shows the keyboard help"),
        Contract(Regex("""^flow:reader\.keys/key:A$"""), Regex("""^reader:auto-scroll$"""), "A starts auto scroll"),
        Contract(Regex("""^flow:reader\.keys/key:B$"""), Regex("""^chapters\.bookmark\[Alpha/"""), "B bookmarks the open chapter"),
        Contract(Regex("""^flow:reader\.keys/key:Escape$"""), Regex("""^nav:back$"""), "Escape leaves the reader when nothing is open over it"),
    )
}
