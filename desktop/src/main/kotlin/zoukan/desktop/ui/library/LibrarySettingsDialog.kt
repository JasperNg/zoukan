package zoukan.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.settings.CategoriesIncludeExcludeDialog
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

private val SORTS = listOf(
    LibrarySort.Type.Alphabetical to localized(MR.strings.action_sort_alpha),
    LibrarySort.Type.TotalChapters to localized(MR.strings.action_sort_total),
    LibrarySort.Type.LastRead to localized(MR.strings.action_sort_last_read),
    LibrarySort.Type.LastUpdate to localized(MR.strings.action_sort_last_manga_update),
    LibrarySort.Type.UnreadCount to localized(MR.strings.action_sort_unread_count),
    LibrarySort.Type.LatestChapter to localized(MR.strings.action_sort_latest_chapter),
    LibrarySort.Type.ChapterFetchDate to localized(MR.strings.action_sort_chapter_fetch_date),
    LibrarySort.Type.DateAdded to localized(MR.strings.action_sort_date_added),
    LibrarySort.Type.TrackerMean to localized(MR.strings.action_sort_tracker_score),
    LibrarySort.Type.Random to localized(MR.strings.action_sort_random),
)

private val DISPLAY_MODES = listOf(
    LibraryDisplayMode.CompactGrid to localized(MR.strings.action_display_grid),
    LibraryDisplayMode.ComfortableGrid to localized(MR.strings.action_display_comfortable_grid),
    LibraryDisplayMode.ComfortableGridPanorama to localized(DMR.strings.panorama_grid),
    LibraryDisplayMode.CoverOnlyGrid to localized(MR.strings.action_display_cover_only_grid),
    LibraryDisplayMode.List to localized(MR.strings.action_display_list),
)

/** The library's Filter, Sort and Display sheet, as on Android. */
@Composable
fun LibrarySettingsDialog(model: LibraryScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
        text = {
            Column(Modifier.width(420.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(localized(MR.strings.action_filter), localized(MR.strings.action_sort), localized(MR.strings.action_display), localized(SYMR.strings.group)).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
                Column(Modifier.heightIn(max = 480.dp).verticalScrollWithBar().padding(top = 8.dp)) {
                    when (tab) {
                        0 -> FilterPage(model)
                        1 -> SortPage(model, state.sort)
                        2 -> DisplayPage(model)
                        else -> GroupPage(model)
                    }
                }
            }
        },
    )
}

/**
 * A tri-state filter: off, only, or not; shared with the Updates and History filters. [notLabel]
 * names the "not" state where "Not [label]" reads badly.
 */
@Composable
internal fun TriStateRow(label: String, preference: Preference<TriState>, notLabel: String? = null) {
    val value by preference.collectAsState()
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
        Text(if (value == TriState.ENABLED_NOT) notLabel ?: localized(DMR.strings.not, label).replaceFirstChar { it.uppercase() } else label)
    }
}

