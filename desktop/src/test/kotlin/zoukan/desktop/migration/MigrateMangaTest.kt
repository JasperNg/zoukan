package zoukan.desktop.migration

import eu.kanade.domain.manga.interactor.UpdateManga
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
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
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

    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val updateChapter = mockk<UpdateChapter>(relaxed = true)
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
    private val insertTrack = mockk<InsertTrack>(relaxed = true)
    private val upsertHistory = mockk<UpsertHistory>(relaxed = true)
    private val getChapters = mockk<GetChaptersByMangaId>()
    private val getCategories = mockk<GetCategories>()
    private val getTracks = mockk<GetTracks>()
    private val getHistory = mockk<GetHistory>()

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean) =
        Chapter.create().copy(id = id, mangaId = mangaId, name = "Chapter $number", url = "/c/$id", chapterNumber = number, read = read)

    private val oldChapters = listOf(chapter(11, 1, 1.0, true), chapter(12, 1, 2.0, true))
    private val newChapters = listOf(chapter(21, 2, 1.0, false), chapter(22, 2, 2.0, false))

    private fun downloads(): DownloadManager {
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val libraryPreferences = mockk<tachiyomi.domain.library.service.LibraryPreferences> {
            every { disallowNonAsciiFilenames() } returns pref(false)
        }
        val downloadPreferences = mockk<tachiyomi.domain.download.service.DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(true)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk<CbzPassword>(), mockk(), mockk(), mockk(relaxed = true))
    }

    private fun <T> pref(value: T) = mockk<tachiyomi.core.common.preference.Preference<T>> { every { get() } returns value }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun build(downloadManager: DownloadManager, failing: BaseTracker): MigrateManga {
        val targetSource = mockk<Source>(relaxed = true)
        val sourceManager = mockk<SourceManager> {
            every { get(target.source) } returns targetSource
            every { get(current.source) } returns null
        }
        val trackerManager = mockk<TrackerManager> { every { trackers } returns listOf(failing) }
        return create(downloadManager, trackerManager, sourceManager)
    }

    private fun create(downloadManager: DownloadManager, trackerManager: TrackerManager, sourceManager: SourceManager) = MigrateManga(
        mockk(relaxed = true),
        trackerManager,
        sourceManager,
        downloadManager,
        updateManga,
        getChapters,
        updateChapter,
        getCategories,
        setMangaCategories,
        getTracks,
        insertTrack,
        mockk<RefreshManga>(relaxed = true),
        getHistory,
        upsertHistory,
        handler,
    )

    private var inTransaction = false

    // Runs the block like the real handler does, and says whether it is inside a transaction
    private val handler = mockk<DatabaseHandler> {
        coEvery { await<Any?>(any(), any()) } coAnswers {
            val block = secondArg<suspend Database.() -> Any?>()
            inTransaction = firstArg()
            try {
                block(mockk<Database>(relaxed = true))
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

    @Test
    fun `a tracker that fails to migrate leaves both entries unchanged`() = runBlocking<Unit> {
        stubReads()
        val manager = downloads()
        val dir = File(temp, "downloads/old").apply { mkdirs() }
        val files = oldChapters.map { File(dir, "Chapter ${it.chapterNumber}.cbz").apply { writeBytes(ByteArray(64) { b -> (b + it.id).toByte() }) } }
        val before = files.associateWith { sha(it) to it.length() }
        val migrate = build(manager, failingTracker())

        shouldThrow<IOException> {
            migrate(
                current,
                target,
                replace = true,
                flags = setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY, MigrationFlag.TRACK, MigrationFlag.REMOVE_DOWNLOAD),
            )
        }

        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
        coVerify(exactly = 0) { upsertHistory.awaitAll(any()) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
        coVerify(exactly = 0) { insertTrack.awaitAll(any()) }
        coVerify(exactly = 0) { updateManga.awaitAll(any()) }
        files.forEach { (sha(it) to it.length()) shouldBe before[it] }
    }

    @Test
    fun `every database step of a migration runs in one transaction`() = runBlocking<Unit> {
        stubReads()
        val seen = mutableListOf<Pair<String, Boolean>>()
        coEvery { updateChapter.awaitAll(any()) } coAnswers { seen += "chapters" to inTransaction }
        coEvery { upsertHistory.awaitAll(any()) } coAnswers { seen += "history" to inTransaction }
        coEvery { setMangaCategories.await(any(), any()) } coAnswers { seen += "categories" to inTransaction }
        coEvery { insertTrack.awaitAll(any()) } coAnswers { seen += "tracks" to inTransaction }
        coEvery { updateManga.awaitAll(any()) } coAnswers {
            seen += "manga" to inTransaction
            true
        }
        val trackerManager = mockk<TrackerManager> { every { trackers } returns emptyList() }
        val sourceManager = mockk<SourceManager> {
            every { get(target.source) } returns mockk<Source>(relaxed = true)
            every { get(current.source) } returns null
        }

        create(downloads(), trackerManager, sourceManager)(
            current,
            target,
            replace = true,
            flags = setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY, MigrationFlag.TRACK),
        )

        seen.map { it.first } shouldBe listOf("chapters", "history", "categories", "tracks", "manga")
        seen.all { it.second } shouldBe true
    }
}
