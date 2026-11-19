package zoukan.desktop.exh

import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.eh.EHentaiUpdateHelper
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.ExhPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.backup.RestoreGate
import java.io.IOException

/**
 * An [EhGalleryUpdater] over three E-Hentai galleries whose remote update stops at the first
 * gallery until [release] is completed, so a test can act while a round is running. Every gallery
 * ends its turn with no chapters, so nothing past the remote update and the chapter lookup runs.
 */
class EhUpdaterFixture(galleryIds: List<Long> = listOf(1L, 2L, 3L)) {
    /** Completed when the first gallery's remote update starts. */
    val entered = CompletableDeferred<Unit>()

    /** Completed by the test to let the remote updates return. */
    val release = CompletableDeferred<Unit>()

    private val galleries = galleryIds.map { galleryId ->
        mockk<Manga> {
            every { id } returns galleryId
            every { source } returns SOURCE_ID
            every { title } returns "Gallery $galleryId"
        }
    }

    val updateMangaFromRemote = mockk<UpdateMangaFromRemote>()
    val getChaptersByMangaId = mockk<GetChaptersByMangaId>()

    val updater: EhGalleryUpdater

    init {
        val getExhFavorites = mockk<GetExhFavoriteMangaWithMetadata> {
            coEvery { await() } returns galleries
        }
        val getFlatMetadata = mockk<GetFlatMetadataById> {
            coEvery { await(any()) } answers {
                val mangaId = firstArg<Long>()
                EHentaiSearchMetadata().apply {
                    this.mangaId = mangaId
                    gId = "$mangaId"
                    gToken = "t"
                    lastUpdateCheck = 0
                }.flatten()
            }
        }
        val ehentai = mockk<EHentai>()
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(any()) } returns ehentai
        coEvery { getChaptersByMangaId.await(any()) } returns emptyList()
        coEvery {
            updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        } coAnswers {
            entered.complete(Unit)
            release.await()
            Result.success(RemoteMangaUpdate(arg(1), emptyList()))
        }
        val stats = mockk<Preference<String>>(relaxed = true)
        val exhPreferences = mockk<ExhPreferences> {
            every { exhAutoUpdateStats() } returns stats
        }
        updater = EhGalleryUpdater(
            exhPreferences = exhPreferences,
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            sourceManager = sourceManager,
            updateHelper = mockk<EHentaiUpdateHelper>(),
            updateMangaFromRemote = updateMangaFromRemote,
            getChaptersByMangaId = getChaptersByMangaId,
            getFlatMetadataById = getFlatMetadata,
            insertFlatMetadata = mockk<InsertFlatMetadata>(relaxed = true),
            getExhFavoriteMangaWithMetadata = getExhFavorites,
        )
    }

    private companion object {
        const val SOURCE_ID = 4_000_000L
    }
}

/** Pausing the E-Hentai updates (the favorites sync does) must also stop a round that is running. */
class EhGalleryUpdaterPauseTest {

