package zoukan.desktop.migration

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A migration writes all of its database changes or none, and never touches the old entry's downloads on failure. */
class MigrateMangaTest {

    @TempDir
    lateinit var temp: File

    private val current = Manga.create().copy(id = 1, source = 10, ogTitle = "Old")
    private val target = Manga.create().copy(id = 2, source = 20, ogTitle = "New", initialized = true)

    private val mangaRepository = mockk<MangaRepository>(relaxed = true) {
        coEvery { updateAll(any()) } returns true
    }
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val trackRepository = mockk<TrackRepository>(relaxed = true)
    private val getChapters = mockk<GetChaptersByMangaId>()
    private val getCategories = mockk<GetCategories>()
    private val getTracks = mockk<GetTracks>()
    private val getHistory = mockk<GetHistory>()

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean) =
        Chapter.create().copy(id = id, mangaId = mangaId, name = "Chapter $number", url = "/c/$id", chapterNumber = number, read = read)

    private val oldChapters = listOf(chapter(11, 1, 1.0, true), chapter(12, 1, 2.0, true))
    private val newChapters = listOf(chapter(21, 2, 1.0, false), chapter(22, 2, 2.0, false))

    private val downloadSource = mockk<Source>(relaxed = true)

    private val paths by lazy {
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val libraryPreferences = mockk<tachiyomi.domain.library.service.LibraryPreferences> {
            every { disallowNonAsciiFilenames() } returns pref(false)
        }
        DownloadPaths(storage, downloadSourceManager, libraryPreferences, downloadPreferences)
    }

    private val downloadSourceManager = mockk<SourceManager>(relaxed = true) {
        every { getOrStub(any()) } returns downloadSource
    }

    private val downloadPreferences = mockk<tachiyomi.domain.download.service.DownloadPreferences> {
        every { includeChapterUrlHash() } returns pref(false)
        every { saveChaptersAsCBZ() } returns pref(true)
        every { downloadCacheRenewInterval() } returns pref(1)
    }

    private fun downloads(): DownloadManager =
        DownloadManager(ZoukanDirs(temp), paths, downloadSourceManager, downloadPreferences, mockk<CbzPassword>(), mockk(), mockk(), mockk(relaxed = true))

    /** The old entry's downloaded chapters, where the download manager keeps them. */
    private fun oldDownloads(): List<File> {
        val dir = paths.mangaDir(current).apply { mkdirs() }
        return oldChapters.map { File(dir, paths.chapterDirName(it) + ".cbz").apply { writeBytes(ByteArray(64) { b -> (b + it.id).toByte() }) } }
    }

    private fun <T> pref(value: T) = mockk<tachiyomi.core.common.preference.Preference<T>> { every { get() } returns value }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun build(downloadManager: DownloadManager, trackers: List<BaseTracker> = emptyList()): MigrateManga {
        val sourceManager = mockk<SourceManager> {
            every { get(target.source) } returns mockk<Source>(relaxed = true)
            every { get(current.source) } returns null
        }
        val trackerManager = mockk<TrackerManager> { every { this@mockk.trackers } returns trackers }
        return MigrateManga(
            mockk(relaxed = true),
            trackerManager,
            sourceManager,
            downloadManager,
            mangaRepository,
            getChapters,
            chapterRepository,
            getCategories,
            getTracks,
            trackRepository,
            mockk<RefreshManga>(relaxed = true),
            getHistory,
            handler,
        )
    }

    private var inTransaction = false
    private val database = mockk<Database>(relaxed = true)

    // Runs the block like the real handler does, and says whether it is inside a transaction
    private val handler = mockk<DatabaseHandler> {
        coEvery { await<Any?>(any(), any()) } coAnswers {
            val block = secondArg<suspend Database.() -> Any?>()
            inTransaction = firstArg()
            try {
                block(database)
            } finally {
                inTransaction = false
            }
        }
    }

    private fun failingTracker(): BaseTracker {
        val tracker = mockk<BaseTracker>(relaxed = true, moreInterfaces = arrayOf(EnhancedTracker::class))
        every { (tracker as EnhancedTracker).isTrackFrom(any(), any(), any()) } returns true
        every { (tracker as EnhancedTracker).migrateTrack(any(), any(), any()) } throws IOException("tracker down")
        return tracker
    }

    private fun stubReads() {
        coEvery { getChapters.await(1L) } returns oldChapters
        coEvery { getChapters.await(2L) } returns newChapters
        coEvery { getHistory.await(1L) } returns emptyList()
        coEvery { getCategories.await(1L) } returns listOf(Category(5, "Five", 0, 0, false))
        coEvery { getTracks.await(1L) } returns listOf(Track(1, 1, 1, 1, null, "t", 2.0, 0, 0, 0.0, "", 0, 0, false))
    }

    private val allSteps = setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY, MigrationFlag.TRACK, MigrationFlag.REMOVE_DOWNLOAD)

    @Test
    fun `a tracker that fails to migrate leaves both entries unchanged`() = runBlocking<Unit> {
        stubReads()
        val files = oldDownloads()
        val before = files.associateWith { sha(it) to it.length() }
        val migrate = build(downloads(), listOf(failingTracker()))

        shouldThrow<IOException> { migrate(current, target, replace = true, flags = allSteps) }

        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
        coVerify(exactly = 0) { mangaRepository.setMangaCategories(any(), any()) }
        coVerify(exactly = 0) { trackRepository.insertAll(any()) }
        coVerify(exactly = 0) { mangaRepository.updateAll(any()) }
        files.forEach { (sha(it) to it.length()) shouldBe before[it] }
    }

    @Test
    fun `a database step that fails stops the migration before the old downloads are deleted`() = runBlocking<Unit> {
        stubReads()
        // The repository reports a failed update with false, as the real one does
        coEvery { mangaRepository.updateAll(any()) } returns false
        val files = oldDownloads()
        val before = files.associateWith { sha(it) to it.length() }

        shouldThrow<IllegalStateException> { build(downloads())(current, target, replace = true, flags = allSteps) }

        files.forEach { (sha(it) to it.length()) shouldBe before[it] }
    }

    @Test
    fun `a migration that succeeds deletes the old downloads when asked`() = runBlocking<Unit> {
        // Proves the survival checks above look at the files the download manager would delete
        stubReads()
        val files = oldDownloads()

        build(downloads())(current, target, replace = true, flags = allSteps)

        files.forEach { it.exists() shouldBe false }
    }

    @Test
    fun `every database step of a migration runs in one transaction`() = runBlocking<Unit> {
        stubReads()
        coEvery { getHistory.await(1L) } returns listOf(
            tachiyomi.domain.history.model.History(1, 11, java.util.Date(5), 7),
        )
        val seen = mutableListOf<Pair<String, Boolean>>()
        coEvery { chapterRepository.updateAll(any()) } coAnswers { seen += "chapters" to inTransaction }
        every { database.historyQueries.upsert(any(), any(), any()) } answers {
            seen += "history" to inTransaction
            mockk(relaxed = true)
        }
        coEvery { mangaRepository.setMangaCategories(any(), any()) } coAnswers { seen += "categories" to inTransaction }
        coEvery { trackRepository.insertAll(any()) } coAnswers { seen += "tracks" to inTransaction }
        coEvery { mangaRepository.updateAll(any()) } coAnswers {
            seen += "manga" to inTransaction
            true
        }

        build(downloads())(
            current,
            target,
            replace = true,
            flags = setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY, MigrationFlag.TRACK),
        )

        seen.map { it.first } shouldBe listOf("chapters", "history", "categories", "tracks", "manga")
        seen.all { it.second } shouldBe true
    }
}
