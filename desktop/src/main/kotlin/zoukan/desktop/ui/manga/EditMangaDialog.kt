package zoukan.desktop.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.manga.EditManga
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.track.Choice
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
).map { it.toLong() to (statusLabel(it.toLong()) ?: "Unknown") }

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

    fun run(block: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { Toasts.error("Could not save", it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverImage(Cover.of(manga), Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                    if (edit.canEditCover(manga)) {
                        OutlinedButton(onClick = {
                            val dialog = FileDialog(window, "Choose a cover", FileDialog.LOAD).apply {
                                setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") }
                                isVisible = true
                            }
                            val name = dialog.file ?: return@OutlinedButton
                            run {
                                edit.setCover(manga, File(dialog.directory, name))
                                Toasts.show("Cover updated")
                            }
                        }) { Text("Change cover") }
                        if (edit.hasCustomCover(manga)) {
                            TextButton(onClick = { run { edit.deleteCustomCover(manga) } }) { Text("Use the source's cover") }
                        }
                    } else {
                        Text("Add the entry to the library to change its cover.")
                    }
                }
                Column(
                    Modifier.width(420.dp).heightIn(max = 520.dp).verticalScrollWithBar(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isLocal && !manga.favorite) {
                        Text("Details of entries outside the library are not kept; add it to the library to edit them.")
                    }
                    val editable = isLocal || manga.favorite
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(author, { author = it }, label = { Text("Author") }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("Description") }, enabled = editable, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(genres, { genres = it }, label = { Text("Genres, separated by commas") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    if (editable) Choice("Status", statusLabel(status) ?: "Unknown", STATUSES) { status = it }
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val info = EditManga.Info(
                    title = title,
                    author = author,
                    artist = artist,
                    description = description,
                    genres = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    status = status,
                )
                run {
                    if (isLocal || manga.favorite) edit.saveInfo(manga, info)
                    if (notes != manga.notes) edit.saveNotes(manga, notes)
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isLocal && manga.favorite) {
                    TextButton(onClick = {
                        run { edit.resetInfo(manga) }
                        onDismiss()
                    }) { Text("Reset details") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
