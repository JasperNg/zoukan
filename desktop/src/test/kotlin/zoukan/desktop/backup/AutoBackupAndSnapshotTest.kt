package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.storage.ZoukanStorage
import zoukan.i18n.desktop.DMR
import java.awt.HeadlessException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.Executors
import kotlin.random.Random

class AutoBackupTest {

    private fun <T> pref(value: T) = mockk<Preference<T>>(relaxed = true) { every { get() } returns value }

    @Test
    fun `a failed backup is not retried at every check`() = runBlocking<Unit> {
        val storage = mockk<ZoukanStorage> { every { autoBackups } throws IOException("The folder is not writable") }
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns pref(0L)
        }
        val backup = AutoBackup(storage, File("."), preferences, pref(""))

        backup.isDue() shouldBe true
        backup.run() shouldBe null
        // The background jobs check every minute: no new attempt (and error notification) each time
        val now = System.currentTimeMillis()
        backup.isDue(now + 60_000) shouldBe false
        backup.isDue(now + AutoBackup.RETRY_AFTER + 1) shouldBe true
    }

    @Test
    fun `the automatic backup waits while a restore is in progress`() = runBlocking<Unit> {
        // Strict mock: the skipped backup must not touch storage at all, let alone rotate backups out
        val storage = mockk<ZoukanStorage>()
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns pref(0L)
        }
        val backup = AutoBackup(storage, File("."), preferences, pref(""))

        RestoreGate.whileRestoring {
            RestoreGate.isRestoring shouldBe true
            backup.run() shouldBe null
        }
        RestoreGate.isRestoring shouldBe false
        // The skip was not an attempt: the next check still finds the backup due
        backup.isDue() shouldBe true
    }

    @Test
    fun `rotation keeps the newest four of Zoukan's backups and never touches the Android app's`(@TempDir folder: File) {
        // Android names them `<applicationId>_yyyy-MM-dd_HH-mm.tachibk` (BackupCreator.getFilename),
        // and "app.komikku_" sorts before "zoukan_": older, newer and same-day ones, and a debug build's
        val android = listOf(
            "app.komikku_2023-12-31_23-59.tachibk",
            "app.komikku_2024-01-01_09-00.tachibk",
            "app.komikku_2024-01-05_12-00.tachibk",
            "app.komikku_2024-03-01_08-30.tachibk",
            "app.komikku_2025-06-01_00-00.tachibk",
            "app.komikku.dev_2024-02-01_10-00.tachibk",
        ).map { File(folder, it).apply { writeText("android") } }
        val zoukan = listOf(
            "zoukan_2024-01-01_10-00.tachibk",
            "zoukan_2024-01-02_10-00.tachibk",
            "zoukan_2024-01-03_10-00.tachibk",
            "zoukan_2024-01-04_10-00.tachibk",
            "zoukan_2024-01-05_10-00.tachibk",
            "zoukan_2024-01-06_10-00.tachibk",
        ).map { File(folder, it).apply { writeText("zoukan") } }

        // Every run rotates; repeating it must not eat into the Android app's backups either
        val own = zoukan.map { it.name }
        repeat(3) { AutoBackup.prune(folder, own, zoukan.last()) }

        android.forEach { it.exists() shouldBe true }
        zoukan.take(2).forEach { it.exists() shouldBe false }
        zoukan.drop(2).forEach { it.exists() shouldBe true }
    }

    private val nameTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test
    fun `rotation never deletes the backup it just wrote`(@TempDir folder: File) {
        val ahead = LocalDateTime.now().plusYears(1)
        val futureNames = (0 until 4).map { "zoukan_${ahead.plusMinutes(it.toLong()).format(nameTime)}.tachibk" }
        futureNames.forEachIndexed { i, n -> File(folder, n).writeText("future $i") }
        val newName = "zoukan_${LocalDateTime.now().format(nameTime)}.tachibk"
        val newFile = File(folder, newName).apply { writeText("new") }

        val kept = AutoBackup.prune(folder, futureNames + newName, newFile)

        newFile.exists() shouldBe true
        kept.last() shouldBe newName
        // The oldest written goes, whatever the times in the names say
        File(folder, futureNames.first()).exists() shouldBe false
        futureNames.drop(1).forEach { File(folder, it).exists() shouldBe true }
    }

    @Test
    fun `rotation never deletes backups another computer wrote`(@TempDir folder: File) {
        val others = (1..6).map { n ->
            val bytes = Random.nextBytes(64)
            File(folder, "zoukan_2024-01-0${n}_10-00.tachibk").apply { writeBytes(bytes) } to bytes
        }
        val ownFile = File(folder, "zoukan_2024-02-01_10-00.tachibk").apply { writeText("own") }

        AutoBackup.prune(folder, listOf(ownFile.name), ownFile)

        others.forEach { (file, bytes) ->
            file.exists() shouldBe true
            (file.readBytes() contentEquals bytes) shouldBe true
        }
        ownFile.exists() shouldBe true
    }

    @Test
    fun `existing backups survive an automatic backup byte for byte`(@TempDir folder: File) {
        val untouchable = listOf(
            "app.komikku_2024-01-01_09-00.tachibk",
            "app.komikku_2099-01-01_09-00.tachibk",
            "zoukan_2099-01-01_10-00.tachibk",
            "zoukan_2099-01-02_10-00.tachibk",
            "zoukan_2020-01-01_10-00.tachibk",
            "zoukan_2020-01-02_10-00.tachibk",
            "notes.txt",
        ).map { File(folder, it).apply { writeBytes(Random.nextBytes(128)) } }
        val hashes = untouchable.associateWith { sha(it) }
        val ownNames = (1..5).map { "zoukan_2024-03-0${it}_10-00.tachibk" }
        ownNames.forEach { File(folder, it).writeBytes(Random.nextBytes(128)) }
        val justWritten = File(folder, ownNames.last())

        val kept = AutoBackup.prune(folder, ownNames, justWritten)

        untouchable.forEach {
            it.exists() shouldBe true
            sha(it) shouldBe hashes.getValue(it)
        }
        File(folder, ownNames.first()).exists() shouldBe false
        ownNames.drop(1).forEach { File(folder, it).exists() shouldBe true }
        kept shouldContainExactly ownNames.drop(1)
    }

    @Test
    fun `a backup never replaces a file with the same name`(@TempDir folder: File) {
        val old = Random.nextBytes(64)
        val file = File(folder, "zoukan_2024-01-01_10-00.tachibk").apply { writeBytes(old) }
        val temp = File(folder, "zoukan_2024-01-01_10-00.tachibk.tmp").apply { writeBytes(Random.nextBytes(64)) }

        shouldThrow<FileAlreadyExistsException> { DesktopBackups.moveIntoPlace(temp, file, replaceExisting = false) }

        (file.readBytes() contentEquals old) shouldBe true
    }

    @Test
    fun `an automatic backup picks a free name when the minute's name is taken`(@TempDir folder: File) {
        val base = "zoukan_2024-01-01_10-00"
        AutoBackup.freeFile(folder, base) shouldBe File(folder, "$base.tachibk")
        File(folder, "$base.tachibk").writeText("a")
        AutoBackup.freeFile(folder, base) shouldBe File(folder, "$base-2.tachibk")
        File(folder, "$base-2.tachibk").writeText("b")
        AutoBackup.freeFile(folder, base) shouldBe File(folder, "$base-3.tachibk")
    }

    @Test
    fun `the latest backup is the newest by time whichever app made it`(@TempDir folder: File) {
        File(folder, "zoukan_2024-01-06_10-00.tachibk").writeText("zoukan")
        File(folder, "zoukan_2024-01-05_10-00.tachibk").writeText("zoukan")
        val androidNewest = File(folder, "app.komikku_2024-01-07_08-00.tachibk").apply { writeText("android") }
        // Not a backup name, and a name whose time does not parse: neither is picked
        File(folder, "notes.txt").writeText("")
        File(folder, "zoukan_2099-13-45_99-99.tachibk").writeText("")
        val storage = mockk<ZoukanStorage> { every { autoBackups } returns folder }
        val preferences = mockk<BackupPreferences>()

        AutoBackup(storage, File("."), preferences, pref("")).latest() shouldBe androidNewest

        // A name that is not in the record is not rotated out either
        AutoBackup.prune(folder, emptyList(), File(folder, "zoukan_2024-01-06_10-00.tachibk"))
        File(folder, "zoukan_2099-13-45_99-99.tachibk").exists() shouldBe true
    }

    @Test
    fun `a backup skips history whose chapter was removed meanwhile and completes`(@TempDir folder: File) {
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(folder, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        try {
            runBlocking {
                val manga = Manga.create().copy(source = 1L, url = "/m", ogTitle = "m")
                val mangaId = handler.awaitOneExecutable(true) {
                    mangasQueries.insert(
                        source = manga.source,
                        url = manga.url,
                        artist = null,
                        author = null,
                        description = null,
                        genre = null,
                        title = manga.ogTitle,
                        status = 0L,
                        thumbnailUrl = null,
                        favorite = true,
                        lastUpdate = 0L,
                        nextUpdate = 0L,
                        calculateInterval = 0L,
                        initialized = true,
                        viewerFlags = 0L,
                        chapterFlags = 0L,
                        coverLastModified = 0L,
                        dateAdded = 0L,
                        updateStrategy = manga.updateStrategy,
                        version = 0L,
                        notes = "",
                        memo = manga.memo,
                    )
                    mangasQueries.selectLastInsertedRowId()
                }
                val keptChapterId = handler.awaitOneExecutable(true) {
                    chaptersQueries.insert(
                        mangaId, "/kept", "Kept", null, false, false, 0L, 1.0, 0L, 0L, 0L, 0L, Chapter.create().memo,
                    )
                    chaptersQueries.selectLastInsertedRowId()
                }
                // The second row's chapter is gone, as after a library update deleted it
                val getHistory = mockk<GetHistory> {
                    coEvery { await(mangaId) } returns listOf(
                        History(id = 1, chapterId = keptChapterId, readAt = Date(1000), readDuration = 5),
                        History(id = 2, chapterId = 999_999L, readAt = Date(2000), readDuration = 5),
                    )
                }
                val creator = MangaBackupCreator(
                    handler = handler,
                    getCategories = mockk(relaxed = true),
                    getHistory = getHistory,
                    sourceManager = mockk(relaxed = true),
                    getCustomMangaInfo = mockk(relaxed = true),
                    getFlatMetadataById = mockk(relaxed = true),
                    requireMetadataSource = false,
                )

                val result = creator(listOf(manga.copy(id = mangaId)), BackupOptions())

                result.single().history.map { it.url } shouldBe listOf("/kept")
            }
        } finally {
            driver.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a failed automatic backup leaves the existing backups byte for byte`(@TempDir folder: File) = runBlocking<Unit> {
        val names = listOf(
            "zoukan_2024-01-01_10-00.tachibk",
            "zoukan_2024-01-02_10-00.tachibk",
            "app.komikku_2024-01-03_10-00.tachibk",
        )
        val files = names.map { File(folder, it).apply { writeBytes(Random.nextBytes(256)) } }
        val hashes = files.associateWith { sha(it) }
        val storage = mockk<ZoukanStorage> { every { autoBackups } returns folder }
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns pref(0L)
        }

        // The runtime is not started, so the backup itself fails
        AutoBackup(storage, File("."), preferences, pref("")).run() shouldBe null

        files.forEach {
            it.exists() shouldBe true
            sha(it) shouldBe hashes.getValue(it)
        }
        folder.listFiles().orEmpty().map { it.name }.sorted() shouldBe names.sorted()
    }

    @Test
    fun `the restore gate clears when a restore fails`() = runBlocking<Unit> {
        runCatching { RestoreGate.whileRestoring<Unit> { throw IOException("The restore failed") } }
        RestoreGate.isRestoring shouldBe false
    }
}

class SafetySnapshotTest {
    @TempDir
    lateinit var root: File

    private val folder get() = File(root, "safety")
    private var clock = LocalDateTime.of(2026, 1, 1, 10, 0, 0)

    private fun snapshot(write: suspend (File) -> Unit = { it.writeText("backup") }, timeout: Long = 10_000) =
        SafetySnapshot(folder = { folder }, write = write, timeoutMillis = timeout, now = { clock.also { clock = clock.plusSeconds(1) } })

    @Test
    fun `a snapshot is written before the change and names its reason`() = runBlocking<Unit> {
        val file = snapshot().take(SnapshotReason.RESTORE).getOrThrow()

        file.parentFile shouldBe folder
        file.name shouldBe "safety_restore_2026-01-01_10-00-00.tachibk"
        file.readText() shouldBe "backup"
    }

    @Test
    fun `rotation keeps the newest five of each reason and touches nothing else`() = runBlocking<Unit> {
        val snapshots = snapshot()
        folder.mkdirs()
        val foreign = File(folder, "notes.txt").apply { writeText("mine") }
        val autoBackup = File(folder, "zoukan_2020-01-01_00-00.tachibk").apply { writeText("old") }
        val sync = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        val restores = (1..7).map { snapshots.take(SnapshotReason.RESTORE).getOrThrow() }

        folder.listFiles()!!.map { it.name } shouldContainExactlyInAnyOrder
            (restores.takeLast(SafetySnapshot.KEEP) + listOf(sync, foreign, autoBackup)).map { it.name }
    }

    @Test
    fun `a sync snapshot of an unchanged library keeps the last one instead of rotating older ones out`() = runBlocking<Unit> {
        var library = "the library before the first sync"
        val snapshots = snapshot(write = { it.writeText(library) })
        val good = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        library = "the library after a sync whose restore failed in part"
        val first = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        // The same entries fail on every sync and the library does not change: more failing syncs
        // than are kept must not push the snapshot before the first sync out
        repeat(SafetySnapshot.KEEP + 2) { snapshots.take(SnapshotReason.SYNC).getOrThrow() shouldBe first }

        folder.listFiles()!!.map { it.name } shouldContainExactlyInAnyOrder listOf(good.name, first.name)
        good.readText() shouldBe "the library before the first sync"
    }

    @Test
    fun `a sync snapshot is always taken when the library changed, even by one byte`() = runBlocking<Unit> {
        var library = "library 1"
        val snapshots = snapshot(write = { it.writeText(library) })
        val first = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        library = "library 2"

        val second = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        (second != first) shouldBe true
        second.readText() shouldBe "library 2"
        first.readText() shouldBe "library 1"
    }

    @Test
    fun `other reasons keep a snapshot for each change, even of the same library`() = runBlocking<Unit> {
        // The user starts each of these, and expects each to have its backup
        val snapshots = snapshot(write = { it.writeText("same") })
        val files = (1..3).map { snapshots.take(SnapshotReason.RESTORE).getOrThrow() }
        files.toSet().size shouldBe 3
        // A sync's snapshot is not compared with another reason's
        val sync = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        (sync in files) shouldBe false
    }

    @Test
    fun `two snapshots in the same second get different names`() = runBlocking<Unit> {
        val fixed = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
        val snapshots = SafetySnapshot(folder = { folder }, write = { it.writeText("b") }, now = { fixed })

        val first = snapshots.take(SnapshotReason.MIGRATION).getOrThrow()
        val second = snapshots.take(SnapshotReason.MIGRATION).getOrThrow()

        (first != second) shouldBe true
        second.name shouldBe "safety_migration_2026-01-01_10-00-00-2.tachibk"
    }

    @Test
    fun `a failed write is reported, not thrown, and the user decides`() = runBlocking<Unit> {
        val snapshots = snapshot(write = { throw IOException("disk full") })
        snapshots.take(SnapshotReason.CLEAR_DATABASE).isFailure shouldBe true

        var asked: String? = null
        snapshots.guard(SnapshotReason.CLEAR_DATABASE) {
            asked = it
            false
        } shouldBe false
        // In the app's words; the error itself is in the app log
        asked shouldBe localized(DMR.strings.safety_backup_could_not_be_written)
        snapshots.guard(SnapshotReason.CLEAR_DATABASE) { true } shouldBe true
    }

    @Test
    fun `a failed write's message never reaches the prompt or the sync notification, which can show paths`() {
        // An I/O error names the file, and so the data folder and the user's name; the text also goes
        // to the OS notification centre when sync fails
        val path = File(root, "backups/safety/safety_sync_2026-01-01_10-00-00.tachibk").path
        val texts = listOf(
            AccessDeniedException(path),
            FileSystemException(path, null, "No space left on device"),
            IOException("Could not write $path"),
            IllegalStateException(path),
        ).map { SafetySnapshot.errorText(it) }

        texts.forEach { text ->
            text shouldNotContain root.path
            text shouldBe localized(DMR.strings.safety_backup_could_not_be_written)
        }
    }

    @Test
    fun `a write that took too long says so in the app's words`() = runBlocking<Unit> {
        val error = snapshot(write = { awaitCancellation() }, timeout = 200).take(SnapshotReason.SYNC).exceptionOrNull()!!

        // Not kotlinx's "Timed out waiting for 200 ms"
        SafetySnapshot.errorText(error) shouldBe localized(DMR.strings.safety_backup_took_too_long)
    }

    @Test
    fun `running out of memory while writing the snapshot is a failed snapshot the user decides on`() = runBlocking<Unit> {
        // The backup of a large library is built in memory, and released when the write fails: the
        // user is asked to continue without a safety backup, as for any failed write
        val snapshots = snapshot(write = { throw OutOfMemoryError("Java heap space") })
        snapshots.take(SnapshotReason.CLEAR_DATABASE).isFailure shouldBe true
        var asked: String? = null
        snapshots.guard(SnapshotReason.RESTORE) {
            asked = it
            false
        } shouldBe false
        // Said in the app's words, not the JVM's ("Java heap space")
        asked shouldBe localized(DMR.strings.safety_backup_too_large)
    }

    @Test
    fun `other fatal errors while writing the snapshot still go on`() = runBlocking<Unit> {
        shouldThrow<NoClassDefFoundError> { snapshot(write = { throw NoClassDefFoundError("x") }).take(SnapshotReason.CLEAR_DATABASE) }
        shouldThrow<InternalError> { snapshot(write = { throw InternalError("x") }).take(SnapshotReason.CLEAR_DATABASE) }
    }

    @Test
    fun `a saved snapshot goes ahead without asking`() = runBlocking<Unit> {
        snapshot().guard(SnapshotReason.CLEAR_CHAPTERS) { error("must not ask") } shouldBe true
    }

    @Test
    fun `a prompt that cannot be shown cancels the change`() = runBlocking<Unit> {
        snapshot(write = { throw IOException("x") }).guard(SnapshotReason.MIGRATION) { throw HeadlessException() } shouldBe false
    }

    @Test
    fun `a write that never ends fails after the timeout instead of blocking the change`() = runBlocking<Unit> {
        val result = snapshot(write = { awaitCancellation() }, timeout = 200).take(SnapshotReason.RESTORE)
        result.isFailure shouldBe true
    }

    @Test
    fun `snapshots go to the app's data folder, not the shared automatic backup folder`() {
        val dirs = ZoukanDirs(root)
        val storage = ZoukanStorage(dirs, InMemoryPreferenceStore())
        val safety = SafetySnapshot.folder(dirs).absoluteFile.normalize()
        val autoBackups = storage.autoBackups.absoluteFile.normalize()

        safety.startsWith(dirs.root.absoluteFile.normalize()) shouldBe true
        safety.startsWith(autoBackups) shouldBe false
        // A storage folder chosen elsewhere (shared with the Android app) never holds them either
        val shared = File(root.parentFile, "shared-${System.nanoTime()}")
        storage.folderPreference.set(shared.absolutePath)
        safety.startsWith(storage.root.absoluteFile.normalize()) shouldBe false
    }

    /** Two sync snapshots in [folder]: an older one and the one just written. */
    private fun twoSnapshots(older: ByteArray, written: ByteArray): Pair<File, File> {
        folder.mkdirs()
        return File(folder, "safety_sync_2026-01-01_10-00-00.tachibk").apply { writeBytes(older) } to
            File(folder, "safety_sync_2026-01-01_10-00-01.tachibk").apply { writeBytes(written) }
    }

    @Test
    fun `snapshots are compared through a small buffer, so a difference in the last byte counts`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val (older, written) = twoSnapshots(bytes, bytes.copyOf().also { it[it.size - 1] = 7 })

        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 16) shouldBe null
        written.exists() shouldBe true

        written.writeBytes(bytes)
        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 16) shouldBe older
        written.exists() shouldBe false
    }

    @Test
    fun `a read that fails part way through the compare keeps the new snapshot`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val (older, written) = twoSnapshots(bytes, bytes)
        val failing = { file: File ->
            object : FilterInputStream(file.inputStream()) {
                var read = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read > 4_000) throw IOException("The disk went away")
                    return super.read(b, off, len).also { if (it > 0) read += it }
                }
            }
        }

        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 1024, open = failing) shouldBe null

        written.exists() shouldBe true
        older.exists() shouldBe true
    }

    @Test
    fun `a compare whose second file cannot be opened closes the first and keeps the new snapshot`() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val (older, written) = twoSnapshots(bytes, bytes)
        val opened = mutableListOf<InputStream>()
        var closed = 0
        val open = { file: File ->
            if (file == written) throw IllegalStateException("The file went away")
            object : FilterInputStream(file.inputStream()) {
                override fun close() {
                    closed++
                    super.close()
                }
            }.also { opened += it }
        }

        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, open = open) shouldBe null

        written.exists() shouldBe true
        older.exists() shouldBe true
        closed shouldBe opened.size
    }
}
