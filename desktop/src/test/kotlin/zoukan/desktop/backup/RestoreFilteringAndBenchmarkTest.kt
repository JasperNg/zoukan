package zoukan.desktop.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTitle
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import okio.sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.TestHome
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** A backup file made by hand must not set this computer's own state. */
class RestoreFilterTest {
    private val trusted = BackupPreference(
        Preference.appStateKey("trusted_extensions"),
        StringSetPreferenceValue(setOf("evil.pkg:1:deadbeef")),
    )
    private val storage = BackupPreference(Preference.appStateKey("storage_dir"), StringPreferenceValue("\\\\evil\\share"))
    private val theme = BackupPreference("pref_app_theme", StringPreferenceValue("MONET"))
    private val token = BackupPreference(Preference.privateKey("track_token_1"), StringPreferenceValue("token"))
    private val days = BackupPreference("biometric_days", IntPreferenceValue(0))
    private val doh = BackupPreference("doh_provider", IntPreferenceValue(-1))
    private val syncHost = BackupPreference("connection_sync_client_host", StringPreferenceValue("http://evil.example"))
    private val lockOff = BackupPreference("use_biometric_lock", BooleanPreferenceValue(false))
    private val lockOn = BackupPreference("use_biometric_lock", BooleanPreferenceValue(true))

    private fun filtered(vararg preferences: BackupPreference, lockIsOn: Boolean = false): List<BackupPreference> =
        DesktopBackups.withSafePreferences(Backup(backupManga = emptyList(), backupPreferences = preferences.toList())) { lockIsOn }
            .backupPreferences

    @Test
    fun `app-state and private keys are dropped, settings stay`() {
        filtered(trusted, storage, theme, token) shouldBe listOf(theme)
    }

