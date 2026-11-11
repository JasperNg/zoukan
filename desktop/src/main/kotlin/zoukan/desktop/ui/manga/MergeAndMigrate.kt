package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergeMangaSettingsUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.migration.MigrateManga
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import java.io.Serializable

/** Why a global search was opened: to browse, or to pick an entry for a merge or a migration. */
sealed interface SearchPurpose : Serializable {
    data object Browse : SearchPurpose {
        private fun readResolve(): Any = Browse
    }

    /** Merge the picked entry into [mangaId] (a merged entry, or the entry a new merge starts from) */
    data class Merge(val mangaId: Long) : SearchPurpose

    /** Move [mangaId] from [sourceId] to the picked entry */
    data class Migrate(val mangaId: Long, val sourceId: Long) : SearchPurpose

    /** Pick the target of [mangaId] in a bulk migration's list; the list migrates it later */
    data class MigrateInList(val mangaId: Long, val sourceId: Long) : SearchPurpose
}

/** A search field in the header of a pick search, to try another title. */
@Composable
fun SearchAgainField(query: String, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(query) }
    OutlinedTextField(
        text,
        { text = it },
        singleLine = true,
        placeholder = { Text("Title") },
        modifier = Modifier.width(320.dp).onPreviewKeyEvent {
            if (it.key == Key.Enter && text.isNotBlank()) {
                onSearch(text.trim())
                true
            } else {
                false
            }
        },
        trailingIcon = { TooltipIconButton(Icons.Default.Search, "Search", onClick = { if (text.isNotBlank()) onSearch(text.trim()) }) },
    )
}

/** Confirms the merge or migration of a picked search result. */
@Composable
fun PickDialog(purpose: SearchPurpose, picked: Manga, onDismiss: () -> Unit) {
    when (purpose) {
        SearchPurpose.Browse -> Unit
        is SearchPurpose.Merge -> MergeDialog(purpose.mangaId, picked, onDismiss)
        is SearchPurpose.Migrate -> MigrateDialog(purpose.mangaId, picked, onDismiss)
        // Picked right away in GlobalSearchScreen, no dialog
        is SearchPurpose.MigrateInList -> Unit
    }
}

@Composable
private fun MergeDialog(mangaId: Long, picked: Manga, onDismiss: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Merge with ${picked.title}?") },
        text = {
            Text(
                "Both entries' chapters show in one merged entry, which takes the library's place of the first one. " +
                    "You can remove an entry from the merge later.",
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { SmartSearchMerge().smartSearchMerge(picked, mangaId) } }
                        .onSuccess { merged ->
                            onDismiss()
                            Toasts.show("Merged")
                            // Back to where the merge started, showing the merged entry
                            navigator.popUntil { it is MangaScreen && it.mangaId == mangaId }
                            navigator.replace(MangaScreen(merged.id))
                        }
                        .onFailure {
                            busy = false
                            Toasts.error("Merging failed", it)
                        }
                }
            }) { Text(if (busy) "Merging…" else "Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun MigrateDialog(mangaId: Long, picked: Manga, onDismiss: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val migrate = remember { Injekt.get<MigrateManga>() }
    val preference = remember { migrate.flags() }
    var flags by remember { mutableStateOf(preference.get()) }
    var busy by remember { mutableStateOf(false) }
    val run = { replace: Boolean ->
        busy = true
        preference.set(flags)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = Injekt.get<GetManga>().await(mangaId) ?: error("The entry no longer exists")
                    migrate(current, picked, replace, flags)
                }
            }.onSuccess {
                onDismiss()
                Toasts.show(if (replace) "Migrated to ${picked.title}" else "Copied to ${picked.title}")
                navigator.popUntil { it is MangaScreen && it.mangaId == mangaId }
                navigator.replace(MangaScreen(picked.id))
            }.onFailure {
                busy = false
                Toasts.error("Migration failed", it)
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Migrate to ${picked.title}?") },
        text = {
            Column {
                Text("Take along:", style = MaterialTheme.typography.bodyMedium)
                MigrationFlag.entries.filter { it != MigrationFlag.CUSTOM_COVER }.forEach { flag ->
                    Row(
                        Modifier.fillMaxWidth().clickable { flags = if (flag in flags) flags - flag else flags + flag },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(flag in flags, onCheckedChange = { flags = if (it) flags + flag else flags - flag })
                        Text(MigrateManga.label(flag))
                    }
                }
                if (busy) {
                    Spacer(Modifier.padding(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching chapters…")
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = { run(false) }) { Text("Copy") }
                TextButton(enabled = !busy, onClick = { run(true) }) { Text("Migrate") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** The entries a merged entry merges: which gives the details, which get updates, removing one. */
@Composable
fun MergedEntriesDialog(mangaId: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var references by remember { mutableStateOf<List<Pair<MergedMangaReference, Manga?>>?>(null) }
    val sourceManager = remember { Injekt.get<SourceManager>() }
    suspend fun load() {
        references = withContext(Dispatchers.IO) {
            Injekt.get<GetMergedReferencesById>().await(mangaId)
                .filter { it.mangaSourceId != exh.source.MERGED_SOURCE_ID }
                .map { ref -> ref to ref.mangaId?.let { Injekt.get<GetManga>().await(it) } }
        }
    }
    LaunchedEffect(mangaId) { load() }
    fun update(change: MergeMangaSettingsUpdate) = scope.launch {
        withContext(Dispatchers.IO) { Injekt.get<UpdateMergedSettings>().await(change) }
        load()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp),
        title = { Text("Merged entries") },
        text = {
            val list = references
            if (list == null) {
                CircularProgressIndicator()
                return@AlertDialog
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { (ref, manga) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ref.isInfoManga,
                            onClick = {
                                // One entry gives the details
                                list.forEach { (other, _) ->
                                    if (other.isInfoManga != (other.id == ref.id)) {
                                        update(MergeMangaSettingsUpdate(other.id, other.id == ref.id, null, null, null, null))
                                    }
                                }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(manga?.title ?: ref.mangaUrl, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                sourceManager.getOrStub(ref.mangaSourceId).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(Modifier.clickable { update(MergeMangaSettingsUpdate(ref.id, null, !ref.getChapterUpdates, null, null, null)) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ref.getChapterUpdates, onCheckedChange = null)
                            Text("Updates", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.clickable { update(MergeMangaSettingsUpdate(ref.id, null, null, null, !ref.downloadChapters, null)) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ref.downloadChapters, onCheckedChange = null)
                            Text("Downloads", style = MaterialTheme.typography.bodySmall)
                        }
                        TooltipIconButton(Icons.Outlined.Delete, "Remove from the merge", enabled = list.size > 1, onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { Injekt.get<DeleteMergeById>().await(ref.id) }
                                load()
                            }
                        })
                    }
                    HorizontalDivider()
                }
                Text(
                    "The selected entry gives the merged entry its details. Updates: check it for new chapters. " +
                        "Downloads: download its new chapters after an update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Opens the pick search for a merge or a migration of [manga]. */
fun pickScreen(manga: Manga, merge: Boolean): Screen = GlobalSearchScreen(
    manga.ogTitle,
    if (merge) SearchPurpose.Merge(manga.id) else SearchPurpose.Migrate(manga.id, manga.source),
)