@Composable
private fun FilterPage(model: LibraryScreenModel) {
    val p = model.preferences
    TriStateRow(localized(MR.strings.label_downloaded), p.filterDownloaded())
    TriStateRow(localized(MR.strings.action_filter_unread), p.filterUnread())
    TriStateRow(localized(MR.strings.label_started), p.filterStarted())
    TriStateRow(localized(MR.strings.action_filter_bookmarked), p.filterBookmarked())
    TriStateRow(localized(MR.strings.completed), p.filterCompleted())
    // KMK -->
    // Android shows this only in debug builds, with the "outside the release period" update restriction
    val restrictions by p.autoUpdateMangaRestrictions().collectAsState()
    if (LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in restrictions) {
        TriStateRow(localized(MR.strings.action_filter_interval_custom), p.filterIntervalCustom())
    }
    // KMK <--
    // SY -->
    TriStateRow(localized(SYMR.strings.lewd), p.filterLewd())
    // SY <--
    // KMK -->
    CategoriesFilter(model)
    // KMK <--
    val trackers = remember { model.trackers() }
    if (trackers.isNotEmpty()) {
        Text(localized(DMR.strings.tracked_on), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        trackers.forEach { tracker -> TriStateRow(tracker.name, p.filterTracking(tracker.id.toInt())) }
    }
    Text(
        localized(DMR.strings.a_box_with_a_dash_shows),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SortPage(model: LibraryScreenModel, sort: LibrarySort) {
    val tags = model.state.collectAsState().value.settings.sortTags
    // SY's "Tag sorting", offered once there are tags to sort by (Settings > Library > Sorting)
    val sorts = if (tags.isNotEmpty() || sort.type == LibrarySort.Type.TagList) SORTS + (LibrarySort.Type.TagList to localized(SYMR.strings.tag_sorting)) else SORTS
    sorts.forEach { (type, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { model.setSort(type) }.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            if (sort.type == type && type != LibrarySort.Type.Random) {
                Icon(
                    if (sort.isAscending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                    contentDescription = if (sort.isAscending) localized(MR.strings.action_asc) else localized(MR.strings.action_desc),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (sort.type == type) {
                TextButton(onClick = model::reshuffle) { Text(localized(DMR.strings.shuffle)) }
            }
        }
    }
    CheckRow(localized(DMR.strings.each_category_keeps_its_own_sort), model.preferences.categorizedDisplaySettings())
}

@Composable
private fun CheckRow(label: String, preference: Preference<Boolean>) {
    val value by preference.collectAsState()
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
    val mode by p.displayMode().collectAsState()
    Text(localized(MR.strings.action_display_mode), style = MaterialTheme.typography.labelLarge)
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
        val columns by p.landscapeColumns().collectAsState()
        Text(
            localized(DMR.strings.columns, if (columns == 0) localized(DMR.strings.fit_the_window) else columns),
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
    Text(localized(DMR.strings.badges), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow(localized(MR.strings.action_display_download_badge), p.downloadBadge())
    CheckRow(localized(MR.strings.action_display_unread_badge), p.unreadBadge())
    CheckRow(localized(MR.strings.action_display_local_badge), p.localBadge())
    CheckRow(localized(MR.strings.action_display_language_badge), p.languageBadge())
    // KMK -->
    val showLang by p.languageBadge().collectAsState()
    if (showLang) CheckRow(localized(KMR.strings.action_display_language_icon), p.useLangIcon())
    CheckRow(localized(KMR.strings.action_display_source_badge), p.sourceBadge())
    // KMK <--
    Text(localized(MR.strings.tabs_header), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow(localized(MR.strings.action_display_show_tabs), p.categoryTabs())
    // KMK -->
    CheckRow(localized(KMR.strings.action_show_hidden_categories), p.showHiddenCategories())
    // KMK <--
    CheckRow(localized(DMR.strings.show_the_number_of_entries), p.categoryNumberOfItems())
    Text(localized(MR.strings.other_source), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    CheckRow(localized(MR.strings.action_display_show_continue_reading_button), p.showContinueReadingButton())
}

// ZOUKAN -->
private val GROUPS = listOf(
    LibraryGroup.BY_DEFAULT to localized(MR.strings.categories),
    LibraryGroup.BY_SOURCE to localized(MR.strings.label_sources),
    LibraryGroup.BY_STATUS to localized(MR.strings.status),
    LibraryGroup.BY_TRACK_STATUS to localized(SYMR.strings.tracking_status),
    LibraryGroup.UNGROUPED to localized(SYMR.strings.ungrouped),
)

/** SY's Group tab: what the library's tabs split it by. */
@Composable
private fun GroupPage(model: LibraryScreenModel) {
    val p = model.preferences.groupLibraryBy()
    val group by p.collectAsState()
    GROUPS.forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { p.set(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(group == value, onClick = { p.set(value) })
            Text(label)
        }
    }
}
// ZOUKAN <--

// KMK -->
/**
 * KMK's category filter: a checkbox that turns it on, and Edit for the categories to include and
 * exclude (TriStateListDialog over the user's categories, Default left out).
 */
@Composable
private fun CategoriesFilter(model: LibraryScreenModel) {
    val p = model.preferences
    val enabled by p.filterCategories().collectAsState()
    val included by p.filterCategoriesInclude().collectAsState()
    val excluded by p.filterCategoriesExclude().collectAsState()
    val categories = model.state.collectAsState().value.categories.filterNot { it.isSystemCategory }
    var dialog by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { p.filterCategories().set(!enabled) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(enabled, onCheckedChange = { p.filterCategories().set(it) })
        Text(localized(MR.strings.categories), Modifier.weight(1f))
        TextButton(onClick = { dialog = true }) { Text(localized(MR.strings.action_edit)) }
    }
    if (dialog) {
        CategoriesIncludeExcludeDialog(
            title = localized(MR.strings.categories),
            message = localized(KMR.strings.pref_library_filter_categories_details),
            categories = categories,
            // As TriStateListDialog's initialChecked: only categories that still exist
            included = included.filterTo(HashSet()) { id -> categories.any { it.id.toString() == id } },
            excluded = excluded.filterTo(HashSet()) { id -> categories.any { it.id.toString() == id } },
            onDismiss = { dialog = false },
            onConfirm = { inc, exc ->
                p.filterCategoriesInclude().set(inc)
                p.filterCategoriesExclude().set(exc)
                dialog = false
            },
        )
    }
}
// KMK <--
