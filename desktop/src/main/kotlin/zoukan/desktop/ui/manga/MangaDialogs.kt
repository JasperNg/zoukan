package zoukan.desktop.ui.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.ui.common.ConfirmDestructiveDialog
import zoukan.i18n.desktop.DMR
import java.io.File

// ZOUKAN -->
/** The one dialog the entry screen has open on top of it, chosen by the user; the screen model's own questions (tracking, duplicates, categories) are separate. */
internal sealed interface MangaDialog {
    data object MergedEntries : MangaDialog
    data object Edit : MangaDialog
    data object Cover : MangaDialog
    data object FetchInterval : MangaDialog
    data object Clear : MangaDialog

    /** A merged entry asks which of its entries Open folder, the source name and the like act on */
    data class PickMergedEntry(val action: MergedAction) : MangaDialog

    /** The second question before the user's own local files go to the trash */
    data class ConfirmLocalFiles(val folders: List<File>) : MangaDialog

    /** The question before the downloads of several chapters are deleted */
    data class ConfirmDeleteDownloads(val chapters: List<Chapter>) : MangaDialog

    /** Migrate the entry to a library entry with the same title */
    data class MigrateDuplicate(val duplicate: Manga) : MangaDialog
}

/** Shows [dialog], if any; [onShow] opens another one, or closes this one with null. */
@Composable
internal fun MangaDialogHost(
    dialog: MangaDialog?,
    manga: Manga,
    model: MangaScreenModel,
    isLocal: Boolean,
    onShow: (MangaDialog?) -> Unit,
) {
    val dismiss = { onShow(null) }
    // The user's own local files are never "downloaded data": they get their own option and a second confirmation
    var localFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    val clearing = dialog is MangaDialog.Clear
    // Loaded fresh each time the Clear dialog opens; never a list left over from an earlier opening
    LaunchedEffect(clearing) { localFolders = if (clearing) model.localFolders() else emptyList() }
    when (dialog) {
        null -> Unit
        MangaDialog.MergedEntries -> MergedEntriesDialog(manga.id, onDismiss = dismiss)
        MangaDialog.Edit -> EditMangaDialog(manga, onDismiss = dismiss)
        MangaDialog.Cover -> CoverDialog(manga, onDismiss = dismiss)
        MangaDialog.FetchInterval -> FetchIntervalDialog(manga, onDismiss = dismiss)
        is MangaDialog.PickMergedEntry -> MergedEntryPicker(dialog.action, model, onDismiss = dismiss)
        MangaDialog.Clear -> ClearMangaDialog(
            onDismissRequest = dismiss,
            showDownloadedData = !isLocal,
            localFolders = localFolders.map { it.name },
            onConfirm = { downloads, chapters, local ->
                model.clearManga(downloads, chapters)
                if (local) onShow(MangaDialog.ConfirmLocalFiles(localFolders))
            },
        )
        is MangaDialog.ConfirmLocalFiles -> ConfirmDestructiveDialog(
            title = localized(DMR.strings.delete_local_files_title),
            text = localized(DMR.strings.delete_local_files_text, dialog.folders.joinToString { it.absolutePath }),
            confirmLabel = localized(DMR.strings.move_to_trash),
            onConfirm = { model.deleteLocalFiles(dialog.folders) },
            onDismiss = dismiss,
        )
        is MangaDialog.ConfirmDeleteDownloads -> ConfirmDestructiveDialog(
            title = localizedPlural(DMR.plurals.delete_selected_downloads_title, dialog.chapters.size, dialog.chapters.size),
            text = localized(DMR.strings.delete_selected_downloads_text),
            confirmLabel = localized(DMR.strings.delete_downloads),
            onConfirm = { model.deleteDownloads(dialog.chapters) },
            onDismiss = dismiss,
        )
        is MangaDialog.MigrateDuplicate -> MigrateDialog(dialog.duplicate.id, manga, onDismiss = dismiss, onMigrated = {})
    }
}
// ZOUKAN <--
