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
    private val store: PreferenceStore,
) {
    /** The chosen folder as a path; blank means the data folder. */
    val folderPreference: Preference<String> = store.getString(Preference.appStateKey("storage_dir"), "")

    /**
     * Desktop only, macOS: keeps the storage folder's [zoukanFolders] out of Spotlight
     * (StorageFolderPrivacy). On by default. An app-state key: it is about this computer's folder.
     */
    val hideFromSpotlight: Preference<Boolean> = store.getBoolean(Preference.appStateKey("zoukan_storage_hide_from_spotlight"), true)

    /**
     * Desktop only, macOS: keeps the storage folder's [zoukanFolders] out of Time Machine
     * (StorageFolderPrivacy). On by default. An app-state key: it is about this computer's folder.
     */
    val excludeFromTimeMachine: Preference<Boolean> = store.getBoolean(Preference.appStateKey("zoukan_storage_exclude_from_time_machine"), true)

    /**
     * Desktop only, macOS: the canonical paths of the folders Zoukan put in Spotlight's Search
     * Privacy list (StorageFolderPrivacy). Only these are ever taken off the list again, so an
     * entry the user made stays. An app-state key: paths are about this computer. Made on first
     * use, so a store without string sets serves the rest of this class.
     */
    val spotlightMarked: Preference<Set<String>> by lazy {
        store.getStringSet(Preference.appStateKey("zoukan_storage_spotlight_marked"), emptySet())
    }

    /**
     * Desktop only, macOS: the canonical paths of the folders Zoukan excluded from Time Machine
     * (StorageFolderPrivacy). Only these lose the exclusion again, so one the user set stays. An
     * app-state key: paths are about this computer. Made on first use, as [spotlightMarked].
     */
    val timeMachineMarked: Preference<Set<String>> by lazy {
        store.getStringSet(Preference.appStateKey("zoukan_storage_time_machine_marked"), emptySet())
    }

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

        /**
         * The folders Zoukan makes in a storage folder at [root]: downloads, the local source and
         * automatic backups. The storage folder itself may hold other things, so only these get
         * folder-level settings (StorageFolderPrivacy).
         */
        fun zoukanFolders(root: File): List<File> = ZOUKAN_FOLDER_NAMES.map { File(root, it) }

        /** The names of [zoukanFolders], exactly as Zoukan and the Android app write them. */
        val ZOUKAN_FOLDER_NAMES: List<String> = listOf(DOWNLOADS, LOCAL, AUTOMATIC_BACKUPS)
    }
}

/** The chosen storage folder is missing, for example on a drive that is not connected. */
class StorageUnavailableException(val folder: File) :
    java.io.IOException(localized(DMR.strings.storage_folder_unavailable, folder.path))
