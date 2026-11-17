package zoukan.desktop.storage

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
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

    /**
     * False when a storage folder was chosen and it is missing (an unmounted or disconnected drive).
     * Nothing may then be created or deleted under [root]: creating it would put downloads on the
     * system disk, and a later re-mount would hide them.
     */
    val isAvailable: Boolean
        get() {
            val chosen = folderPreference.get()
            if (chosen.isBlank() || chosen.contains("://")) return true
            return File(chosen).isDirectory
        }

    /** @throws StorageUnavailableException when [isAvailable] is false */
    fun requireAvailable() {
        if (!isAvailable) throw StorageUnavailableException(root)
    }

    /** True when [folder] is the storage folder or inside it, and the storage folder is missing. */
    fun isMissing(folder: File): Boolean {
        if (isAvailable) return false
        val base = root.absoluteFile.normalize()
        return generateSequence(folder.absoluteFile.normalize()) { it.parentFile }.any { it == base }
    }

    val downloads: File get() = File(root, DOWNLOADS)
    val local: File get() = File(root, LOCAL)
    val autoBackups: File get() = File(root, AUTOMATIC_BACKUPS)

    companion object {
        const val DOWNLOADS = "downloads"
        const val LOCAL = "local"
        const val AUTOMATIC_BACKUPS = "autobackup"
    }
}

/** The chosen storage folder is missing, for example on a drive that is not connected. */
class StorageUnavailableException(val folder: File) :
    java.io.IOException(localized(DMR.strings.storage_folder_unavailable, folder.path))
