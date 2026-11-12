package zoukan.desktop

import android.app.Application
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.eh.EHentaiUpdateHelper
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.CustomMangaRepositoryImpl
import tachiyomi.data.manga.FavoritesEntryRepositoryImpl
import tachiyomi.data.manga.MangaMergeRepositoryImpl
import tachiyomi.data.manga.MangaMetadataRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.source.FeedSavedSearchRepositoryImpl
import tachiyomi.data.source.SavedSearchRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.DeleteByMergeId
import tachiyomi.domain.manga.interactor.DeleteMangaById
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.InsertFavoriteEntryAlternative
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.InsertMergedReference
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.manga.repository.FavoritesEntryRepository
import tachiyomi.domain.manga.repository.MangaMergeRepository
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchById
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.repository.FeedSavedSearchRepository
import tachiyomi.domain.source.repository.SavedSearchRepository
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.interactor.IsTrackUnfollowed
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.exh.EhLogin
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.migration.MigrateManga
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.track.TrackerLogin
import zoukan.desktop.ui.common.DesktopPreferences

/**
 * Repositories, interactors and preference groups of the shared domain and data modules, registered
 * as the Android app's DomainModule and PreferenceModule register them. Only what desktop code uses
 * so far: backups need the library, chapters, history, tracking and SY data; the UI needs the
 * library, browsing, updates, history and reading progress.
 */
