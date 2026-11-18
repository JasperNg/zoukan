package zoukan.desktop.ui.settings

import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException

/** What happens when the storage folder is changed in Settings. */
internal object StorageLocation {
    sealed interface Verdict {
        /** Can be used; [hasDownloads] when it already holds a downloads folder with files. */
        data class Ok(val hasDownloads: Boolean) : Verdict

        /** Not a folder Zoukan can write to. */
        data object NotWritable : Verdict
    }

    /**
     * Whether Zoukan can write in [folder]. The only thing written is an empty probe file, removed
     * at once. Reads the disk: call it off the UI thread (the folder may be on a network drive).
     */
    fun check(folder: File): Verdict {
        val writable = try {
            (folder.isDirectory || folder.mkdirs()) && File.createTempFile(".zoukan-write-check", null, folder).delete()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!writable) return Verdict.NotWritable
        val hasDownloads = File(folder, ZoukanStorage.DOWNLOADS).listFiles()?.isNotEmpty() == true
        return Verdict.Ok(hasDownloads)
    }

    /** Why a storage folder may be copied to a cloud service by macOS. */
    enum class CloudSync {
        /**
         * In iCloud Drive (`~/Library/Mobile Documents`), another provider's `~/Library/CloudStorage`
         * folder, or an older sync app's folder in the home folder ([LEGACY_SYNC_FOLDERS]): those apps
         * sync everything in their folder, so it counts as synced, not only possibly synced.
         */
        SYNCED,

        /** In `~/Desktop` or `~/Documents`, which iCloud syncs when "Desktop & Documents Folders" is on. */
        MAYBE_SYNCED,
    }

    /**
     * Whether a storage folder at [folder] is, or may be, synced to a cloud service on macOS, where
     * [home] is the user's home folder; null when neither. Path logic only: nothing is read from
     * disk, so a symbolic link is judged by its own path. A warning, never a block.
     */
    fun cloudSync(folder: File, home: File): CloudSync? {
        // macOS file systems usually ignore case
        val path = File(folder.absoluteFile.normalize().path.lowercase())
        val base = File(home.absoluteFile.normalize().path.lowercase())
        fun under(vararg parts: String) = path.startsWith(parts.fold(base) { dir, part -> File(dir, part.lowercase()) })
        // The first folder under the home folder, for the older sync apps' folders
        // Both paths are absolute and normalized, and the folder is inside the home folder, so
        // relativize cannot throw here
        val top = if (path.toPath().startsWith(base.toPath())) {
            base.toPath().relativize(path.toPath()).takeIf { it.nameCount > 0 }?.getName(0)?.toString()
        } else {
            null
        }
        return when {
            under("Library", "Mobile Documents") || under("Library", "CloudStorage") -> CloudSync.SYNCED
            top != null && LEGACY_SYNC_FOLDERS.any { it.matches(top) } -> CloudSync.SYNCED
            under("Desktop") || under("Documents") -> CloudSync.MAYBE_SYNCED
            else -> null
        }
    }

    /**
     * Folders directly in the home folder that older versions of sync apps (before macOS's File
     * Provider moved them to `~/Library/CloudStorage`) keep in sync, lower-case: Dropbox (also
     * "Dropbox (Personal)" and a team's "Dropbox (Name)"), OneDrive (also a work or school account's
     * "OneDrive - Name") and Google Drive (with its "My Drive" in it).
     */
    private val LEGACY_SYNC_FOLDERS = listOf(
        Regex("""dropbox( \(.+\))?"""),
        Regex("""onedrive( - .+)?"""),
        Regex("""google drive"""),
    )

    /** Uses [folder] (blank path: the data folder). Never moves, copies or deletes anything. */
    fun apply(storage: ZoukanStorage, folder: String, invalidate: () -> Unit) {
        storage.folderPreference.set(folder)
        invalidate()
    }
}
