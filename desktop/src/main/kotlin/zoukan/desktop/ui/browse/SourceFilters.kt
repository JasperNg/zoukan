package zoukan.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.verticalScrollWithBar

/**
 * Android's Browse > Sources > filter (SourcesFilterScreen): a switch per language, and under each
 * turned-on language a checkbox per source. Unchecked sources are the hidden ("disabled") ones.
 */
object SourcesFilterScreen : Screen {
    private fun readResolve(): Any = SourcesFilterScreen

    override val key: ScreenKey = "sources-filter"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { BrowseScreenModel() }
        val state by model.state.collectAsState()
        val preferences = remember { Injekt.get<DesktopPreferences>() }
        val languages by preferences.enabledLanguages().changes().collectAsState(preferences.enabledLanguages().get())
        val byLanguage = state.allSources.filterNot { it is LocalSource }.groupBy { it.lang }.toSortedMap()

        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Filter")
            if (byLanguage.isEmpty()) {
                EmptyScreen("No sources yet. Install an extension to add its sources.")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                byLanguage.forEach { (lang, sources) ->
                    val enabled = lang in languages
                    item(key = "lang-$lang") {
                        Row(
                            Modifier.fillMaxWidth().clickable { model.toggleLanguage(lang) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(languageName(lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { model.toggleLanguage(lang) })
                        }
                    }
                    if (enabled) {
                        items(sources, key = { "source-${it.id}" }) { source ->
                            SourceCheckRow(source, checked = source.id.toString() !in state.disabled) { model.toggleHidden(source) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCheckRow(source: CatalogueSource, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(source.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Android's source categories (SY's SourceCategoryScreen): names kept in sources_tab_categories,
 * with each source's membership in sources_tab_source_categories as "sourceId|category".
 */
object SourceCategoryScreen : Screen {
    private fun readResolve(): Any = SourceCategoryScreen

    override val key: ScreenKey = "source-categories"

    @Composable
    override fun Content() {
        val preferences = remember { Injekt.get<SourcePreferences>() }
        val categories by preferences.sourcesTabCategories().changes().collectAsState(preferences.sourcesTabCategories().get())
        var editing by remember { mutableStateOf<String?>(null) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<String?>(null) }

        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Edit categories") {
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            if (categories.isEmpty()) {
                EmptyScreen("You have no categories. Add one to organize your sources.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(categories.sortedWith(String.CASE_INSENSITIVE_ORDER), key = { it }) { category ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(category, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TooltipIconButton(Icons.Default.Edit, "Rename", onClick = { editing = category })
                            TooltipIconButton(Icons.Default.Delete, "Delete", onClick = { deleting = category })
                        }
                    }
                }
            }
        }

        if (creating || editing != null) {
            val original = editing
            var name by remember(original) { mutableStateOf(original.orEmpty()) }
            val trimmed = name.trim()
            // Android forbids "|", which separates a source from its category
            val error = when {
                '|' in trimmed -> "Category names can't contain |"
                trimmed != original && categories.any { it.equals(trimmed, ignoreCase = true) } -> "A category with this name already exists!"
                else -> null
            }
            AlertDialog(
                onDismissRequest = {
                    creating = false
                    editing = null
                },
                title = { Text(if (original == null) "Add category" else "Rename category") },
                text = {
                    OutlinedTextField(
                        name,
                        { name = it },
                        singleLine = true,
                        label = { Text("Name") },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                },
                confirmButton = {
                    TextButton(enabled = trimmed.isNotEmpty() && error == null, onClick = {
                        if (original == null) {
                            preferences.sourcesTabCategories().set(categories + trimmed)
                        } else {
                            renameSourceCategory(preferences, original, trimmed)
                        }
                        creating = false
                        editing = null
                    }) { Text(if (original == null) "Add" else "OK") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        creating = false
                        editing = null
                    }) { Text("Cancel") }
                },
            )
        }
        deleting?.let { category ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete category") },
                text = { Text("Do you wish to delete the category $category?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteSourceCategory(preferences, category)
                        deleting = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

internal fun renameSourceCategory(preferences: SourcePreferences, from: String, to: String) {
    preferences.sourcesTabCategories().set(preferences.sourcesTabCategories().get() - from + to)
    preferences.sourcesTabSourcesInCategories().set(
        preferences.sourcesTabSourcesInCategories().get().map {
            if (it.substringAfter('|') == from) "${it.substringBefore('|')}|$to" else it
        }.toSet(),
    )
}

internal fun deleteSourceCategory(preferences: SourcePreferences, category: String) {
    preferences.sourcesTabCategories().set(preferences.sourcesTabCategories().get() - category)
    preferences.sourcesTabSourcesInCategories().set(
        preferences.sourcesTabSourcesInCategories().get().filterNot { it.substringAfter('|') == category }.toSet(),
    )
}

/** Android's "Set categories" dialog for a source: a checkbox per source category. */
@Composable
internal fun SourceCategoriesDialog(
    source: CatalogueSource,
    categories: List<String>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onEditCategories: () -> Unit,
) {
    var chosen by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.name) },
        text = {
            if (categories.isEmpty()) {
                Text("You have no categories. Add one to organize your sources.")
            } else {
                Column(Modifier.verticalScrollWithBar()) {
                    categories.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (category in chosen) chosen - category else chosen + category }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = category in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(category)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (categories.isEmpty()) {
                TextButton(onClick = onEditCategories) { Text("Edit categories") }
            } else {
                TextButton(onClick = {
                    onConfirm(chosen)
                    onDismiss()
                }) { Text("OK") }
            }
        },
        dismissButton = {
            Row {
                if (categories.isNotEmpty()) TextButton(onClick = onEditCategories) { Text("Edit") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
