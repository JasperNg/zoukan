package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
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