class DesktopDomainModule : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { LibraryPreferences(get()) }
        addSingletonFactory { BackupPreferences(get()) }
        addSingletonFactory { DownloadPreferences(get()) }

        addSingletonFactory<CategoryRepository> { CategoryRepositoryImpl(get()) }
        addFactory { GetCategories(get()) }

        addSingletonFactory<MangaRepository> { MangaRepositoryImpl(get()) }
        addFactory { GetFavorites(get()) }
        addFactory { GetMangaByUrlAndSourceId(get()) }
        addFactory { FetchInterval(get()) }
        addFactory { tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga(get()) }

        addSingletonFactory<ChapterRepository> { ChapterRepositoryImpl(get()) }
        addFactory { GetChaptersByMangaId(get()) }

        addSingletonFactory<HistoryRepository> { HistoryRepositoryImpl(get()) }
        addFactory { GetHistory(get()) }

        addSingletonFactory<TrackRepository> { TrackRepositoryImpl(get()) }
        addFactory { GetTracks(get()) }
        addFactory { InsertTrack(get()) }

        // SY
        addSingletonFactory<MangaMergeRepository> { MangaMergeRepositoryImpl(get()) }
        addFactory { GetMergedManga(get()) }
        addFactory { tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading(get()) }
        addSingletonFactory<MangaMetadataRepository> { MangaMetadataRepositoryImpl(get()) }
        addFactory { GetFlatMetadataById(get()) }
        addFactory { InsertFlatMetadata(get()) }
        addSingletonFactory<CustomMangaRepository> { CustomMangaRepositoryImpl(get<Application>()) }
        addFactory { GetCustomMangaInfo(get()) }
        addFactory { SetCustomMangaInfo(get()) }
        addFactory { eu.kanade.domain.manga.interactor.GetSortTag(get()) }
        addFactory { eu.kanade.domain.manga.interactor.CreateSortTag(get(), get()) }
        addFactory { eu.kanade.domain.manga.interactor.DeleteSortTag(get(), get()) }
        addFactory { eu.kanade.domain.manga.interactor.ReorderSortTag(get(), get()) }

        // UI (Phase 4)
        addSingletonFactory { DesktopPreferences(get()) }
        addSingletonFactory { eu.kanade.domain.ui.UiPreferences(get()) }
        addSingletonFactory { tachiyomi.domain.updates.service.UpdatesPreferences(get()) }
        addSingletonFactory { tachiyomi.domain.history.service.HistoryPreferences(get()) }
        addFactory { tachiyomi.domain.category.interactor.ResetCategoryFlags(get(), get()) }
        addFactory { GetLibraryManga(get()) }
        addFactory { GetManga(get()) }
        addFactory { GetMangaWithChapters(get(), get()) }
        addFactory { NetworkToLocalManga(get()) }
        addFactory { SetMangaCategories(get()) }
        addFactory { tachiyomi.domain.category.interactor.SetSortModeForCategory(get(), get()) }
        addFactory { CreateCategoryWithName(get(), get()) }
        addFactory { RenameCategory(get()) }
        addFactory { DeleteCategory(get(), get(), get()) }
        addFactory { tachiyomi.domain.category.interactor.ReorderCategory(get()) }
        addFactory { tachiyomi.domain.category.interactor.HideCategory(get()) }
        addFactory { GetChapter(get()) }
        addFactory { UpdateChapter(get()) }
        addFactory { ShouldUpdateDbChapter() }
        addFactory { GetMergedReferencesById(get()) }
        addFactory { GetMergedChaptersByMangaId(get(), get()) }
        addFactory { GetNextChapters(get(), get(), get(), get()) }
        addFactory { UpsertHistory(get()) }
        addFactory { RemoveHistory(get()) }
        addSingletonFactory<UpdatesRepository> { UpdatesRepositoryImpl(get()) }
        addFactory { GetUpdates(get()) }
        addFactory { GetUpcomingManga(get()) }
        addFactory { RemoveExtensionStore(get()) }
        addFactory { GetExcludedScanlators(get()) }
        addFactory { SyncChapters(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        addFactory { RefreshManga(get(), get(), get(), get(), get()) }

        // Downloads, library updates and background work (Phase 5)
        addFactory { FilterChaptersForDownload(get(), get(), get(), get()) }
        addSingletonFactory { DownloadPaths(get(), get(), get(), get()) }
        addSingletonFactory { DownloadManager(get(), get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory {
            LibraryUpdater(
                get(), get(), get(), get(), get(), get(), get(), get(), get(),
                get(), get(), get(), get(), get(), get(),
                { uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>() },
            )
        }
        addSingletonFactory { AutoBackup(get(), get<ZoukanDirs>().android, get()) }
        addSingletonFactory { BackgroundJobs(get(), get(), get(), get()) }
        addSingletonFactory { zoukan.desktop.discord.DiscordPresence(get(), get(), get()) }

        // Trackers (Phase 6): the Android app's tracker code, see app-shared.txt
        addSingletonFactory { TrackPreferences(get()) }
        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(get()) }
        addFactory { DeleteTrack(get()) }
        addFactory { IsTrackUnfollowed() }
        addFactory { GetTracksPerManga(get(), get()) }
        addFactory { AddTracks(get(), get(), get(), get()) }
        addFactory { RefreshTracks(get(), get(), get(), get()) }
        addFactory { SyncChapterProgressWithTrack(get(), get(), get()) }
        addFactory { TrackChapters(get(), get(), get(), get(), get()) }
        addSingletonFactory { TrackerLogin(get()) }

        // E-Hentai and merged manga (Phase 6): the Android app's sources, see app-shared.txt
        addFactory { UpdateManga(get(), get()) }
        addFactory { zoukan.desktop.manga.EditManga(get(), get(), get()) }
        addFactory { GetChapterByUrl(get()) }
        addSingletonFactory<FavoritesEntryRepository> { FavoritesEntryRepositoryImpl(get()) }
        addFactory { InsertFavoriteEntryAlternative(get()) }
        addSingletonFactory { EHentaiUpdateHelper(get<Application>()) }
        addFactory { UpdateMangaFromRemote(get(), get()) }
        addSingletonFactory { eu.kanade.tachiyomi.data.download.DownloadManager(get()) }
        addSingletonFactory { EhLogin(get(), get()) }
        addFactory { tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata(get()) }
        addFactory { tachiyomi.domain.manga.interactor.GetFavoriteEntries(get()) }
        addFactory { tachiyomi.domain.category.interactor.UpdateCategory(get()) }
        addFactory { tachiyomi.domain.manga.interactor.InsertFavoriteEntries(get()) }
        addFactory { tachiyomi.domain.manga.interactor.DeleteFavoriteEntries(get()) }
        addSingletonFactory { zoukan.desktop.exh.EhGalleryUpdater(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { zoukan.desktop.exh.MangaDexSync(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { exh.favorites.FavoritesSyncHelper(get<android.app.Application>()) }
        addFactory { InsertMergedReference(get()) }
        addFactory { DeleteMangaById(get()) }
        addFactory { DeleteByMergeId(get()) }
        addFactory { DeleteMergeById(get()) }
        addFactory { UpdateMergedSettings(get()) }

        // Saved searches and feeds (Phase 6)
        addSingletonFactory<SavedSearchRepository> { SavedSearchRepositoryImpl(get()) }
        addSingletonFactory<FeedSavedSearchRepository> { FeedSavedSearchRepositoryImpl(get()) }
        addFactory { FilterSerializer() }
        addFactory { GetSavedSearchById(get()) }
        addFactory { GetSavedSearchBySourceId(get()) }
        addFactory { GetExhSavedSearch(get(), get(), get()) }
        addFactory { InsertSavedSearch(get()) }
        addFactory { DeleteSavedSearchById(get()) }
        addFactory { GetFeedSavedSearchGlobal(get()) }
        addFactory { GetSavedSearchGlobalFeed(get()) }
        addFactory { tachiyomi.domain.source.interactor.GetFeedSavedSearchBySourceId(get()) }
        addFactory { tachiyomi.domain.source.interactor.GetSavedSearchBySourceIdFeed(get()) }
        addFactory { InsertFeedSavedSearch(get()) }
        addFactory { DeleteFeedSavedSearchById(get()) }
        // ZOUKAN -->
        addFactory { tachiyomi.domain.source.interactor.ReorderFeed(get()) }
        // ZOUKAN <--

        addFactory { GetTotalReadDuration(get()) }

        // Library update errors (Komikku): the Android tables, so both apps show the same list
        addSingletonFactory<tachiyomi.domain.libraryUpdateError.repository.LibraryUpdateErrorWithRelationsRepository> {
            tachiyomi.data.libraryUpdateError.LibraryUpdateErrorWithRelationsRepositoryImpl(get())
        }
        addFactory { tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations(get()) }
        addSingletonFactory<tachiyomi.domain.libraryUpdateErrorMessage.repository.LibraryUpdateErrorMessageRepository> {
            tachiyomi.data.libraryUpdateErrorMessage.LibraryUpdateErrorMessageRepositoryImpl(get())
        }
        addFactory { tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages(get()) }
        addFactory { tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages(get()) }
        addSingletonFactory<tachiyomi.domain.libraryUpdateError.repository.LibraryUpdateErrorRepository> {
            tachiyomi.data.libraryUpdateError.LibraryUpdateErrorRepositoryImpl(get())
        }
        addFactory { tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors(get()) }
        addFactory { tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors(get()) }
        addFactory { zoukan.desktop.library.LibraryUpdateErrors(get(), get(), get()) }
        addFactory { zoukan.desktop.library.AddToLibrary(get(), get(), get(), get(), get(), get(), get(), get()) }

        // Migration (Phase 6)
        addFactory { MigrateManga(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    }
}
