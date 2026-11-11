package zoukan.desktop.library

import eu.kanade.domain.track.interactor.AddTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import java.time.Instant

/**
 * Adding an entry to the library, as Android's manga and browse screens do: the duplicate check,
 * the default category or a choice of categories, the enhanced trackers, and (from Browse) the
 * "Fetch metadata / chapters when adding to library" settings.
 */
class AddToLibrary(
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val mangaRepository: MangaRepository,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val libraryPreferences: LibraryPreferences,
    private val addTracks: AddTracks,
    private val refreshManga: RefreshManga,
    private val sourceManager: SourceManager,
) {
    /** Where a new entry goes: straight into [categoryIds], or the user picks from [ask]. */
    data class Plan(val categoryIds: List<Long>, val ask: List<Category>? = null)

    /** Library entries with the same title, as Android's possible duplicates. */
    suspend fun duplicates(manga: Manga): List<MangaWithChapterCount> =
        getDuplicateLibraryManga(manga).filter { it.manga.id != manga.id }

    /**
     * Settings > Library > Default category, as on Android: a category, the default one (0), or
     * ask (-1); with no categories there is nothing to ask.
     */
    suspend fun plan(): Plan {
        val categories = getCategories.await().filterNot { it.isSystemCategory }
        val defaultId = libraryPreferences.defaultCategory().get().toLong()
        val defaultCategory = categories.find { it.id == defaultId }
        return when {
            defaultCategory != null -> Plan(listOf(defaultCategory.id))
            defaultId == 0L || categories.isEmpty() -> Plan(emptyList())
            else -> Plan(emptyList(), ask = categories)
        }
    }

    /**
     * Adds [manga] to [categoryIds], then binds the enhanced trackers in the background. With
     * [fetchOnAdd] (adding from Browse, where the entry may not be fetched yet), the "Fetch
     * metadata" and "Fetch chapters when adding to library" settings fetch it too.
     */
    suspend fun add(manga: Manga, categoryIds: List<Long>, fetchOnAdd: Boolean = false) {
        mangaRepository.update(MangaUpdate(id = manga.id, favorite = true, dateAdded = Instant.now().toEpochMilli()))
        setMangaCategories.await(manga.id, categoryIds)
        val source = sourceManager.get(manga.source) ?: return
        val fetchDetails = fetchOnAdd && libraryPreferences.fetchMetadataOnAdd().get()
        val fetchChapters = fetchOnAdd && libraryPreferences.fetchChaptersOnAdd().get()
        AppScope.launch(Dispatchers.IO) {
            runCatching { addTracks.bindEnhancedTrackers(manga, source) }
                .onFailure { if (it !is CancellationException) Toasts.error("Tracking failed", it) }
            if (fetchDetails || fetchChapters) {
                try {
                    refreshManga(manga, fetchDetails = fetchDetails, fetchChapters = fetchChapters, source = source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Could not fetch ${manga.title} after adding it" }
                }
            }
        }
    }

    suspend fun remove(manga: Manga) {
        mangaRepository.update(MangaUpdate(id = manga.id, favorite = false, dateAdded = 0))
    }
}
