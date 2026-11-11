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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.i18n.desktop.DMR

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
            ScreenHeader(localized(SYMR.strings.action_edit_tags), subtitle = localized(DMR.strings.sort_the_library_by_these_tags)) {
                TooltipIconButton(Icons.Outlined.Add, localized(DMR.strings.add_a_tag), onClick = { adding = true })
            }
            if (tags.isEmpty()) {
                EmptyScreen(
                    localized(DMR.strings.you_have_no_tags_add_one),
                    actionLabel = localized(DMR.strings.add_a_tag),
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
                title = { Text(localized(SYMR.strings.delete_tag)) },
                text = { Text(localized(DMR.strings.do_you_wish_to_delete_the_2, tag)) },
                confirmButton = {
                    TextButton(onClick = {
                        Injekt.get<DeleteSortTag>().await(tag)
                        deleting = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(localized(MR.strings.action_cancel)) } },
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
            TooltipIconButton(Icons.Outlined.ArrowDropUp, localized(KMR.strings.action_move_up), onClick = { onMove(-1) }, enabled = canMoveUp)
            TooltipIconButton(Icons.Outlined.ArrowDropDown, localized(KMR.strings.action_move_down), onClick = { onMove(1) }, enabled = canMoveDown)
            TooltipIconButton(Icons.Outlined.Delete, localized(MR.strings.action_delete), onClick = onDelete)
        }
    }
}

@Composable
private fun AddTagDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.add_tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // SY's warning: matches are exact, namespaced tags as E-Hentai writes them
                Text(
                    localized(DMR.strings.tags_must_match_exactly_with_no),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(localized(DMR.strings.tag)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    when (Injekt.get<CreateSortTag>().await(name)) {
                        CreateSortTag.Result.TagExists -> Toasts.show(localized(SYMR.strings.error_tag_exists))
                        CreateSortTag.Result.Success -> onDismiss()
                    }
                },
            ) { Text(localized(MR.strings.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
