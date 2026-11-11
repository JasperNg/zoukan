package zoukan.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import kotlin.math.roundToInt

private val SORTS = listOf(
    LibrarySort.Type.Alphabetical to "Alphabetically",
    LibrarySort.Type.TotalChapters to "Total chapters",
    LibrarySort.Type.LastRead to "Last read",
    LibrarySort.Type.LastUpdate to "Last update check",
    LibrarySort.Type.UnreadCount to "Unread count",
    LibrarySort.Type.LatestChapter to "Latest chapter",
    LibrarySort.Type.ChapterFetchDate to "Chapter fetch date",
    LibrarySort.Type.DateAdded to "Date added",
    LibrarySort.Type.TrackerMean to "Tracker score",
    LibrarySort.Type.Random to "Random",
)

private val DISPLAY_MODES = listOf(
    LibraryDisplayMode.CompactGrid to "Compact grid",
    LibraryDisplayMode.ComfortableGrid to "Comfortable grid",
    LibraryDisplayMode.ComfortableGridPanorama to "Panorama grid",
    LibraryDisplayMode.CoverOnlyGrid to "Cover-only grid",
    LibraryDisplayMode.List to "List",
)

/** The library's Filter, Sort and Display sheet, as on Android. */
@Composable
fun LibrarySettingsDialog(model: LibraryScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(Modifier.width(420.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Filter", "Sort", "Display").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    when (tab) {
                        0 -> FilterPage(model)
                        1 -> SortPage(model, state.sort)
                        else -> DisplayPage(model)
                    }
                }
            }
        },
    )
}

@Composable
private fun TriStateRow(label: String, preference: Preference<TriState>) {
    val value by preference.changes().collectAsState(preference.get())
    Row(
        Modifier.fillMaxWidth().clickable { preference.set(value.next()) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (value) {
                TriState.DISABLED -> ToggleableState.Off
                TriState.ENABLED_IS -> ToggleableState.On
                TriState.ENABLED_NOT -> ToggleableState.Indeterminate
            },
            onClick = { preference.set(value.next()) },
        )
        Text(if (value == TriState.ENABLED_NOT) "Not $label".replaceFirstChar { it.uppercase() } else label)
    }
}

@Composable
private fun FilterPage(model: LibraryScreenModel) {
    val p = model.preferences
    TriStateRow("Downloaded", p.filterDownloaded())
    TriStateRow("Unread", p.filterUnread())
    TriStateRow("Started", p.filterStarted())
    TriStateRow("Bookmarked", p.filterBookmarked())
    TriStateRow("Completed", p.filterCompleted())
    val trackers = remember { model.trackers() }
    if (trackers.isNotEmpty()) {
        Text("Tracked on", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        trackers.forEach { tracker -> TriStateRow(tracker.name, p.filterTracking(tracker.id.toInt())) }
    }
    Text(
        "A box with a dash shows entries that are not.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SortPage(model: LibraryScreenModel, sort: LibrarySort) {
    SORTS.forEach { (type, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { model.setSort(type) }.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            if (sort.type == type && type != LibrarySort.Type.Random) {
                Icon(
                    if (sort.isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = if (sort.isAscending) "Ascending" else "Descending",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (sort.type == type) {
                TextButton(onClick = model::reshuffle) { Text("Shuffle") }
            }
        }
    }
    CheckRow("Each category keeps its own sort", model.preferences.categorizedDisplaySettings())
}

@Composable
private fun CheckRow(label: String, preference: Preference<Boolean>) {
    val value by preference.changes().collectAsState(preference.get())
    Row(
        Modifier.fillMaxWidth().clickable { preference.set(!value) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, onCheckedChange = { preference.set(it) })
        Text(label)
    }
}

@Composable
private fun DisplayPage(model: LibraryScreenModel) {
    val p = model.preferences
    val mode by p.displayMode().changes().collectAsState(p.displayMode().get())
    Text("Display mode", style = MaterialTheme.typography.labelLarge)
    DISPLAY_MODES.forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { p.displayMode().set(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(mode == value, onClick = { p.displayMode().set(value) })
            Text(label)
        }
    }
    if (mode != LibraryDisplayMode.List) {
        val columns by p.landscapeColumns().changes().collectAsState(p.landscapeColumns().get())
        Text(
            "Columns: ${if (columns == 0) "fit the window" else columns}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Slider(
            value = columns.toFloat(),
            onValueChange = { p.landscapeColumns().set(it.roundToInt()) },
            valueRange = 0f..12f,
            steps = 11,
        )
    }
    Text("Badges", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow("Downloaded chapters", p.downloadBadge())
    CheckRow("Unread chapters", p.unreadBadge())
    CheckRow("Local source", p.localBadge())
    CheckRow("Language", p.languageBadge())
    CheckRow("Source", p.sourceBadge())
    Text("Tabs", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow("Show category tabs", p.categoryTabs())
    CheckRow("Show the number of entries", p.categoryNumberOfItems())
    Text("Other", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow("Continue reading button", p.showContinueReadingButton())
}
