package zoukan.desktop.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.manga.EditManga
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.track.Choice
import zoukan.i18n.desktop.DMR
import java.awt.FileDialog
import java.io.File

private val STATUSES = listOf(
    SManga.UNKNOWN,
    SManga.ONGOING,
    SManga.COMPLETED,
    SManga.LICENSED,
    SManga.PUBLISHING_FINISHED,
    SManga.CANCELLED,
    SManga.ON_HIATUS,
).map { it.toLong() to (statusLabel(it.toLong()) ?: localized(MR.strings.unknown)) }

/** Edits the entry's details, notes and cover, as the Android app's edit dialog. */
@Composable
fun EditMangaDialog(manga: Manga, onDismiss: () -> Unit) {
    val edit = remember { Injekt.get<EditManga>() }
    val scope = rememberCoroutineScope()
    val window = LocalWindow.current?.window
    var title by remember { mutableStateOf(manga.title) }
    var author by remember { mutableStateOf(manga.author.orEmpty()) }
    var artist by remember { mutableStateOf(manga.artist.orEmpty()) }
    var description by remember { mutableStateOf(manga.description.orEmpty()) }
    var genres by remember { mutableStateOf(manga.genre.orEmpty().joinToString(", ")) }
    var status by remember { mutableStateOf(manga.status) }
    var notes by remember { mutableStateOf(manga.notes) }
    val isLocal = manga.source == LocalSource.ID
    // ZOUKAN -->
    var thumbnailUrl by remember { mutableStateOf(manga.thumbnailUrl.orEmpty()) }
    // KMK: Fill from tracker, choosing the tracker when more than one tracks the entry
    var trackerChoice by remember { mutableStateOf<List<Pair<Track, Tracker>>?>(null) }
    fun fillFromTracker(track: Track, tracker: Tracker) {
        scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) { tracker.getMangaMetadata(track) }
                metadata.title?.takeIf { it.isNotBlank() }?.let { title = it }
                metadata.authors?.takeIf { it.isNotBlank() }?.let { author = it }
                metadata.artists?.takeIf { it.isNotBlank() }?.let { artist = it }
                metadata.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumbnailUrl = it }
                metadata.description?.takeIf { it.isNotBlank() }?.let { description = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: this runs on the window's thread, where an escaping error closes the app
                logcat(LogPriority.ERROR, e)
                Toasts.show(localized(MR.strings.track_error, tracker.name, e.message ?: ""))
            }
        }
    }
    // ZOUKAN <--

    // The writes run in the app's scope, not the dialog's: Save and Reset close the dialog at
    // once, which would cancel a write still running in the dialog's scope and lose the edit
    fun run(block: suspend () -> Unit) {
        AppScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: an OutOfMemoryError on a huge cover still shows "could not save"
                Toasts.error(localized(DMR.strings.could_not_save), e)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.action_edit)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverImage(Cover.of(manga), Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Corners.Medium).coverOutline(Corners.Medium))
                    if (edit.canEditCover(manga)) {
                        OutlinedButton(onClick = {
                            val dialog = FileDialog(window, localized(DMR.strings.choose_a_cover), FileDialog.LOAD).apply {
                                setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") }
                                isVisible = true
                            }
                            val name = dialog.file ?: return@OutlinedButton
                            run {
                                edit.setCover(manga, File(dialog.directory, name))
                                Toasts.show(localized(MR.strings.cover_updated))
                            }
                        }) { Text(localized(DMR.strings.change_cover)) }
                        if (edit.hasCustomCover(manga)) {
                            TextButton(onClick = { run { edit.deleteCustomCover(manga) } }) { Text(localized(DMR.strings.use_the_sources_cover)) }
                        }
                    } else {
                        Text(localized(DMR.strings.add_the_entry_to_the_library))
                    }
                }
                Column(
                    Modifier.width(420.dp).heightIn(max = 520.dp).verticalScrollWithBar(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isLocal && !manga.favorite) {
                        Text(localized(DMR.strings.details_of_entries_outside_the_library))
                    }
                    val editable = isLocal || manga.favorite
                    OutlinedTextField(title, { title = it }, label = { Text(localized(MR.strings.title)) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(author, { author = it }, label = { Text(localized(MR.strings.author)) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(artist, { artist = it }, label = { Text(localized(MR.strings.artist)) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // ZOUKAN -->
                    OutlinedTextField(thumbnailUrl, { thumbnailUrl = it }, label = { Text(localized(SYMR.strings.thumbnail_url)) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // ZOUKAN <--
                    OutlinedTextField(description, { description = it }, label = { Text(localized(DMR.strings.description)) }, enabled = editable, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(genres, { genres = it }, label = { Text(localized(DMR.strings.genres_separated_by_commas)) }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    if (editable) Choice(localized(MR.strings.status), statusLabel(status) ?: localized(MR.strings.unknown), STATUSES) { status = it }
                    OutlinedTextField(notes, { notes = it }, label = { Text(localized(MR.strings.action_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    // ZOUKAN -->
                    if (editable) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val tracks = withContext(Dispatchers.IO) { trackersOf(manga) }
                                when {
                                    tracks.isEmpty() -> Toasts.show(localized(SYMR.strings.entry_not_tracked))
                                    tracks.size > 1 -> trackerChoice = tracks
                                    else -> fillFromTracker(tracks.first().first, tracks.first().second)
                                }
                            }
                        }) { Text(localized(SYMR.strings.fill_from_tracker)) }
                    }
                    // ZOUKAN <--
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val info = EditManga.Info(
                    title = title,
                    author = author,
                    artist = artist,
                    thumbnailUrl = thumbnailUrl,
                    description = description,
                    genres = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    status = status,
                )
                run {
                    if (isLocal || manga.favorite) edit.saveInfo(manga, info)
                    if (notes != manga.notes) edit.saveNotes(manga, notes)
                }
                onDismiss()
            }) { Text(localized(MR.strings.action_save)) }
        },
        dismissButton = {
            Row {
                if (!isLocal && manga.favorite) {
                    TextButton(onClick = {
                        run { edit.resetInfo(manga) }
                        onDismiss()
                    }) { Text(localized(DMR.strings.reset_details)) }
                }
                TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) }
            }
        },
    )
    // ZOUKAN -->
    trackerChoice?.let { tracks ->
        TrackerSelectDialog(
            tracks = tracks,
            onDismissRequest = { trackerChoice = null },
            onTrackerSelect = { tracker, track -> fillFromTracker(track, tracker) },
        )
    }
    // ZOUKAN <--
}

// ZOUKAN -->
/** The trackers tracking [manga] that can fill its details: not the enhanced ones, as on Android */
private suspend fun trackersOf(manga: Manga): List<Pair<Track, Tracker>> {
    val trackerManager = Injekt.get<TrackerManager>()
    return Injekt.get<GetTracks>().await(manga.id).mapNotNull { track ->
        track to (trackerManager.get(track.trackerId) ?: return@mapNotNull null)
    }
        .filterNot { (_, tracker) -> tracker is EnhancedTracker }
}

/** SY's "Select a tracker" when more than one tracker tracks the entry */
@Composable
private fun TrackerSelectDialog(
    tracks: List<Pair<Track, Tracker>>,
    onDismissRequest: () -> Unit,
    onTrackerSelect: (tracker: Tracker, track: Track) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(localized(MR.strings.action_cancel)) }
        },
        title = { Text(localized(SYMR.strings.select_tracker)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                tracks.forEach { (track, tracker) ->
                    OutlinedButton(onClick = {
                        onTrackerSelect(tracker, track)
                        onDismissRequest()
                    }) { Text(tracker.name) }
                }
            }
        },
    )
}
// ZOUKAN <--
