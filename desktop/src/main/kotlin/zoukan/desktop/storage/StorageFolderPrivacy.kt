package zoukan.desktop.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import zoukan.desktop.BackupExclusion
import zoukan.desktop.Platform
import zoukan.desktop.SpotlightExclusion
import java.io.File

/**
 * On macOS, keeps the storage folder's downloads, local source and automatic backups out of
 * Spotlight and Time Machine, each while its setting is on ([ZoukanStorage.hideFromSpotlight],
 * [ZoukanStorage.excludeFromTimeMachine], both on by default). Only the folders Zoukan makes
 * ([ZoukanStorage.zoukanFolders]) are marked, never the chosen folder itself, which may hold other
 * things. A setting turned off takes its mark away again, and a storage folder that is changed
 * loses the marks Zoukan put on it.
 *
 * Only folder metadata changes: the Time Machine attribute on each folder (set in this process)
 * and its entry in Spotlight's Search Privacy list (changed by a short-lived helper process, see
 * [SpotlightExclusion]). No file in them is touched. Every step is best-effort: a
 * failure goes to [note] and is never thrown. A missing storage folder (a drive that is not
 * connected) is skipped. Other systems: nothing happens.
 */
internal class StorageFolderPrivacy(
    private val storage: ZoukanStorage,
    private val note: (String) -> Unit,
    private val enabled: Boolean = Platform.current == Platform.MACOS,
    private val timeMachine: FolderMark = FolderMark.TimeMachine,
    private val spotlight: FolderMark = FolderMark.Spotlight,
) {
    /** A folder-level mark that can be put on and taken off. */
    fun interface FolderMark {
        /** Puts the mark on [dirs] ([on]) or takes it off. Never throws; failures go to [note]. */
        fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit)

        /** Time Machine's exclusion attribute, set in Zoukan's process (a public system call). */
        object TimeMachine : FolderMark {
            override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit) = dirs.forEach { dir ->
                if (on) BackupExclusion.exclude(dir, note) else BackupExclusion.include(dir, note)
            }
        }

        /** Spotlight's Search Privacy list, changed in one run of the helper process for all [dirs]. */
        object Spotlight : FolderMark {
            override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit) {
                SpotlightExclusion().set(dirs, on, note)
            }
        }
    }

    /** The storage folder the marks were last applied to; guarded by `this`. */
    private var applied: File? = null

    /**
     * Follows the storage folder and both settings: applies them now and again after each change,
     * off the UI thread. Each storage folder Zoukan leaves loses its marks.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        combine(
            storage.folderPreference.changes(),
            storage.hideFromSpotlight.changes(),
            storage.excludeFromTimeMachine.changes(),
        ) { _, _, _ -> }.collect { refresh() }
    }

    /** Applies the settings to the current storage folder, after clearing a previous one's marks. */
    @Synchronized
    fun refresh() {
        if (!enabled) return
        try {
            val root = storage.root.absoluteFile
            applied?.takeIf { it != root }?.let(::clear)
            applied = root
            apply(root)
        } catch (e: Exception) {
            note("Could not apply the storage folder's Spotlight and Time Machine settings: $e")
        }
    }

    private fun apply(root: File) {
        if (!storage.isAvailable) {
            note("The storage folder ${root.path} is not available; its Spotlight and Time Machine settings wait for it")
            return
        }
        val toSpotlight = storage.hideFromSpotlight.get()
        val toTimeMachine = storage.excludeFromTimeMachine.get()
        val dirs = ZoukanStorage.zoukanFolders(root).onEach { dir ->
            // Made now, as the Android app makes them, so the first download is already covered. A
            // folder that is not there has no mark to take off
            if (!dir.isDirectory && (toSpotlight || toTimeMachine)) runCatching { dir.mkdirs() }
        }.filter { it.isDirectory }
        if (dirs.isEmpty()) return
        timeMachine.set(dirs, toTimeMachine, note)
        spotlight.set(dirs, toSpotlight, note)
    }

    /**
     * Takes both marks off [root]'s Zoukan folders, when [root] can still be reached. Creates
     * nothing: a folder that is not there is left out.
     */
    fun clear(root: File) {
        if (!enabled) return
        try {
            if (!root.isDirectory) return
            val dirs = ZoukanStorage.zoukanFolders(root).filter { it.isDirectory }
            if (dirs.isEmpty()) return
            timeMachine.set(dirs, false, note)
            spotlight.set(dirs, false, note)
        } catch (e: Exception) {
            note("Could not clear the Spotlight and Time Machine settings of ${root.path}: $e")
        }
    }
}
