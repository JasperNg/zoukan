package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.domain.toSManga
import zoukan.desktop.manga.EditManga
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.reader.PageImages
import zoukan.desktop.ui.track.Choice
import java.awt.FileDialog
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** The entry actions of Android's manga screen that the desktop reaches from the More menu. */
object EntryActions {

    /** The entry's web address, as Android's Share shares it. */
    fun mangaUrl(manga: Manga, source: Source?): String? {
        val http = source as? HttpSource ?: return null
        return runCatching { http.getMangaUrl(manga.toSManga()) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Copies the entry's link: the desktop stand-in for Android's Share. */
    fun copyLink(manga: Manga, source: Source?) {
        val url = mangaUrl(manga, source) ?: return Toasts.show("This source has no web page")
        copyToClipboard(url)
        Toasts.show("Link copied to clipboard")
    }

    /** Sets a custom update interval in days, as Android does: stored negative, 0 for automatic. */
    suspend fun setFetchInterval(manga: Manga, days: Int) {
        Injekt.get<UpdateManga>().awaitUpdateFetchInterval(manga.copy(fetchInterval = -days))
    }

    /** Saves the cover into the Pictures folder under the entry's title, as Android's Save. */
    suspend fun saveCover(manga: Manga): File = withContext(Dispatchers.IO) {
        PageImages.save(coverBytes(manga), PageImages.picturesDir(), manga.title)
    }

    suspend fun copyCover(manga: Manga) = withContext(Dispatchers.IO) { PageImages.copy(coverBytes(manga)) }

    private suspend fun coverBytes(manga: Manga): ByteArray {
        val cover = Cover.of(manga) ?: error("The entry has no cover")
        return Images.coverFile(cover).readBytes()
    }
}

/**
 * Android's update interval dialog: when the next update is expected and, with Settings > Library
 * > Smart update > "Predict next release time" on, a custom interval or none at all.
 */
@Composable
fun FetchIntervalDialog(manga: Manga, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val editable = remember {
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().get()
    }
    val interval = manga.fetchInterval
    var selected by remember { mutableIntStateOf(if (interval < 0) -interval else 0) }
    val nextUpdateDays = remember(manga.expectedNextUpdate) {
        manga.expectedNextUpdate?.let { Instant.now().until(it, ChronoUnit.DAYS).toInt().coerceAtLeast(0) }
    }
    val choices = listOf(FetchInterval.MANUAL_DISABLE to "Disabled", 0 to "Default") +
        (1..FetchInterval.MAX_INTERVAL).map { it to days(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update frequency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (nextUpdateDays != null && interval >= 0) {
                        "New chapters predicted to be released in around ${days(nextUpdateDays)}, checking around every ${days(interval)}."
                    } else if (interval == -FetchInterval.MANUAL_DISABLE) {
                        "Library updates skip this entry."
                    } else if (nextUpdateDays != null) {
                        "Next check in around ${days(nextUpdateDays)}, every ${days(-interval)} as set."
                    } else {
                        "This entry is either completed, or there is no predicted release date."
                    },
                )
                if (editable) {
                    Text("Custom update frequency:")
                    Choice("Every", choices.first { it.first == selected }.second, choices) { selected = it }
                } else {
                    Text(
                        "Turn on Settings > Library > Smart update > \"Predict next release time\" to set it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editable) {
                    scope.launch {
                        runCatching { EntryActions.setFetchInterval(manga, selected) }
                            .onFailure { Toasts.error("Could not set the update frequency", it) }
                    }
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun days(count: Int) = if (count == 1) "1 day" else "$count days"

/** Android's cover dialog: the cover large, with copy (for Share), save, change and delete. */
@Composable
fun CoverDialog(manga: Manga, onDismiss: () -> Unit) {
    val edit = remember { Injekt.get<EditManga>() }
    val scope = rememberCoroutineScope()
    val window = LocalWindow.current?.window
    fun run(failure: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { Toasts.error(failure, it) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverImage(
                    Cover.of(manga),
                    Modifier.width(320.dp).aspectRatio(2f / 3f).clip(Corners.Large),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverAction("Copy", Icons.Outlined.ContentCopy) {
                        run("Could not copy the cover") {
                            EntryActions.copyCover(manga)
                            Toasts.show("Copied to clipboard")
                        }
                    }
                    CoverAction("Save", Icons.Outlined.Save) {
                        run("Could not save the cover") {
                            val file = EntryActions.saveCover(manga)
                            Toasts.show("Cover saved to ${file.parent}")
                        }
                    }
                    if (edit.canEditCover(manga)) {
                        CoverAction("Change", Icons.Outlined.Edit) {
                            val dialog = FileDialog(window, "Choose a cover", FileDialog.LOAD).apply {
                                setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in COVER_EXTENSIONS }
                                isVisible = true
                            }
                            val name = dialog.file ?: return@CoverAction
                            run("Failed to update cover") {
                                edit.setCover(manga, File(dialog.directory, name))
                                Toasts.show("Cover updated")
                            }
                        }
                    }
                    if (edit.hasCustomCover(manga)) {
                        CoverAction("Delete", Icons.Outlined.Delete) {
                            run("Could not delete the cover") {
                                edit.deleteCustomCover(manga)
                                Toasts.show("Custom cover deleted")
                            }
                        }
                    }
                }
                if (!edit.canEditCover(manga)) {
                    Text(
                        "Add the entry to the library to change its cover.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

@Composable
private fun CoverAction(title: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = Corners.Large) {
        Icon(icon, null)
        Spacer(Modifier.width(6.dp))
        Text(title)
    }
}

/**
 * Android's possible duplicates dialog: library entries with the same title, each to open or to
 * migrate to [target], or add [target] anyway.
 */
@Composable
fun DuplicateDialog(
    target: Manga,
    duplicates: List<MangaWithChapterCount>,
    onAddAnyway: () -> Unit,
    onOpen: (Manga) -> Unit,
    onMigrate: (Manga) -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceManager = remember { Injekt.get<SourceManager>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Possible duplicates") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScrollWithBar(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.title, style = MaterialTheme.typography.titleMedium)
                Text("You have entries in your library with a similar name. Select an entry to migrate or add anyway.")
                duplicates.forEach { (manga, chapterCount) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Corners.Medium) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(manga) }.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(Cover.of(manga), Modifier.width(56.dp).aspectRatio(2f / 3f).clip(Corners.Medium).coverOutline(Corners.Medium))
                            Column(Modifier.weight(1f)) {
                                Text(manga.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        sourceManager.getOrStub(manga.source).toString(),
                                        if (chapterCount == 1L) "1 chapter" else "$chapterCount chapters",
                                        statusLabel(manga.status),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onOpen(manga) }) { Text("Open") }
                            TextButton(onClick = { onMigrate(manga) }) { Text("Migrate") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAddAnyway) { Text("Add anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Downloads a chosen number of the next unread chapters. */
@Composable
fun CustomDownloadDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val count = text.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download next chapters") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                label = { Text("Number of unread chapters") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = count != null, onClick = {
                count?.let(onConfirm)
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Android's missing chapter indicators for the chapters as listed: [before] maps a chapter to the
 * count of chapters missing between it and the row above it, and [end] counts those missing after
 * the last row (the ones below chapter 1, when the newest are listed first).
 */
data class MissingChapters(val before: Map<Long, Int>, val end: Int) {
    companion object {
        val NONE = MissingChapters(emptyMap(), 0)

        /** [descending] says whether [listed] goes from the highest chapter to the lowest. */
        fun of(listed: List<Chapter>, descending: Boolean): MissingChapters {
            if (listed.isEmpty()) return NONE
            val before = HashMap<Long, Int>()
            for (i in 1 until listed.size) {
                val (lower, higher) = if (descending) listed[i] to listed[i - 1] else listed[i - 1] to listed[i]
                calculateChapterGap(higher, lower).takeIf { it > 0 }?.let { before[listed[i].id] = it }
            }
            // The chapters before the lowest one: Android counts them from its number
            val lowest = if (descending) listed.last() else listed.first()
            val belowFirst = (floor(lowest.chapterNumber).toInt() - 1).coerceAtLeast(0)
            return if (descending) {
                MissingChapters(before, belowFirst)
            } else {
                if (belowFirst > 0) before[lowest.id] = belowFirst
                MissingChapters(before, 0)
            }
        }
    }
}

/** A "Missing N chapters" divider, as Android's chapter list shows. */
@Composable
fun MissingChaptersRow(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).alpha(0.78f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(if (count == 1) "Missing 1 chapter" else "Missing $count chapters", style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Whether to show [MissingChaptersRow]s: Settings > Library > "Hide missing chapter indicators". */
@Composable
fun showMissingChapters(): Boolean {
    val preference = remember { Injekt.get<LibraryPreferences>().hideMissingChapters() }
    val hide by preference.changes().collectAsState(preference.get())
    return !hide
}
