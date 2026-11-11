package zoukan.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.domain.manga.interactor.CreateSortTag
import eu.kanade.domain.manga.interactor.DeleteSortTag
import eu.kanade.domain.manga.interactor.GetSortTag
import eu.kanade.domain.manga.interactor.ReorderSortTag
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton

/**
 * SY's "Tag sorting tags" (Settings > Library > Sorting): the ordered tags the library's "Tag
 * sorting" sort uses. An entry sorts by the first of these tags in its genres.
 */
object SortTagScreen : Screen {
    private fun readResolve(): Any = SortTagScreen

    override val key: ScreenKey = "sort-tags"

    @Composable
    override fun Content() {
        val getSortTag = remember { Injekt.get<GetSortTag>() }
        val tags by getSortTag.subscribe().collectAsState(getSortTag.await())
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Edit tags", subtitle = "Sort the library by these tags, in this order, with Sort > Tag sorting") {
                TooltipIconButton(Icons.Default.Add, "Add a tag", onClick = { adding = true })
            }
            if (tags.isEmpty()) {
                EmptyScreen(
                    "You have no tags. Add one to sort your library by tags.",
                    actionLabel = "Add a tag",
                    onAction = { adding = true },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tags, key = { _, tag -> tag }) { index, tag ->
                        SortTagRow(
                            tag,
                            canMoveUp = index > 0,
                            canMoveDown = index < tags.lastIndex,
                            onMove = { Injekt.get<ReorderSortTag>().await(tag, index + it) },
                            onDelete = { deleting = tag },
                        )
                    }
                }
            }
        }
        if (adding) AddTagDialog(onDismiss = { adding = false })
        deleting?.let { tag ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete tag") },
                text = { Text("Do you wish to delete the tag \"$tag\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        Injekt.get<DeleteSortTag>().await(tag)
                        deleting = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SortTagRow(tag: String, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Label, null)
            Spacer(Modifier.width(16.dp))
            Text(tag, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TooltipIconButton(Icons.Outlined.ArrowDropUp, "Move up", onClick = { onMove(-1) }, enabled = canMoveUp)
            TooltipIconButton(Icons.Outlined.ArrowDropDown, "Move down", onClick = { onMove(1) }, enabled = canMoveDown)
            TooltipIconButton(Icons.Outlined.Delete, "Delete", onClick = onDelete)
        }
    }
}

@Composable
private fun AddTagDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // SY's warning: matches are exact, namespaced tags as E-Hentai writes them
                Text(
                    "Tags must match exactly, with no partial matches. Write namespaced tags as \"female: sole female\". " +
                        "Add one variant per source, for example \"tag: netorare\" for NHentai and \"female: netorare\" for E-Hentai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tag") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    when (Injekt.get<CreateSortTag>().await(name)) {
                        CreateSortTag.Result.TagExists -> Toasts.show("This tag exists!")
                        CreateSortTag.Result.Success -> onDismiss()
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
