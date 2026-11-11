package zoukan.desktop.storage

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.ZoukanDirs
import java.io.File

/**
 * The storage folder: downloads, the local source and automatic backups, in the same layout as the
 * Android app (`downloads/<source>/<manga>/<chapter>.cbz`, `local/`, `autobackup/`), so a synced or
 * copied folder works on both.
 *
 * It defaults to the data folder and can be moved in Settings. The setting uses Android's key
 * (`storage_dir`, an app-state key, so it is never in backups: paths are not portable).
 */
class ZoukanStorage(
    private val dirs: ZoukanDirs,
    store: PreferenceStore,
) {
    /** The chosen folder as a path; blank means the data folder. */
    val folderPreference: Preference<String> = store.getString(Preference.appStateKey("storage_dir"), "")

    val root: File
        get() = folderPreference.get()
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?.let { File(it) }
            ?: dirs.root

    val downloads: File get() = File(root, DOWNLOADS)
    val local: File get() = File(root, LOCAL)
    val autoBackups: File get() = File(root, AUTOMATIC_BACKUPS)

    companion object {
        const val DOWNLOADS = "downloads"
        const val LOCAL = "local"
        const val AUTOMATIC_BACKUPS = "autobackup"
    }
}
