package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.repository.MangaMergeRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadManager.CleanupItem
import zoukan.desktop.download.DownloadManager.CleanupPlan
import zoukan.desktop.download.DownloadManager.CleanupReason
import zoukan.i18n.desktop.DMR
import java.io.File

/** Set while a clean-up looks or deletes, so a second one waits, as Android's single job. */
private val cleanupRunning = mutableStateOf(false)

/** The plan found, shown for the user to confirm; null when no preview is open. */
private val cleanupPreview = mutableStateOf<CleanupPlan?>(null)

/**
 * SY's "Clean up downloaded chapters" (Settings > Advanced): deletes unfinished downloads, and
 * optionally read chapters and entries no longer in the library. Unlike SY it first shows what it
 * will delete, never deletes a file it does not know as a chapter, and deletes a folder of no entry
 * only when the user ticks it there (see PARITY.md).
 */
@Composable
fun CleanupDownloadsPreference() {
    var open by remember { mutableStateOf(false) }
    val running by cleanupRunning
    val preview by cleanupPreview
    SettingRow(
        localized(SYMR.strings.clean_up_downloaded_chapters),
        localized(SYMR.strings.delete_unused_chapters),
        enabled = !running,
        onClick = { open = true },
    ) { if (running) CircularProgressIndicator(Modifier.size(24.dp)) }
    if (open) {
        CleanupDownloadsDialog(onDismiss = { open = false }) { removeRead, removeNonFavorite ->
            open = false
            findCleanup(removeRead, removeNonFavorite)
        }
    }
    preview?.let { plan ->
        CleanupPreviewDialog(
            plan,
            // Found with the plan, for the downloads folder's volume: the delete does what this says
            canTrash = plan.toTrash,
            onDismiss = { cleanupPreview.value = null },
        ) { orphans ->
            cleanupPreview.value = null
            runCleanup(plan, orphans)
        }
    }
}

/** The children of merged entries: in no library themselves, but their parent's downloads. */
private suspend fun mergedChildIds(): Set<Long> {
    val merges = Injekt.get<MangaMergeRepository>()
    return merges.getMergedManga().flatMap { parent ->
        merges.getMergedMangaById(parent.id).map { it.id }.filter { it != parent.id }
    }.toSet()
}

private fun findCleanup(removeRead: Boolean, removeNonFavorite: Boolean) {
    if (cleanupRunning.value) return
    cleanupRunning.value = true
    Toasts.show(localized(DMR.strings.cleanup_finding))
    AppScope.launch {
        try {
            val plan = Injekt.get<DownloadManager>().planCleanup(
                removeRead,
                removeNonFavorite,
                allManga = { Injekt.get<MangaRepository>().getAll() },
                chaptersOf = { Injekt.get<GetChaptersByMangaId>().await(it) },
                protectedIds = mergedChildIds(),
            )
            if (plan.items.isEmpty()) {
                Toasts.show(localized(SYMR.strings.no_folders_to_cleanup))
            } else {
                cleanupPreview.value = plan
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toasts.error(localized(DMR.strings.cleaning_up_downloads_failed), e)
        } finally {
            cleanupRunning.value = false
        }
    }
}

private fun runCleanup(plan: CleanupPlan, orphans: Set<File>) {
    if (cleanupRunning.value) return
    cleanupRunning.value = true
    Toasts.show(localized(SYMR.strings.starting_cleanup))
    AppScope.launch {
        try {
            // Into the trash only when the preview said so; a file the trash refuses is kept, never deleted for good
            val result = Injekt.get<DownloadManager>().applyCleanupResult(plan, orphans, toTrash = plan.toTrash)
            Toasts.show(
                when (result.deleted) {
                    0 -> localized(SYMR.strings.no_folders_to_cleanup)
                    1 -> localized(DMR.strings.cleanup_done_removed_1_folder)
                    else -> localized(DMR.strings.cleanup_done_removed_folders, result.deleted)
                },
            )
            if (result.notDeleted > 0) Toasts.show(localized(DMR.strings.cleanup_not_deleted, result.notDeleted))
            if (result.skipped > 0) Toasts.show(localized(DMR.strings.cleanup_skipped_changed, result.skipped))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toasts.error(localized(DMR.strings.cleaning_up_downloads_failed), e)
        } finally {
            cleanupRunning.value = false
        }
    }
}

@Composable
private fun CleanupDownloadsDialog(onDismiss: () -> Unit, onConfirm: (removeRead: Boolean, removeNonFavorite: Boolean) -> Unit) {
    var removeRead by remember { mutableStateOf(true) }
    var removeNonFavorite by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(SYMR.strings.clean_up_downloaded_chapters)) },
        text = {
            Column {
                // Listed in the next step, where the user picks which ones go
                CheckRow(localized(SYMR.strings.clean_orphaned_downloads), checked = true, enabled = false) {}
                CheckRow(localized(SYMR.strings.clean_read_downloads), removeRead) { removeRead = it }
                CheckRow(localized(SYMR.strings.clean_read_entries_not_in_library), removeNonFavorite) { removeNonFavorite = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(removeRead, removeNonFavorite) }) { Text(localized(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** What the clean-up found: counts and sizes, and the orphaned folders to tick one by one (none ticked). */
@Composable
internal fun CleanupPreviewDialog(
    plan: CleanupPlan,
    canTrash: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (orphans: Set<File>) -> Unit,
) {
    var ticked by remember(plan) { mutableStateOf(emptySet<File>()) }
    val others = plan.others
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.cleanup_preview_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                fun List<CleanupItem>.summary(reason: CleanupReason) = filter { it.reason == reason }.let { it.size to formatSize(it.sumOf { i -> i.bytes }) }
                listOf(
                    CleanupReason.UNFINISHED to DMR.strings.cleanup_preview_unfinished,
                    CleanupReason.READ to DMR.strings.cleanup_preview_read,
                    CleanupReason.NOT_IN_LIBRARY to DMR.strings.cleanup_preview_not_in_library,
                ).forEach { (reason, text) ->
                    val (count, size) = others.summary(reason)
                    if (count > 0) Text(localized(text, count, size))
                }
                if (others.isEmpty() && plan.orphans.isEmpty()) Text(localized(DMR.strings.cleanup_preview_nothing))
                if (plan.orphans.isNotEmpty()) {
                    Text(localized(DMR.strings.cleanup_preview_orphans), Modifier.padding(top = 8.dp))
                    plan.orphans.forEach { orphan ->
                        CheckRow(
                            "${orphan.file.parentFile?.name}/${orphan.file.name} (${formatSize(orphan.bytes)})",
                            orphan.file in ticked,
                        ) { on -> ticked = if (on) ticked + orphan.file else ticked - orphan.file }
                    }
                }
                Text(localized(DMR.strings.cleanup_preview_keeps_unknown), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text(
                    localized(if (canTrash) DMR.strings.cleanup_preview_trash else DMR.strings.cleanup_preview_permanent),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canTrash) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ticked) }, enabled = others.isNotEmpty() || ticked.isNotEmpty()) {
                Text(localized(MR.strings.action_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}
