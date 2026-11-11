package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton

/**
 * Android's Advanced > Clear database: each source with entries outside the library, and deleting
 * them for the chosen sources, optionally keeping the ones with read chapters. Merged entries'
 * references are kept, as the Android query does.
 */
object ClearDatabaseScreen : Screen {
    private fun readResolve(): Any = ClearDatabaseScreen

    override val key: ScreenKey = "clear-database"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { ClearDatabaseScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()
        val items = state.items

        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Clear database") {
                if (!items.isNullOrEmpty()) {
                    TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = model::selectAll)
                    TooltipIconButton(Icons.Outlined.FlipToBack, "Select inverse", onClick = model::invertSelection)
                    TooltipIconButton(Icons.Outlined.Delete, "Delete", enabled = state.selection.isNotEmpty(), onClick = { model.confirm(true) })
                }
            }
            when {
                items == null -> LoadingScreen()
                items.isEmpty() -> EmptyScreen("Nothing to clear")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { model.toggle(item.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${item.count} non-library entries in database",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Checkbox(checked = item.id in state.selection, onCheckedChange = { model.toggle(item.id) })
                        }
                    }
                }
            }
        }

        if (state.confirming) {
            var keepRead by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { model.confirm(false) },
                title = { Text("Are you sure?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You're about to remove entries from the database")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep entries with read chapters", modifier = Modifier.weight(1f))
                            Switch(checked = keepRead, onCheckedChange = { keepRead = it })
                        }
                        if (!keepRead) {
                            Text(
                                "Read chapters and progress of non-library entries will be lost",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.confirm(false)
                        scope.launch {
                            model.delete(keepRead)
                            Toasts.show("Entries deleted")
                        }
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { model.confirm(false) }) { Text("Cancel") } },
            )
        }
    }
}

class ClearDatabaseScreenModel(
    private val handler: DatabaseHandler = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<ClearDatabaseScreenModel.State>(State()) {

    data class Item(val id: Long, val name: String, val count: Long)

    data class State(
        val items: List<Item>? = null,
        val selection: Set<Long> = emptySet(),
        val confirming: Boolean = false,
    )

    init {
        screenModelScope.launch {
            handler.subscribeToList { mangasQueries.getSourceIdsWithNonLibraryManga() }.collect { rows ->
                val items = rows.map { row ->
                    // As Android's SourceRepository: an uninstalled source shows its id
                    val name = sourceManager.getOrStub(row.source).name.ifBlank { row.source.toString() }
                    Item(row.source, name, row.manga_count)
                }.sortedBy { it.name.lowercase() }
                mutableState.update { state ->
                    state.copy(items = items, selection = state.selection.filterTo(HashSet()) { id -> items.any { it.id == id } })
                }
            }
        }
    }

    fun toggle(id: Long) = mutableState.update { it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id) }

    fun selectAll() = mutableState.update { state -> state.copy(selection = state.items.orEmpty().mapTo(HashSet()) { it.id }) }

    fun invertSelection() = mutableState.update { state ->
        state.copy(selection = state.items.orEmpty().map { it.id }.filterNotTo(HashSet()) { it in state.selection })
    }

    fun confirm(show: Boolean) = mutableState.update { it.copy(confirming = show) }

    suspend fun delete(keepReadManga: Boolean) = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        val selection = state.value.selection.toList()
        if (selection.isEmpty()) return@withContext
        handler.await(inTransaction = true) {
            mangasQueries.deleteNonLibraryManga(selection, if (keepReadManga) 1L else 0L)
            historyQueries.removeResettedHistory()
        }
        mutableState.update { it.copy(selection = emptySet()) }
    }
}