    @Test
    fun `pausing stops a running round after the current gallery`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }

        withTimeout(10_000) { fixture.entered.await() }
        fixture.updater.setPaused(true)
        fixture.release.complete(Unit)
        withTimeout(10_000) { round.await() }

        coVerify(exactly = 1) {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        }
    }

    @Test
    fun `cancelling the background update waits for the running round to end`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }
        withTimeout(10_000) { fixture.entered.await() }
        fixture.updater.setPaused(true)

        // Runs until it suspends: it returns at once unless it waits for the round
        val idle = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { fixture.updater.awaitIdle() }
        idle.isCompleted shouldBe false

        fixture.release.complete(Unit)
        withTimeout(10_000) { idle.await() }
        withTimeout(10_000) { round.await() }
    }

    @Test
    fun `cancellation during remote update propagates instead of counting as a network failure`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture(listOf(1L, 2L, 3L))
        coEvery {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        } throws CancellationException("cancelled")

        shouldThrow<CancellationException> { fixture.updater.run() }

        coVerify(exactly = 1) {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        }
    }

    @Test
    fun `IOException still counts as a network failure and stops the round after exceeding the limit`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture((1L..8L).toList())
        coEvery {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        } throws IOException("network down")

        fixture.updater.run() shouldBe true

        // MAX_UPDATE_FAILURES is 5: stops before gallery 7 when failures > 5 (6 attempts)
        coVerify(exactly = 6) {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        }
    }

    @Test
    fun `GalleryNotFoundException marks metadata aged and does not count as a network failure`() = runBlocking<Unit> {
        val galleries = (1L..7L).map { id ->
            mockk<Manga> {
                every { this@mockk.id } returns id
                every { source } returns 4_000_000L
                every { title } returns "Gallery $id"
            }
        }
        val inserted = mutableListOf<EHentaiSearchMetadata>()
        val insertFlatMetadata = mockk<InsertFlatMetadata> {
            coEvery { await(any<exh.metadata.metadata.RaisedSearchMetadata>()) } answers {
                inserted += firstArg<EHentaiSearchMetadata>()
            }
        }
        val getFlatMetadata = mockk<GetFlatMetadataById> {
            coEvery { await(any()) } answers {
                val id = firstArg<Long>()
                EHentaiSearchMetadata().apply {
                    mangaId = id
                    gId = "$id"
                    gToken = "t"
                    lastUpdateCheck = 0
                }.flatten()
            }
        }
        val updateMangaFromRemote = mockk<UpdateMangaFromRemote> {
            coEvery {
                invoke(
                    source = any(),
                    manga = any(),
                    fetchDetails = any(),
                    fetchChapters = any(),
                    manualFetch = any(),
                    fetchWindow = any(),
                    throttleFunc = any(),
                )
            } throws EHentai.GalleryNotFoundException(IllegalStateException("gone"))
        }
        val ehentai = mockk<EHentai>()
        val sourceManager = mockk<SourceManager>().also { every { it.get(any()) } returns ehentai }
        val exhPreferences = mockk<ExhPreferences> {
            every { exhAutoUpdateStats() } returns mockk<Preference<String>>(relaxed = true)
        }
        val updater = EhGalleryUpdater(
            exhPreferences = exhPreferences,
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            sourceManager = sourceManager,
            updateHelper = mockk<EHentaiUpdateHelper>(),
            updateMangaFromRemote = updateMangaFromRemote,
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            getFlatMetadataById = getFlatMetadata,
            insertFlatMetadata = insertFlatMetadata,
            getExhFavoriteMangaWithMetadata = mockk<GetExhFavoriteMangaWithMetadata> {
                coEvery { await() } returns galleries
            },
        )

        updater.run() shouldBe true

        inserted.size shouldBe 7
        inserted.all { it.aged } shouldBe true
    }
}

/** A backup restore rewrites the library, so the E-Hentai update must not write while it waits or runs. */
class EhGalleryUpdaterRestoreTest {

    @Test
    fun `a restore that is asked for stops the round before the next gallery`() = runBlocking<Unit> {
        val fixture = EhUpdaterFixture()
        val round = async(Dispatchers.Default) { fixture.updater.run() }

        withTimeout(10_000) { fixture.entered.await() }
        RestoreGate.pending.incrementAndGet()
        try {
            fixture.release.complete(Unit)
            withTimeout(10_000) { round.await() }
        } finally {
            RestoreGate.pending.decrementAndGet()
        }

        coVerify(exactly = 1) {
            fixture.updateMangaFromRemote.invoke(
                source = any(),
                manga = any(),
                fetchDetails = any(),
                fetchChapters = any(),
                manualFetch = any(),
                fetchWindow = any(),
                throttleFunc = any(),
            )
        }
        // No further entry was read or written after the restore was asked for
        coVerify(exactly = 1) { fixture.getChaptersByMangaId.await(any()) }
    }
}
