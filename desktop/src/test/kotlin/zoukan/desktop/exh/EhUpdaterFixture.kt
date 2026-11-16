package zoukan.desktop.exh

import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.eh.EHentaiUpdateHelper
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.ExhPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

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
