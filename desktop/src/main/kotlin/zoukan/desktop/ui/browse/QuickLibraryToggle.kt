package zoukan.desktop.ui.browse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.manga.CategoryDialog
import zoukan.desktop.ui.manga.DuplicateDialog
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.MigrateDialog
import zoukan.i18n.desktop.DMR

/**
 * Android's long press on a browse result, as a right-click: removes a library entry after asking,
 * or adds the entry after the duplicate check and the category choice, fetching its details and
 * chapters when Settings > Library asks for it.
 */
class QuickLibraryToggle internal constructor(private val scope: CoroutineScope) {
    private val addToLibrary = Injekt.get<AddToLibrary>()

    internal var removing by mutableStateOf<Manga?>(null)
    internal var duplicates by mutableStateOf<Pair<Manga, List<MangaWithChapterCount>>?>(null)
    internal var categories by mutableStateOf<Pair<Manga, List<Category>>?>(null)
    internal var migrating by mutableStateOf<Pair<Manga, Manga>?>(null)

    fun toggle(item: Manga) {
        scope.launch {
            val manga = Injekt.get<GetManga>().await(item.id) ?: return@launch
            if (manga.favorite) {
                removing = manga
                return@launch
            }
            val found = addToLibrary.duplicates(manga)
            if (found.isNotEmpty()) duplicates = manga to found else addAnyway(manga)
        }
    }

    internal fun addAnyway(manga: Manga) {
        duplicates = null
        scope.launch {
            val plan = addToLibrary.plan()
            if (plan.ask != null) categories = manga to plan.ask else add(manga, plan.categoryIds)
        }
    }

    internal fun add(manga: Manga, categoryIds: List<Long>) {
        categories = null
        scope.launch {
            runCatching { addToLibrary.add(manga, categoryIds, fetchOnAdd = true) }
                .onSuccess { Toasts.show(localized(MR.strings.manga_added_library)) }
                .onFailure { Toasts.error(localized(DMR.strings.could_not_add_to_library), it) }
        }
    }

    internal fun remove(manga: Manga) {
        removing = null
        scope.launch {
            addToLibrary.remove(manga)
            Toasts.show(localized(MR.strings.manga_removed_library))
        }
    }
}

@Composable
fun rememberQuickLibraryToggle(): QuickLibraryToggle {
    val scope = rememberCoroutineScope()
    return remember(scope) { QuickLibraryToggle(scope) }
}

/** The toggle's dialogs; place it once in the screen that uses [toggle]. */
@Composable
fun QuickLibraryToggleDialogs(toggle: QuickLibraryToggle) {
    val navigator = LocalNavigator.currentOrThrow
    toggle.removing?.let { manga ->
        AlertDialog(
            onDismissRequest = { toggle.removing = null },
            title = { Text(manga.title) },
            text = { Text(localized(DMR.strings.remove_from_library)) },
            confirmButton = { TextButton(onClick = { toggle.remove(manga) }) { Text(localized(MR.strings.action_remove)) } },
            dismissButton = { TextButton(onClick = { toggle.removing = null }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
    toggle.duplicates?.let { (manga, found) ->
        DuplicateDialog(
            target = manga,
            duplicates = found,
            onAddAnyway = { toggle.addAnyway(manga) },
            onOpen = {
                toggle.duplicates = null
                navigator.push(MangaScreen(it.id))
            },
            onMigrate = {
                toggle.duplicates = null
                toggle.migrating = it to manga
            },
            onDismiss = { toggle.duplicates = null },
        )
    }
    toggle.categories?.let { (manga, choices) ->
        CategoryDialog(
            categories = choices,
            onConfirm = { toggle.add(manga, it) },
            onDismiss = { toggle.categories = null },
        )
    }
    toggle.migrating?.let { (from, to) ->
        MigrateDialog(from.id, to, onDismiss = { toggle.migrating = null }, onMigrated = { navigator.push(MangaScreen(to.id)) })
    }
}
