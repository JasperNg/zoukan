package zoukan.desktop.ui.browse

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.kanade.domain.track.interactor.AddTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import java.time.Instant

/**
 * Komikku's bulk favorite (BulkFavoriteScreenModel): select entries in a source, the global search
 * or the feed, then add them all to the library. The select button starts it, as the toolbar button
 * does on Android; while selecting, a click or right-click selects an entry.
 */
@Stable
class BulkFavorite {
    val selection = mutableStateListOf<Manga>()

    /** The categories dialog, when the default category is "Always ask" */
    var categoryChoice by mutableStateOf<List<Category>?>(null)
        private set

    /** Selected entries whose title is already in the library, asked about before adding */
    var duplicates by mutableStateOf<List<Manga>?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    /** Android's selection mode, which the select button turns on */
    var selectionMode by mutableStateOf(false)
        private set

    val active: Boolean get() = selectionMode || selection.isNotEmpty()

    fun start() {
        selectionMode = true
    }

    fun isSelected(manga: Manga) = selection.any { it.id == manga.id }

    fun toggle(manga: Manga) {
        if (!selection.removeAll { it.id == manga.id }) selection.add(manga)
    }

    fun selectAll(manga: List<Manga>) {
        manga.filterNot(::isSelected).forEach(selection::add)
    }

    fun invert(manga: List<Manga>) {
        val selected = manga.filter(::isSelected)
        val others = manga.filterNot(::isSelected)
        selection.removeAll { s -> selected.any { it.id == s.id } }
        selection.addAll(others)
    }

    fun clear() {
        selectionMode = false
        selection.clear()
        categoryChoice = null
        duplicates = null
    }

    /** The bar's Add button: first the duplicate check, then the default category or the dialog */
    fun addToLibrary() {
        if (running || selection.isEmpty()) return
        running = true
        AppScope.launch {
            try {
                val titles = withContext(Dispatchers.IO) { Injekt.get<GetFavorites>().await() }
                    .associateBy({ it.title.lowercase() }, { it.id })
                val dupes = selection.filter { manga -> titles[manga.title.lowercase()]?.let { it != manga.id } == true }
                if (dupes.isNotEmpty()) {
                    duplicates = dupes
                } else {
                    chooseCategories(selection.toList())
                }
            } finally {
                running = false
            }
        }
    }

    /** The duplicate dialog's answer: add them all, or leave out the ones already in the library */
    fun resolveDuplicates(skip: Boolean) {
        val dupes = duplicates.orEmpty()
        duplicates = null
        val list = if (skip) selection.filterNot { m -> dupes.any { it.id == m.id } } else selection.toList()
        if (list.isEmpty()) {
            clear()
            return
        }
        running = true
        AppScope.launch {
            try {
                chooseCategories(list)
            } finally {
                running = false
            }
        }
    }

    private var pending: List<Manga> = emptyList()

    private suspend fun chooseCategories(list: List<Manga>) {
        val categories = withContext(Dispatchers.IO) { Injekt.get<GetCategories>().await() }.filterNot { it.isSystemCategory }
        // Settings > Library > Default category, as on Android: a category, the default one (0),
        // or ask (-1)
        val defaultId = Injekt.get<LibraryPreferences>().defaultCategory().get().toLong()
        val defaultCategory = categories.find { it.id == defaultId }
        when {
            defaultCategory != null -> add(list, listOf(defaultCategory.id))
            defaultId == 0L || categories.isEmpty() -> add(list, emptyList())
            else -> {
                pending = list
                categoryChoice = categories
            }
        }
    }

    fun confirmCategories(ids: List<Long>) {
        categoryChoice = null
        val list = pending
        pending = emptyList()
        running = true
        AppScope.launch {
            try {
                add(list, ids)
            } finally {
                running = false
            }
        }
    }

    fun dismissCategories() {
        categoryChoice = null
        pending = emptyList()
    }

