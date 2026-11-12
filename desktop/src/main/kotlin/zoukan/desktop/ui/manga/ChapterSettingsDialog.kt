package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR

private val SORTS = listOf(
    Manga.CHAPTER_SORTING_SOURCE to localized(MR.strings.sort_by_source),
    Manga.CHAPTER_SORTING_NUMBER to localized(MR.strings.action_order_by_chapter_number),
    Manga.CHAPTER_SORTING_UPLOAD_DATE to localized(MR.strings.action_order_by_upload_date),
    Manga.CHAPTER_SORTING_ALPHABET to localized(MR.strings.action_sort_alpha),
)

/**
 * The manga page's chapter Filter, Sort and Display sheet, as on Android. The settings are saved on the
 * entry, so each entry keeps its own.
 */
@Composable
fun ChapterSettingsDialog(state: MangaScreenModel.State, model: MangaScreenModel, onDismiss: () -> Unit) {
    val manga = state.manga ?: return
    var tab by remember { mutableIntStateOf(0) }
    var scanlators by remember { mutableStateOf(false) }
    var setAsDefault by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
        dismissButton = {
            Row {
                TextButton(onClick = model::resetChapterSettings) { Text(localized(MR.strings.action_reset)) }
                TextButton(onClick = { setAsDefault = true }) { Text(localized(MR.strings.set_chapter_settings_as_default)) }
            }
        },
        text = {
            Column(Modifier.width(420.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(localized(MR.strings.action_filter), localized(MR.strings.action_sort), localized(MR.strings.action_display)).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
                Column(Modifier.heightIn(max = 480.dp).verticalScrollWithBar().padding(top = 8.dp)) {
                    when (tab) {
                        0 -> {
                            // A local entry's chapters are all on disk
                            if (!state.isLocal) {
                                // More > Downloaded only keeps it on, as on Android
                                TriStateRow(
                                    localized(MR.strings.label_downloaded),
                                    ChapterSettings.downloadedFilter(manga, state.downloadedOnly),
                                    model::setDownloadedFilter.takeUnless { state.downloadedOnly },
                                )
                            }
                            TriStateRow(localized(MR.strings.action_filter_unread), manga.unreadFilter, model::setUnreadFilter)
                            TriStateRow(localized(MR.strings.action_filter_bookmarked), manga.bookmarkedFilter, model::setBookmarkedFilter)
                            if (state.availableScanlators.isNotEmpty()) {
                                ScanlatorRow(active = state.scanlatorFilterActive, onClick = { scanlators = true })
                            }
                            Text(
                                localized(DMR.strings.a_box_with_a_dash_shows_2),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        1 -> SORTS.forEach { (sorting, label) ->
                            SortRow(
                                label = label,
                                descending = manga.sortDescending().takeIf { manga.sorting == sorting },
                                onClick = { model.setSorting(sorting) },
                            )
                        }
                        // ZOUKAN -->
                        // Android's DisplayPage: the source's title or the chapter number
                        else -> listOf(
                            MR.strings.show_title to Manga.CHAPTER_DISPLAY_NAME,
                            MR.strings.show_chapter_number to Manga.CHAPTER_DISPLAY_NUMBER,
                        ).forEach { (titleRes, mode) ->
                            DisplayRow(localized(titleRes), selected = manga.displayMode == mode, onClick = { model.setDisplayMode(mode) })
                        }
                        // ZOUKAN <--
                    }
                }
            }
        },
    )
    if (scanlators) {
        ScanlatorFilterDialog(
            available = state.availableScanlators,
            excluded = state.excludedScanlators,
            onConfirm = model::setExcludedScanlators,
            onDismiss = { scanlators = false },
        )
    }
    if (setAsDefault) {
        SetAsDefaultDialog(
            onConfirm = model::setChapterSettingsAsDefault,
            onDismiss = { setAsDefault = false },
        )
    }
}

@Composable
private fun TriStateRow(label: String, value: TriState, onChange: ((TriState) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onChange != null) { onChange?.invoke(value.next()) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (value) {
                TriState.DISABLED -> ToggleableState.Off
                TriState.ENABLED_IS -> ToggleableState.On
                TriState.ENABLED_NOT -> ToggleableState.Indeterminate
            },
            onClick = onChange?.let { { it(value.next()) } },
            enabled = onChange != null,
        )
        Text(if (value == TriState.ENABLED_NOT) localized(DMR.strings.not, label.lowercase()) else label)
    }
}

@Composable
private fun ScanlatorRow(active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            Icons.Outlined.PeopleAlt,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
        Text(if (active) localized(DMR.strings.scanlators_some_hidden) else localized(DMR.strings.scanlators))
    }
}

@Composable
private fun SortRow(label: String, descending: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        if (descending != null) {
            Icon(
                if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                contentDescription = if (descending) localized(MR.strings.action_desc) else localized(MR.strings.action_asc),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ZOUKAN -->
/** A Display tab choice, as Android's RadioItem */
@Composable
private fun DisplayRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
// ZOUKAN <--

/** Picks the scanlators whose chapters show; the unticked ones are hidden, as on Android */
@Composable
private fun ScanlatorFilterDialog(
    available: Set<String>,
    excluded: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hidden by remember { mutableStateOf(excluded.intersect(available)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.scanlators)) },
        text = {
            Column(Modifier.width(360.dp).heightIn(max = 420.dp).verticalScrollWithBar()) {
                available.forEach { scanlator ->
                    val shown = scanlator !in hidden
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { hidden = if (shown) hidden + scanlator else hidden - scanlator }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(shown, onCheckedChange = { hidden = if (it) hidden - scanlator else hidden + scanlator })
                        Text(scanlator)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keeps the hidden scanlators that no chapter has now, as Android does
                onConfirm(hidden + (excluded - available))
                onDismiss()
            }) { Text(localized(MR.strings.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { hidden = emptySet() }) { Text(localized(DMR.strings.show_all)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) }
            }
        },
    )
}

@Composable
private fun SetAsDefaultDialog(onConfirm: (toLibrary: Boolean) -> Unit, onDismiss: () -> Unit) {
    var toLibrary by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.chapter_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(DMR.strings.make_these_the_default_chapter_settings))
                Row(
                    Modifier.fillMaxWidth().clickable { toLibrary = !toLibrary },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(toLibrary, onCheckedChange = { toLibrary = it })
                    Text(localized(DMR.strings.also_apply_them_to_every_entry))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(toLibrary)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
