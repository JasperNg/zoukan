package zoukan.desktop.ui.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.library.AddToLibrary
import zoukan.i18n.desktop.DMR

/**
 * Coordinates the shared add-to-library decision flow across Browse ([zoukan.desktop.ui.browse.QuickLibraryToggle],
 * [zoukan.desktop.ui.browse.BulkFavorite]) and the entry screen ([zoukan.desktop.ui.manga.MangaScreenModel]):
 * duplicate detection, default-category vs. category-picker planning, migration handoff from duplicate
 * prompts, category editing, and library add/remove.
 */
class LibraryAddController(
    private val addToLibrary: () -> AddToLibrary = { Injekt.get() },
    private val getCategories: () -> GetCategories = { Injekt.get() },
    private val setMangaCategories: () -> SetMangaCategories = { Injekt.get() },
) {
    sealed interface SinglePrompt {
        data class ConfirmRemove(val manga: Manga) : SinglePrompt
        data class Duplicates(val target: Manga, val matches: List<MangaWithChapterCount>) : SinglePrompt
        data class Categories(val target: Manga, val choices: List<Category>) : SinglePrompt
        data class Migrate(val from: Manga, val to: Manga) : SinglePrompt
    }

    sealed interface SingleResult {
        data class Added(val manga: Manga) : SingleResult
        data class Removed(val manga: Manga) : SingleResult
        data class Prompt(val prompt: SinglePrompt) : SingleResult
    }

    sealed interface BulkResult {
        data class AskDuplicates(val duplicates: List<Manga>) : BulkResult
        data class AskCategories(val pending: List<Manga>, val categories: List<Category>) : BulkResult
        data class Added(val count: Int) : BulkResult
        data object EmptyAfterSkip : BulkResult
    }

    suspend fun toggleSingle(
        manga: Manga,
        confirmRemove: Boolean,
        fetchOnAdd: Boolean,
    ): SingleResult {
        if (manga.favorite) {
            return if (confirmRemove) {
                SingleResult.Prompt(SinglePrompt.ConfirmRemove(manga))
            } else {
                removeSingle(manga)
            }
        }
        val found = addToLibrary().duplicates(manga)
        return if (found.isNotEmpty()) {
            SingleResult.Prompt(SinglePrompt.Duplicates(manga, found))
        } else {
            planOrAddSingle(manga, fetchOnAdd)
        }
    }

    suspend fun planOrAddSingle(manga: Manga, fetchOnAdd: Boolean): SingleResult {
        val plan = addToLibrary().plan()
        val ask = plan.ask
        return if (ask != null) {
            SingleResult.Prompt(SinglePrompt.Categories(manga, ask))
        } else {
            addSingle(manga, plan.categoryIds, fetchOnAdd)
        }
    }

    suspend fun addSingle(
        manga: Manga,
        categoryIds: List<Long>,
        fetchOnAdd: Boolean,
    ): SingleResult.Added {
        addToLibrary().add(manga, categoryIds, fetchOnAdd = fetchOnAdd)
        Toasts.show(localized(MR.strings.manga_added_library))
        return SingleResult.Added(manga)
    }

    suspend fun removeSingle(manga: Manga): SingleResult.Removed {
        addToLibrary().remove(manga)
        Toasts.show(localized(MR.strings.manga_removed_library))
        return SingleResult.Removed(manga)
    }

    suspend fun loadCategoryEdit(mangaId: Long): Pair<List<Category>, Set<Long>> {
        val categories = withContext(Dispatchers.IO) { getCategories().await() }.filterNot(Category::isSystemCategory)
        val chosen = withContext(Dispatchers.IO) { getCategories().await(mangaId) }.mapTo(HashSet()) { it.id }
        return categories to chosen
    }

    suspend fun setCategories(mangaId: Long, chosen: Set<Long>) {
        withContext(Dispatchers.IO) {
            setMangaCategories().await(mangaId, chosen.toList())
        }
    }

    suspend fun startBulkAdd(
        items: List<Manga>,
        findDuplicates: suspend (List<Manga>) -> List<Manga>,
        planCategories: suspend () -> AddToLibrary.Plan = { addToLibrary().plan() },
        addBatch: suspend (List<Manga>, List<Long>) -> Int,
    ): BulkResult {
        val dupes = findDuplicates(items)
        if (dupes.isNotEmpty()) {
            return BulkResult.AskDuplicates(dupes)
        }
        return planOrAddBulk(items, planCategories, addBatch)
    }

    suspend fun resolveBulkDuplicates(
        selected: List<Manga>,
        duplicates: List<Manga>,
        skip: Boolean,
        planCategories: suspend () -> AddToLibrary.Plan = { addToLibrary().plan() },
        addBatch: suspend (List<Manga>, List<Long>) -> Int,
    ): BulkResult {
        val list = if (skip) selected.filterNot { m -> duplicates.any { it.id == m.id } } else selected
        if (list.isEmpty()) return BulkResult.EmptyAfterSkip
        return planOrAddBulk(list, planCategories, addBatch)
    }

    suspend fun planOrAddBulk(
        items: List<Manga>,
        planCategories: suspend () -> AddToLibrary.Plan = { addToLibrary().plan() },
        addBatch: suspend (List<Manga>, List<Long>) -> Int,
    ): BulkResult {
        val plan = planCategories()
        val ask = plan.ask
        return if (ask != null) {
            BulkResult.AskCategories(items, ask)
        } else {
            BulkResult.Added(addBatch(items, plan.categoryIds))
        }
    }

    fun showBulkAddedToast(added: Int) {
        Toasts.show(
            if (added == 1) {
                localized(DMR.strings.added_1_entry_to_the_library)
            } else {
                localized(DMR.strings.added_entries_to_the_library, added)
            },
        )
    }
}