    /** As BulkFavoriteScreenModel.setMangasCategories: the categories are added to each entry's own */
    private suspend fun add(list: List<Manga>, categoryIds: List<Long>) = withContext(Dispatchers.IO) {
        val repository = Injekt.get<MangaRepository>()
        val getCategories = Injekt.get<GetCategories>()
        val setCategories = Injekt.get<SetMangaCategories>()
        val sourceManager = Injekt.get<SourceManager>()
        val addTracks = Injekt.get<AddTracks>()
        var added = 0
        list.forEach { manga ->
            val current = getCategories.await(manga.id).map { it.id }
            setCategories.await(manga.id, (current + categoryIds).distinct())
            if (manga.favorite) return@forEach
            repository.update(MangaUpdate(id = manga.id, favorite = true, dateAdded = Instant.now().toEpochMilli()))
            added++
            sourceManager.get(manga.source)?.let { source ->
                runCatching { addTracks.bindEnhancedTrackers(manga, source) }
            }
        }
        withContext(Dispatchers.Main) { clear() }
        Toasts.show(if (added == 1) localized(DMR.strings.added_1_entry_to_the_library) else localized(DMR.strings.added_entries_to_the_library, added))
    }
}

/** The button that starts selecting, for a screen's header */
@Composable
fun BulkSelectButton(bulk: BulkFavorite) {
    TooltipIconButton(Icons.Outlined.Checklist, localized(DMR.strings.select_entries), onClick = { if (bulk.active) bulk.clear() else bulk.start() })
}

/** A grid item's border while it is selected. */
@Composable
fun Modifier.bulkSelected(selected: Boolean): Modifier =
    if (selected) border(3.dp, MaterialTheme.colorScheme.primary, Corners.Medium) else this

/** The selection bar over a list of results: count, select all, invert, clear and Add to library. */
@Composable
fun BulkFavoriteBar(bulk: BulkFavorite, visible: List<Manga>) {
    if (!bulk.active) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("bulk-favorite-bar")) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(Icons.Outlined.Close, localized(DMR.strings.clear_the_selection), onClick = bulk::clear)
            Text(localized(DMR.strings.selected, bulk.selection.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TooltipIconButton(Icons.Outlined.SelectAll, localized(MR.strings.action_select_all), onClick = { bulk.selectAll(visible) })
            TooltipIconButton(Icons.Outlined.FlipToBack, localized(DMR.strings.invert_the_selection), onClick = { bulk.invert(visible) })
            Spacer(Modifier.width(8.dp))
            if (bulk.running) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                Button(onClick = bulk::addToLibrary) { Text(localized(MR.strings.add_to_library)) }
            }
        }
    }
    BulkFavoriteDialogs(bulk)
}

@Composable
private fun BulkFavoriteDialogs(bulk: BulkFavorite) {
    bulk.duplicates?.let { dupes ->
        AlertDialog(
            onDismissRequest = { bulk.resolveDuplicates(skip = true) },
            title = { Text(localized(DMR.strings.already_in_your_library)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScrollWithBar()) {
                    Text(localized(DMR.strings.the_library_has_entries_with_the))
                    dupes.forEach { Text("• ${it.title}", modifier = Modifier.padding(top = 4.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { bulk.resolveDuplicates(skip = false) }) { Text(localized(DMR.strings.add_all)) } },
            dismissButton = { TextButton(onClick = { bulk.resolveDuplicates(skip = true) }) { Text(localized(DMR.strings.skip_these)) } },
        )
    }
    bulk.categoryChoice?.let { categories ->
        var chosen by remember(categories) { mutableStateOf(emptySet<Long>()) }
        AlertDialog(
            onDismissRequest = bulk::dismissCategories,
            title = { Text(localized(DMR.strings.add_to_categories)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScrollWithBar()) {
                    categories.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = category.id in chosen,
                                onValueChange = { on -> chosen = if (on) chosen + category.id else chosen - category.id },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = category.id in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(category.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bulk.confirmCategories(chosen.toList()) }) { Text(localized(MR.strings.action_add)) } },
            dismissButton = { TextButton(onClick = bulk::dismissCategories) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}
