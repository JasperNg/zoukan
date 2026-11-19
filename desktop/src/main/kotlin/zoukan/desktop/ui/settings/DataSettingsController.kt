package zoukan.desktop.ui.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.runCatchingCancellable
import zoukan.desktop.local.Archives
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.Images
import java.io.File

/**
 * Non-UI state/business controller for Data and storage settings: storage folder verification and
 * switching, cover/chapter cache clearing, backup/restore trigger guard, and CSV library export.
 */
internal class DataSettingsController(
    private val dirs: ZoukanDirs,
    private val clearCoverCacheAction: () -> Unit = {
        CustomCovers.clearCache(dirs.covers)
        runCatching { Images.clearDiskCache() }
    },
    private val chapterCacheSizeAction: () -> Long = { Archives.cacheSize() },
    private val clearChapterCacheAction: () -> Int = { Archives.clearCache() },
    private val getFavoritesAction: suspend () -> List<Manga> = { Injekt.get<GetFavorites>().await() },
    private val checkStorageAction: (File) -> StorageLocation.Verdict = StorageLocation::check,
    private val applyStorageAction: (ZoukanStorage, String, () -> Unit) -> Unit = StorageLocation::apply,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun clearCoverCache() {
        withContext(ioDispatcher) { clearCoverCacheAction() }
    }

    suspend fun readChapterCacheSize(): Long =
        withContext(ioDispatcher) { chapterCacheSizeAction() }

    suspend fun clearChapterCache(): Result<Int> =
        runCatchingCancellable { withContext(ioDispatcher) { clearChapterCacheAction() } }

    suspend fun checkStorageFolder(picked: File): StorageLocation.Verdict =
        withContext(ioDispatcher) { checkStorageAction(picked) }

    suspend fun hasDefaultStorageDownloads(): Boolean =
        withContext(ioDispatcher) {
            File(dirs.root, ZoukanStorage.DOWNLOADS).listFiles()?.isNotEmpty() == true
        }

    fun applyStorageFolder(storage: ZoukanStorage, path: String, onCacheInvalidated: () -> Unit) {
        applyStorageAction(storage, path, onCacheInvalidated)
    }

    suspend fun exportLibraryCsv(target: File, options: LibraryCsv.Options): Result<Unit> =
        runCatchingCancellable {
            withContext(ioDispatcher) {
                target.writeText(LibraryCsv.generate(getFavoritesAction(), options))
            }
        }

    fun canStartBackupOrRestore(status: String?): Boolean = status == null
}
