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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

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
