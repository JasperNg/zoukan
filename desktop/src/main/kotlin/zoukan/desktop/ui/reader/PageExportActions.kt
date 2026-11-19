package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.EditManga
import zoukan.i18n.desktop.DMR
import java.io.File

/**
 * Handles saving, copying, combining, and setting reader pages as custom covers on [appScope] so
 * actions complete even if the reader closes immediately.
 */
internal class PageExportActions(
    private val appScope: CoroutineScope,
    private val mangaId: Long,
    private val getManga: GetManga,
    private val editManga: Lazy<EditManga>,
    private val preferences: DesktopPreferences,
    private val readPageBytes: suspend (Int) -> ByteArray,
    private val currentState: () -> ReaderScreenModel.State,
) {
    /** Saves the page into the Pictures folder, in a folder per entry when that setting is on. */
    fun savePage(index: Int) = runExport(localized(DMR.strings.could_not_save_the_picture)) {
        val bytes = readPageBytes(index)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${index + 1}")
        Toasts.show(localized(DMR.strings.picture_saved_to, file.parent))
    }

    fun copyPage(index: Int) = runExport(localized(DMR.strings.could_not_copy_the_picture)) {
        PageImages.copy(readPageBytes(index))
        Toasts.show(localized(MR.strings.copied_to_clipboard_plain))
    }

    fun saveCombined(target: PageActionsTarget) = runExport(localized(DMR.strings.could_not_save_the_picture)) {
        val bytes = combined(target)
        val file = PageImages.save(bytes, picturesFolder(), "${pageFileName()} - ${target.page + 1}-${target.extra!! + 1}")
        Toasts.show(localized(DMR.strings.picture_saved_to, file.parent))
    }

    fun copyCombined(target: PageActionsTarget) = runExport(localized(DMR.strings.could_not_copy_the_picture)) {
        PageImages.copy(combined(target))
        Toasts.show(localized(MR.strings.copied_to_clipboard_plain))
    }

    /** Sets the page as the entry's cover: a library entry's custom cover, or a local entry's cover file, as Android. */
    fun setPageAsCover(index: Int) = runExport(localized(MR.strings.notification_cover_update_failed)) {
        val manga = getManga.await(mangaId) ?: return@runExport
        val edit = editManga.value
        if (!edit.canEditCover(manga)) {
            Toasts.show(localized(MR.strings.notification_first_add_to_library))
            return@runExport
        }
        // ZOUKAN: in memory, never through a plain file: the page can come from an encrypted chapter
        edit.setCover(manga, readPageBytes(index))
        Toasts.show(localized(MR.strings.cover_updated))
    }

    /** Runs a page action to the end even when the reader closes, as Android's launchNonCancellable. */
    private fun runExport(failure: String, block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.rethrowIfVmFailure()
                logcat(LogPriority.ERROR, e) { failure }
                Toasts.error(failure, e)
            }
        }
    }

    private suspend fun combined(target: PageActionsTarget): ByteArray {
        if (target.extra == null) error(localized(DMR.strings.not_a_double_page))
        val background = readerBackground(preferences.readerTheme().get())
        return PageImages.combine(readPageBytes(target.page), readPageBytes(target.extra), target.leftFirst, background.toArgb())
    }

    private fun picturesFolder(): File {
        val root = PageImages.picturesDir()
        val title = currentState().manga?.title
        return if (preferences.folderPerManga().get() && !title.isNullOrBlank()) {
            PageImages.entryPicturesFolder(root, title)
        } else {
            root
        }
    }

    /** "<title> - <chapter>", as Android names saved pages */
    private fun pageFileName(): String = "${currentState().manga?.title.orEmpty()} - ${currentState().chapter?.name.orEmpty()}"
}
