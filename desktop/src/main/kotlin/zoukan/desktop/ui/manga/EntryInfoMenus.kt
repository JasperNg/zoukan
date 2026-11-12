package zoukan.desktop.ui.manga

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.onRightClick

// ZOUKAN -->
/** What the entry page's title, author, artist, source and tag menus search with. */
class EntryInfoSearch(
    /** Komikku's Library search */
    val library: (String) -> Unit,
    /** Global search */
    val global: (String) -> Unit,
    /** A tag's Search: the browse screen the entry came from, as Android's performGenreSearch */
    val tag: (String) -> Unit,
)

/**
 * The title, author, artist or source name with KMK's menu: a click runs [onClick] (a global
 * search, or browsing the source), and a right click or long press opens Library search, Global
 * search and Copy to clipboard for [value]. With [value] null (an unknown author) neither does
 * anything, as on Android.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitleMenuBox(
    value: String?,
    search: EntryInfoSearch,
    onClick: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        val modifier = if (value.isNullOrBlank()) {
            Modifier
        } else {
            Modifier
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = { showMenu = true },
                    onClick = { onClick?.invoke() },
                )
                .onRightClick { showMenu = true }
        }
        content(modifier)
        if (!value.isNullOrBlank()) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(localized(KMR.strings.action_library_search)) },
                    onClick = {
                        search.library(value)
                        showMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(localized(MR.strings.action_global_search)) },
                    onClick = {
                        search.global(value)
                        showMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(localized(MR.strings.action_copy_to_clipboard)) },
                    onClick = {
                        copyToClipboard(value)
                        showMenu = false
                    },
                )
            }
        }
    }
}

/**
 * A tag with Android's tag menu: a click (or a right click) opens Search, Global search and Copy
 * to clipboard for [tag], as ExpandableMangaDescription's tags.
 */
@Composable
fun TagMenuBox(
    tag: String,
    search: EntryInfoSearch,
    content: @Composable (modifier: Modifier, onClick: () -> Unit) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        content(Modifier.onRightClick { showMenu = true }) { showMenu = true }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(localized(MR.strings.action_search)) },
                onClick = {
                    search.tag(tag)
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text(localized(MR.strings.action_global_search)) },
                onClick = {
                    search.global(tag)
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text(localized(MR.strings.action_copy_to_clipboard)) },
                onClick = {
                    copyToClipboard(tag)
                    showMenu = false
                },
            )
        }
    }
}

/** An action on one of a merged entry's entries, which [MergedEntryPicker] asks for. */
class MergedAction(val title: String, val action: (Manga, Source) -> Unit)

/**
 * Picks one of a merged entry's entries by its source, as Komikku's mergedMangaAction, then runs
 * the [action] on it.
 */
@Composable
fun MergedEntryPicker(action: MergedAction, model: MangaScreenModel, onDismiss: () -> Unit) {
    val entries by produceState<List<Pair<Manga, Source>>>(emptyList(), model) { value = model.mergedEntries() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column {
                entries.forEach { (manga, source) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            action.action(manga, source)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = {
                            onDismiss()
                            action.action(manga, source)
                        })
                        Text(source.toString())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/**
 * KMK's Clear manga: removes the entry's downloaded data, its chapters and history from the
 * database, or both.
 */
@Composable
fun ClearMangaDialog(onDismissRequest: () -> Unit, onConfirm: (deleteDownload: Boolean, removeChapters: Boolean) -> Unit) {
    var list by remember { mutableStateOf(listOf(false, false)) }
    val labels = listOf(KMR.strings.downloaded_data, KMR.strings.chapters_from_database)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(localized(MR.strings.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = list.any { it },
                onClick = {
                    onDismissRequest()
                    onConfirm(list[0], list[1])
                },
            ) { Text(localized(MR.strings.action_ok)) }
        },
        title = { Text(localized(MR.strings.action_remove)) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
                    val toggle = { list = list.toMutableList().also { it[index] = !it[index] } }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = list[index], onCheckedChange = { toggle() })
                        Text(localized(label))
                    }
                }
            }
        },
    )
}
// ZOUKAN <--
