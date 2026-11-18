package zoukan.desktop.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.sink
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Times a restore of [LargeBackup] into a new profile, then again into the library it made, and
 * checks the result. Runs in its own JVM, because the runtime starts once per process.
 *
 * Arguments: `<work dir> <entries> <chapters per entry>`. Writes into the work dir:
 * - `timings.txt`: one `name millis` line per phase,
 * - `content-empty.txt` and `content-existing.txt`: every table's rows after each restore (sorted,
 *   without the columns that hold the time of the run), plus custom info, so two builds can be diffed,
 * - `result.txt`: the restore errors and the round-trip differences, empty when there are none.
 */
object RestoreBenchmark {

    @JvmStatic
    fun main(args: Array<String>) {
        val work = File(args[0]).apply { mkdirs() }
        val entries = args[1].toInt()
        val chapters = args[2].toInt()
        val timings = linkedMapOf<String, Long>()
        val problems = mutableListOf<String>()

        val file = File(work, "large.tachibk")
        file.sink().use { BackupCodec.write(LargeBackup.create(entries, chapters), it) }

        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        val profile = File(work, "profile").apply { deleteRecursively() }
        val dirs = ZoukanDirs(profile)
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        val backups = DesktopBackups(dirs.android)
        // Warm up the JIT and the database on a separate small restore, so phase times are steadier
        runBlocking { backups.restore(SyntheticBackup.create(), allowSensitive = true) }

        val decoded = timed(timings, "decode") { backups.read(file) }

        // With an `observe` file in the work dir, the library is watched during the restore, as
        // the Library tab does while the app is open
        val observe = File(work, "observe").exists()
        for (pass in listOf("empty", "existing")) {
            val watching = if (observe) watchLibrary() else null
            val openedBefore = openedConnections()
            val start = System.nanoTime()
            val firstEntry = longArrayOf(0)
            var lastEntry = 0L
            val errors = runBlocking {
                backups.restore(decoded, allowSensitive = true) { step, _, _ ->
                    val at = (System.nanoTime() - start) / 1_000_000
                    when (step) {
                        is BackupContentRestorer.Step.Entry -> {
                            if (firstEntry[0] == 0L) firstEntry[0] = at
                            lastEntry = at
                        }
                        is BackupContentRestorer.Step.Section -> timings["$pass.done.${sectionName(step)}"] = at
                    }
                }
            }
            timings["$pass.total"] = (System.nanoTime() - start) / 1_000_000
            timings["$pass.connections-opened"] = openedConnections() - openedBefore
            watching?.let { (job, emissions) ->
                runBlocking { job.cancelAndJoin() }
                timings["$pass.library-emissions"] = emissions.get().toLong()
            }
            timings["$pass.first-entry"] = firstEntry[0]
            timings["$pass.entries"] = lastEntry - firstEntry[0]
            errors.forEach { problems += "$pass restore: ${it.second}" }
            File(work, "timings.txt").writeText(timings.entries.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" })
            File(work, "content-$pass.txt").writeText(content(dirs))
        }

        val exported = File(work, "exported.tachibk")
        timed(timings, "create") { runBlocking { backups.create(exported, BackupOptions(privateSettings = true)) } }
        BackupComparison(decoded, backups.read(exported)).compare().differences.forEach { problems += "round trip: $it" }

        File(work, "timings.txt").writeText(timings.entries.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" })
        File(work, "result.txt").writeText(problems.joinToString("\n"))
        println(timings.entries.joinToString("\n") { "${it.key} ${it.value}" })
        println(if (problems.isEmpty()) "No problems" else "${problems.size} problems")
        // The runtime leaves non-daemon threads behind
        exitProcess(0)
    }

    /** Connections the app's driver has opened so far. */
    private fun openedConnections(): Long = (Injekt.get<SqlDriver>() as DesktopSqliteDriver).opened.get().toLong()

    /** Collects the library as LibraryScreenModel does; returns the job and how many lists it got. */
    private fun watchLibrary(): Pair<Job, AtomicInteger> {
        val emissions = AtomicInteger()
        val first = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            Injekt.get<GetLibraryManga>().subscribe().collect {
                emissions.incrementAndGet()
                first.complete(Unit)
            }
        }
        // Wait for the first list, as an open Library tab has it
        runBlocking { first.await() }
        return job to emissions
    }

    private fun sectionName(step: BackupContentRestorer.Step.Section) = when (step.label) {
        MR.strings.categories -> "categories"
        MR.strings.app_settings -> "app-settings"
        MR.strings.source_settings -> "source-settings"
        MR.strings.extensionStores -> "extension-stores"
        KMR.strings.saved_searches_feeds -> "saved-searches"
        else -> "other"
    }

    private inline fun <T> timed(timings: MutableMap<String, Long>, name: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { timings[name] = (System.nanoTime() - start) / 1_000_000 }
    }

    /** Columns a restore sets from the clock, which differ between two runs of the same restore. */
    private val CLOCK_COLUMNS = setOf("last_modified_at", "favorite_modified_at", "next_update", "calculate_interval")

    /** Every table's rows, sorted, without [CLOCK_COLUMNS]; then custom info, for diffing two runs. */
    fun content(dirs: ZoukanDirs): String = buildString {
        val driver = Injekt.get<SqlDriver>()
        val tables = query(driver, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0] }
        for (table in tables) {
            val columns = query(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid").map { it[0]!! }
                .filter { it !in CLOCK_COLUMNS && !(table == "mangas_categories" && it == "_id") }
            val rows = query(driver, "SELECT ${columns.joinToString { "quote(\"$it\")" }} FROM \"$table\"")
                .map { row -> row.joinToString("|") }
                .sorted()
            appendLine("## $table (${columns.joinToString()}) ${rows.size} rows")
            rows.forEach { appendLine(it) }
        }
        val edits = dirs.android.walkTopDown().firstOrNull { it.name == "edits.json" }
        appendLine("## edits.json")
        appendLine(edits?.readText())
    }

    private fun query(driver: SqlDriver, sql: String): List<List<String?>> =
        driver.executeQuery(null, sql, { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) {
                // quote() gives one text column per value; the column count is not known here, so
                // read until a column is out of range
                val row = mutableListOf<String?>()
                var i = 0
                while (true) {
                    val value = try {
                        cursor.getString(i)
                    } catch (_: Exception) {
                        break
                    }
                    row += value
                    i++
                }
                rows += row
            }
            QueryResult.Value(rows)
        }, 0).value
}
