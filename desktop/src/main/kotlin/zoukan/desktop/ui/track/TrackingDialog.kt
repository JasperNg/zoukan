package zoukan.desktop.ui.track

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.manga.formatDate
import zoukan.i18n.desktop.DMR
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The manga's tracking, as the Android app's tracking sheet: one row per signed-in tracker. */
@Composable
fun TrackingDialog(model: TrackingModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    AlertDialog(
        onDismissRequest = { if (state.searching != null) model.closeSearch() else onDismiss() },
        modifier = Modifier.widthIn(min = 640.dp, max = 760.dp),
        title = { Text(state.searching?.let { localized(DMR.strings.find_on, it.name) } ?: localized(MR.strings.track)) },
        text = {
            Box(Modifier.heightIn(min = 200.dp, max = 560.dp)) {
                if (state.searching != null) {
                    SearchPane(state, model)
                } else if (state.items.isEmpty()) {
                    Text(
                        localized(DMR.strings.sign_in_to_a_tracker_in),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.items, key = { it.tracker.id }) { item -> TrackerRow(item, model) }
                    }
                }
            }
        },
        confirmButton = {
            if (state.searching != null) {
                TextButton(onClick = model::closeSearch) { Text(localized(MR.strings.action_webview_back)) }
            } else {
                TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) }
            }
        },
    )
}

@Composable
private fun TrackerRow(item: TrackingModel.Item, model: TrackingModel) {
    val tracker = item.tracker
    val track = item.track
    Surface(shape = Corners.Large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tracker.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (track == null) {
                    FilledTonalButton(onClick = { model.startSearch(tracker) }) { Text(localized(MR.strings.add_tracking)) }
                } else {
                    TooltipIconButton(Icons.Outlined.Refresh, localized(DMR.strings.refresh_from, tracker.name), onClick = { model.refresh(item) })
                    TooltipIconButton(Icons.Outlined.Search, localized(DMR.strings.track_a_different_entry), onClick = { model.startSearch(tracker) })
                    if (track.remoteUrl.isNotBlank()) {
                        TooltipIconButton(Icons.Outlined.OpenInBrowser, localized(DMR.strings.open_on, tracker.name), onClick = { browse(track.remoteUrl) })
                    }
                    RemoveButton(item, model)
                }
            }
            if (track == null) return@Column
            Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice(
                    label = localized(MR.strings.status),
                    value = tracker.getStatus(track.status)?.let { localized(it) } ?: "—",
                    choices = tracker.getStatusList().map { it to (tracker.getStatus(it)?.let { r -> localized(r) } ?: "$it") },
                    onChoose = { model.setStatus(item, it) },
                )
                ChapterField(track.lastChapterRead, track.totalChapters) { model.setChapter(item, it) }
                val scores = tracker.getScoreList()
                if (scores.isNotEmpty()) {
                    Choice(
                        label = localized(MR.strings.score),
                        value = if (track.score > 0) tracker.displayScore(track) else "—",
                        choices = scores.map { it to it },
                        onChoose = { model.setScore(item, it) },
                    )
                }
            }
            if (tracker.supportsReadingDates) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(localized(MR.strings.label_started), track.startDate) { model.setStartDate(item, it) }
                    DateField(localized(DMR.strings.finished), track.finishDate) { model.setFinishDate(item, it) }
                }
            }
            if (tracker.supportsPrivateTracking) {
                Row(Modifier.clickable { model.setPrivate(item, !track.private) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(track.private, onCheckedChange = { model.setPrivate(item, it) })
                    Text(localized(MR.strings.ext_installer_private), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RemoveButton(item: TrackingModel.Item, model: TrackingModel) {
    var confirm by remember { mutableStateOf(false) }
    TooltipIconButton(Icons.Outlined.Delete, localized(DMR.strings.stop_tracking), onClick = { confirm = true })
    if (!confirm) return
    var remote by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(localized(DMR.strings.stop_tracking_on, item.tracker.name)) },
        text = {
            if (item.tracker is DeletableTracker) {
                Row(Modifier.clickable { remote = !remote }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remote, onCheckedChange = { remote = it })
                    Text(localized(DMR.strings.also_remove_it_from_your_list, item.tracker.name))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                model.remove(item, remote)
            }) { Text(localized(DMR.strings.stop_tracking)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
internal fun <T> Choice(label: String, value: String, choices: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value", maxLines = 1) }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onChoose(key)
                })
            }
        }
    }
}

@Composable
private fun ChapterField(lastRead: Double, total: Long, onSet: (Int) -> Unit) {
    var text by remember(lastRead) { mutableStateOf(lastRead.toInt().toString()) }
    val commit = { text.trim().toIntOrNull()?.takeIf { it >= 0 && it != lastRead.toInt() }?.let(onSet) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(5) },
        singleLine = true,
        label = { Text(if (total > 0) localized(DMR.strings.chapter_of, total) else localized(DMR.strings.chapter)) },
        modifier = Modifier.width(150.dp).onPreviewKeyEvent {
            if (it.key == Key.Enter) {
                commit()
                true
            } else {
                false
            }
        },
        trailingIcon = { TextButton(onClick = { commit() }) { Text(localized(DMR.strings.set)) } },
    )
}

@Composable
private fun DateField(label: String, epochMillis: Long, onSet: (Long) -> Unit) {
    var text by remember(epochMillis) {
        mutableStateOf(if (epochMillis > 0) toLocalDate(epochMillis).toString() else "")
    }
    var error by remember { mutableStateOf(false) }
    val commit = {
        val value = text.trim()
        if (value.isEmpty()) {
            if (epochMillis != 0L) onSet(0L)
        } else {
            try {
                val millis = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                error = false
                if (millis != epochMillis) onSet(millis)
            } catch (e: DateTimeParseException) {
                error = true
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(10) },
        singleLine = true,
        isError = error,
        label = { Text(localized(DMR.strings.yyyy_mm_dd, label)) },
        supportingText = if (epochMillis > 0) ({ Text(formatDate(epochMillis)) }) else null,
        modifier = Modifier.width(220.dp).onPreviewKeyEvent {
            if (it.key == Key.Enter) {
                commit()
                true
            } else {
                false
            }
        },
        trailingIcon = { TextButton(onClick = { commit() }) { Text(localized(DMR.strings.set)) } },
    )
}

@Composable
private fun SearchPane(state: TrackingModel.State, model: TrackingModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = model::setQuery,
            singleLine = true,
            label = { Text(localized(MR.strings.title)) },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.key == Key.Enter) {
                    model.search()
                    true
                } else {
                    false
                }
            },
            leadingIcon = { TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, localized(MR.strings.action_webview_back), onClick = model::closeSearch) },
            trailingIcon = { TooltipIconButton(Icons.Outlined.Search, localized(MR.strings.action_search), onClick = model::search) },
        )
        when {
            state.loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
            state.results?.isEmpty() == true -> Text(localized(DMR.strings.nothing_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.results.orEmpty(), key = { it.remote_id }) { result -> SearchResult(result) { model.bind(result) } }
            }
        }
    }
}

@Composable
private fun SearchResult(result: TrackSearch, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(result.cover_url.takeIf { it.isNotBlank() }, Modifier.size(56.dp, 80.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOf(result.publishing_type, result.start_date, result.publishing_status)
                .filter { it.isNotBlank() }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.summary.isNotBlank()) {
                Text(result.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider()
}

private fun toLocalDate(epochMillis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun browse(url: String) {
    runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }
}