    @Test
    fun `the same keys are restored when the user confirms them`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupPreferences = listOf(days, doh, syncHost, token, theme, trusted),
        )
        DesktopBackups.withSafePreferences(backup, allowSensitive = true) { false }.backupPreferences shouldBe
            listOf(days, doh, syncHost, token, theme)
        DesktopBackups.sensitiveKeys(backup) shouldBe
            listOf("biometric_days", "doh_provider", "connection_sync_client_host", token.key)
    }

    @Test
    fun `source logins need confirmation too`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupSourcePreferences = listOf(BackupSourcePreferences("source_1", listOf(token, theme))),
        )
        DesktopBackups.withSafePreferences(backup) { false }.backupSourcePreferences.single().prefs shouldBe listOf(theme)
        DesktopBackups.withSafePreferences(backup, allowSensitive = true) { false }.backupSourcePreferences.single().prefs shouldBe
            listOf(token, theme)
    }

    @Test
    fun `a backup cannot set the lock schedule, DNS over HTTPS, the sync host or a private key`() {
        filtered(days, doh, syncHost, token, theme) shouldBe listOf(theme)
    }

    @Test
    fun `a sync or backup cannot switch off the content blocker or change its block lists`() {
        val blockerOff = BackupPreference("zoukan_browser_content_blocker", BooleanPreferenceValue(false))
        val lists = BackupPreference("zoukan_browser_block_lists", StringPreferenceValue("https://evil.example/list.txt"))
        filtered(blockerOff, lists, theme) shouldBe listOf(theme)
        val backup = Backup(backupManga = emptyList(), backupPreferences = listOf(blockerOff, lists, theme))
        DesktopBackups.sensitiveKeys(backup) shouldBe listOf("zoukan_browser_content_blocker", "zoukan_browser_block_lists")
        DesktopBackups.withSafePreferences(backup, allowSensitive = true) { false }.backupPreferences shouldBe
            listOf(blockerOff, lists, theme)
    }

    @Test
    fun `a sync or backup cannot switch on sending private settings to the sync server`() {
        val sendPrivate = BackupPreference("sync_privateSettings", BooleanPreferenceValue(true))
        filtered(sendPrivate, theme) shouldBe listOf(theme)
        DesktopBackups.sensitiveKeys(Backup(backupManga = emptyList(), backupPreferences = listOf(sendPrivate))) shouldBe
            listOf("sync_privateSettings")
    }

    @Test
    fun `another device's sync ETag is never restored, even when the user confirms the sensitive keys`() {
        val etag = BackupPreference("sync_etag", StringPreferenceValue("v2"))
        filtered(etag, theme) shouldBe listOf(theme)
        val backup = Backup(backupManga = emptyList(), backupPreferences = listOf(etag, theme))
        DesktopBackups.withSafePreferences(backup, allowSensitive = true) { false }.backupPreferences shouldBe listOf(theme)
        // Not a key to confirm: it is never written
        DesktopBackups.sensitiveKeys(backup) shouldBe emptyList()
    }

    @Test
    fun `a restore does not switch the app lock off`() {
        filtered(lockOff, lockIsOn = true) shouldBe emptyList()
        filtered(lockOff, lockIsOn = false) shouldBe listOf(lockOff)
        filtered(lockOn, lockIsOn = true) shouldBe listOf(lockOn)
    }

    @Test
    fun `source settings lose app-state keys too`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupSourcePreferences = listOf(BackupSourcePreferences("source_1", listOf(trusted, theme))),
        )
        DesktopBackups.withSafePreferences(backup) { false }.backupSourcePreferences.single().prefs shouldBe listOf(theme)
    }

    @Test
    fun `source settings go only to source files, not the app's own settings`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupSourcePreferences = listOf(
                BackupSourcePreferences("source_-42", listOf(theme)),
                BackupSourcePreferences("app.komikku.zoukan_preferences", listOf(lockOff)),
                BackupSourcePreferences("source_1_extra", listOf(theme)),
            ),
        )
        DesktopBackups.withSafePreferences(backup) { true }.backupSourcePreferences.map { it.sourceKey } shouldBe
            listOf("source_-42")
    }

    @Test
    fun `a backup that changes a known store's signing key needs the same confirmation as a new store`() {
        val known = ExtensionStore(
            indexUrl = "https://example.org/repo.json",
            name = "Example",
            badgeLabel = "Example",
            signingKey = "a".repeat(64),
            contact = ExtensionStore.Contact("https://example.org", null),
            isLegacy = true,
            extensionListUrl = null,
        )
        val repository = mockk<ExtensionStoreRepository> { coEvery { getAll() } returns listOf(known) }
        fun store(url: String, key: String, isLegacy: Boolean? = true) =
            BackupExtensionStore(url, "Example", null, key, "https://example.org", null, isLegacy, null)
        val same = store("https://example.org/repo.json", "a".repeat(64))
        // An old backup names the index, which the restore writes as repo.json
        val legacySame = store("https://example.org/index.min.json", "a".repeat(64), isLegacy = null)
        val newKey = store("https://example.org/repo.json", "b".repeat(64))
        val newStore = store("https://other.example/repo.json", "a".repeat(64))
        val backup = Backup(backupManga = emptyList(), backupExtensionStores = listOf(same, legacySame, newKey, newStore))
        runBlocking {
            DesktopBackups(File("unused"), NoStubSources).newExtensionStores(backup, GetExtensionStores(repository)) shouldBe
                listOf(newKey, newStore)
            // Sync and the command line restore without the new and re-keyed stores
            val (restorable, skipped) =
                DesktopBackups(File("unused"), NoStubSources).withoutNewExtensionStores(backup, GetExtensionStores(repository))
            restorable.backupExtensionStores shouldBe listOf(same, legacySame)
            skipped shouldBe listOf(newKey, newStore)
        }
    }

    @Test
    fun `a setting stored as untyped text is restored from a typed backup value`() {
        val written = mutableListOf<Int>()
        val intPref = mockk<Preference<Int>>(relaxed = true) { every { set(capture(written)) } just Runs }
        // As JavaSharedPreferences.getAll() returns a value saved before types were kept
        val store = mockk<PreferenceStore>(relaxed = true) {
            every { getAll() } returns mapOf("reader_theme" to "5")
            every { getInt("reader_theme", any()) } returns intPref
        }
        val restorer = PreferenceRestorer(getCategories = mockk(relaxed = true), preferenceStore = store)
        runBlocking { restorer.restoreApp(listOf(BackupPreference("reader_theme", IntPreferenceValue(7))), null) }
        written shouldBe listOf(7)
    }

    @Test
    fun `a setting that cannot be restored is reported, not only logged`() {
        val written = mutableListOf<Int>()
        val intPref = mockk<Preference<Int>>(relaxed = true) { every { set(capture(written)) } just Runs }
        val store = mockk<PreferenceStore>(relaxed = true) {
            every { getAll() } returns mapOf("reader_theme" to "dark")
            every { getInt("reader_theme", any()) } returns intPref
        }
        val restorer = PreferenceRestorer(getCategories = mockk(relaxed = true), preferenceStore = store)
        val problems = runBlocking {
            restorer.restoreApp(listOf(BackupPreference("reader_theme", IntPreferenceValue(7))), null)
        }
        written shouldBe emptyList()
        problems.single().contains("reader_theme") shouldBe true
    }

    @Test
    fun `a setting that fails to write is reported`() {
        val intPref = mockk<Preference<Int>>(relaxed = true) { every { set(any()) } throws IllegalStateException("disk full") }
        val store = mockk<PreferenceStore>(relaxed = true) {
            every { getAll() } returns emptyMap<String, Any>()
            every { getInt("reader_theme", any()) } returns intPref
        }
        val restorer = PreferenceRestorer(getCategories = mockk(relaxed = true), preferenceStore = store)
        val problems = runBlocking {
            restorer.restoreApp(listOf(BackupPreference("reader_theme", IntPreferenceValue(7))), null)
        }
        problems.single().contains("reader_theme") shouldBe true
    }
}

/** Restoring the same backup again must not add the searches and feeds the database already has. */
class SavedSearchRestoreTest {

    @TempDir
    lateinit var temp: File

