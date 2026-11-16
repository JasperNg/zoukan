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

    /** Uses [folder] (blank path: the data folder). Never moves, copies or deletes anything. */
    fun apply(storage: ZoukanStorage, folder: String, invalidate: () -> Unit) {
        storage.folderPreference.set(folder)
        invalidate()
    }
}
