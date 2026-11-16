package zoukan.desktop.exh

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.utils.MdUtil
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import zoukan.desktop.backup.RestoreGate

class MangaDexSyncTest {

    private val getManga = mockk<GetManga>()
    private val networkToLocalManga = mockk<NetworkToLocalManga>()
    private val updateManga = mockk<UpdateManga>()
    private val updateMangaFromRemote = mockk<UpdateMangaFromRemote>()
    private val insertFlatMetadata = mockk<InsertFlatMetadata>()
    private val mangaDex = mockk<MangaDex>()

    @AfterEach
    fun tearDown() {
        unmockkObject(MdUtil.Companion)
    }

    private fun sync(): MangaDexSync {
        val statuses = mockk<Preference<Set<String>>> {
            every { get() } returns setOf("1")
        }
        val sourcePreferences = mockk<SourcePreferences> {
            every { mangadexSyncToLibraryIndexes() } returns statuses
        }
        mockkObject(MdUtil.Companion)
        every { MdUtil.getEnabledMangaDex(any(), any()) } returns mangaDex
        val follows = listOf(1, 2).map { n ->
            SManga.create().apply {
                url = "/title/$n"
                title = "Follow $n"
            } to MangaDexSearchMetadata().apply { followStatus = 1 }
        }
        every { mangaDex.id } returns 7L
        coEvery { mangaDex.fetchAllFollows() } returns follows
        // Entries the library already has but has not favorited: the sync favorites them. (A new
        // entry needs Injekt for Manga.create, which a unit test does not set up.)
        val local = mockk<Manga>(relaxed = true) {
            every { id } returns 5L
            every { favorite } returns false
        }
        coEvery { getManga.await(any<String>(), any()) } returns local
        coEvery { updateManga.awaitUpdateFavorite(any(), any()) } returns true
        coEvery { networkToLocalManga(any<Manga>()) } returns local
        coEvery { updateMangaFromRemote.invoke(manga = any(), manualFetch = any()) } returns
            Result.success(RemoteMangaUpdate(local, emptyList()))
        coEvery { insertFlatMetadata.await(any<RaisedSearchMetadata>()) } returns Unit
        return MangaDexSync(
            sourcePreferences = sourcePreferences,
            sourceManager = mockk<SourceManager>(),
            trackerManager = mockk<TrackerManager>(),
            getManga = getManga,
            getFavorites = mockk<GetFavorites>(),
            networkToLocalManga = networkToLocalManga,
            updateManga = updateManga,
            updateMangaFromRemote = updateMangaFromRemote,
            insertFlatMetadata = insertFlatMetadata,
            getTracks = mockk<GetTracks>(),
            insertTrack = mockk<InsertTrack>(),
        )
    }

    @Test
    fun `a pending restore stops the follow sync before it writes`() = runBlocking<Unit> {
        val sync = sync()
        RestoreGate.pending.incrementAndGet()
        try {
            runCatching { sync.syncFollows() }
        } finally {
            RestoreGate.pending.decrementAndGet()
        }

        // The sync did get as far as the follows, so the checks below are about the restore
        coVerify(exactly = 1) { mangaDex.fetchAllFollows() }
        coVerify(exactly = 0) { networkToLocalManga(any<Manga>()) }
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        coVerify(exactly = 0) { updateMangaFromRemote.invoke(manga = any(), manualFetch = any()) }
        coVerify(exactly = 0) { insertFlatMetadata.await(any<RaisedSearchMetadata>()) }
    }
}