    private fun withHandler(block: suspend (AndroidDatabaseHandler) -> Unit) {
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        try {
            runBlocking { block(handler) }
        } finally {
            driver.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `restoring the same saved searches twice keeps one of each`() = withHandler { handler ->
        val backup = SyntheticBackup.create()
        val restorer = SavedSearchRestorer(handler)
        restorer.restoreSavedSearches(backup.backupSavedSearches)
        restorer.restoreSavedSearches(backup.backupSavedSearches)
        handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe 2
    }

    @Test
    fun `restoring the same feeds twice keeps one of each`() = withHandler { handler ->
        val backup = SyntheticBackup.create()
        val filterless = backup.backupSavedSearches.first { it.filterList.isBlank() }
        val feeds = backup.backupFeeds + BackupFeed(source = filterless.source, global = false, savedSearch = filterless)
        SavedSearchRestorer(handler).restoreSavedSearches(backup.backupSavedSearches)
        val feedRestorer = FeedRestorer(handler)
        feedRestorer.restoreFeeds(feeds)
        val feedsAfterOne = handler.awaitList { feed_saved_searchQueries.selectAllFeedWithSavedSearch() }.size
        val searchesAfterOne = handler.awaitList { saved_searchQueries.selectAll() }.size

        feedRestorer.restoreFeeds(feeds)

        handler.awaitList { feed_saved_searchQueries.selectAllFeedWithSavedSearch() }.size shouldBe feedsAfterOne
        handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe searchesAfterOne
    }
}

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

/**
 * Restores a library-sized backup ([LargeBackup]) with [RestoreBenchmark], in its own JVM because
 * the runtime starts once per process, with the library watched as an open Library tab does.
 *
 * Besides the result, it checks what made restores slow before, by counts rather than by time, so
 * it holds on slow machines: the database thread keeps one connection for the whole restore
 * instead of opening one per entry, and a watched library hears of the changes a few times
 * instead of after every entry.
 *
 * Gradle properties, for timing by hand: `-Pzoukan.restoreBench=<entries>,<chapters>`,
 * `-Pzoukan.restoreBench.dir=<work dir>` (keeps the timings and content dumps) and
 * `-Pzoukan.restoreBench.jfr=<file.jfr>`. With a work dir, the library is watched only when the
 * dir holds an `observe` file.
 */
class RestoreSpeedTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a large restore is complete, repeatable and fast`() {
        val (entries, chapters) = (System.getProperty("zoukan.restoreBench") ?: "300,60").split(",").map { it.trim().toInt() }
        val work = System.getProperty("zoukan.restoreBench.dir")?.let(::File)
            ?: File(temp, "bench").apply { mkdirs() }.also { File(it, "observe").createNewFile() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classPath = System.getProperty("java.class.path").replace('\\', '/')
        val argFile = File(temp, "classpath.args").apply { writeText("-cp \"$classPath\"\n") }
        val jfr = System.getProperty("zoukan.restoreBench.jfr")?.let { listOf("-XX:StartFlightRecording=filename=$it,settings=profile") }.orEmpty()
        val log = File(temp, "bench.log")
        val process = ProcessBuilder(
            listOf(java, "-Djava.awt.headless=true", "-Xmx1g") + TestHome.childJvmArguments() + jfr +
                listOf("@${argFile.absolutePath}", "zoukan.desktop.backup.RestoreBenchmark", work.absolutePath, "$entries", "$chapters"),
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            // Where it is stuck, for the log
            ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/jcmd").path) + TestHome.jdkToolArguments() + listOf("${process.pid()}", "Thread.print"))
                .redirectErrorStream(true)
                .redirectOutput(File(work, "threads.txt"))
                .start()
                .waitFor(30, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
        println(log.readText().lines().filter { !it.contains(" I/") && !it.contains(" D/") }.takeLast(40).joinToString("\n"))
        finished shouldBe true
        process.exitValue() shouldBe 0

        // Nothing failed and nothing was lost on the round trip
        File(work, "result.txt").readText() shouldBe ""
        // Restoring the same backup again changes nothing
        File(work, "content-existing.txt").readText() shouldBe File(work, "content-empty.txt").readText()

        val timings = File(work, "timings.txt").readLines().associate { line -> line.substringBefore(' ') to line.substringAfter(' ').toLong() }
        for (pass in listOf("empty", "existing")) {
            // One connection for the whole restore (a few if it pauses for longer than the idle time)
            (timings.getValue("$pass.connections-opened") <= 3) shouldBe true
            if (File(work, "observe").exists()) {
                // The first list, a refresh every two seconds, one at the end and a few for the
                // writes around the entries; not one per entry
                val allowed = 5 + timings.getValue("$pass.total") / DesktopSqliteDriver.NOTIFY_INTERVAL_MS
                (timings.getValue("$pass.library-emissions") <= allowed) shouldBe true
            }
            // Generous: before these fixes the default size took about 10 times as long
            (timings.getValue("$pass.total") < 60_000) shouldBe true
        }
    }
}
