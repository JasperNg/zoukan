package zoukan.desktop.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.nulldev.androidcompat.util.NoFollowDelete
import zoukan.desktop.BackupExclusion
import zoukan.desktop.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * On macOS, keeps the storage folder's downloads, local source and automatic backups out of Time
 * Machine while [ZoukanStorage.excludeFromTimeMachine] is on (on by default).
 *
 * Which folders: only a real directory directly in the storage folder whose name is exactly one of
 * [ZoukanStorage.ZOUKAN_FOLDER_NAMES] (case included, checked without following links via
 * [NoFollowDelete]). Never the chosen folder itself, never a folder reached through a link or
 * junction, and never a folder whose name only differs in case: with the home folder as storage
 * folder, `~/Downloads` is left alone. A folder that is not there yet is made (as the Android app
 * makes them), unless something else already answers to its name.
 *
 * Only Zoukan's own marks are taken off: each mark Zoukan puts on is recorded
 * ([ZoukanStorage.timeMachineMarked]), and turning the setting off or changing the storage folder
 * removes the mark only from recorded folders. An exclusion the user made (with
 * `tmutil addexclusion` or in System Settings) stays: a folder that already carries the mark when
 * Zoukan first sees it is treated as the user's, is not recorded, and stays excluded after the
 * setting is turned off. When that cannot be read, the folder is neither marked nor recorded until
 * a later refresh can read it. A removal that fails, or a recorded folder that cannot be reached (a
 * drive that is not connected), keeps its record and is tried again at the next refresh.
 *
 * Only folder metadata changes: the Time Machine attribute on each folder (set in this process with
 * `XATTR_NOFOLLOW`). No file in them is touched. Every step is best-effort: a failure goes to
 * [note] and is never thrown. Other systems: nothing happens.
 */
internal class StorageFolderPrivacy(
    private val storage: ZoukanStorage,
    private val note: (String) -> Unit,
    private val enabled: Boolean = Platform.current == Platform.MACOS,
    private val timeMachine: FolderMark = FolderMark.TimeMachine,
) {
    /** A folder-level mark that can be put on, taken off and read. */
    interface FolderMark {
        /**
         * Puts the mark on [dirs] ([on]) or takes it off. Returns the folders of [dirs] now in the
         * wanted state. Never throws; failures go to [note].
         */
        fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File>

        /**
         * The folders of [dirs] that carry the mark now, whoever put it there; null when that could
         * not be read. Never throws; failures go to [note].
         */
        fun marked(dirs: List<File>, note: (String) -> Unit): Set<File>?

        /** Time Machine's exclusion attribute, set in Zoukan's process (a public system call). */
        object TimeMachine : FolderMark {
            override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File> =
                dirs.filterTo(mutableSetOf()) { dir -> if (on) BackupExclusion.exclude(dir, note) else BackupExclusion.include(dir, note) }

            // Any value counts: taking the mark off removes the attribute whatever it holds
            override fun marked(dirs: List<File>, note: (String) -> Unit): Set<File>? =
                dirs.filterTo(mutableSetOf()) { dir -> BackupExclusion.hasAttribute(dir, note) ?: return null }
        }
    }

    private val timeMachineResult = MutableStateFlow(true)

    /**
     * Whether the last refresh left Time Machine as its setting asks: on, every Zoukan folder of the
     * storage folder is excluded (false while the storage folder cannot be reached); off, every
     * reachable folder Zoukan had excluded is back in. True before the first refresh and on other
     * systems. A folder skipped because it is not Zoukan's own (see the class) is not a failure.
     */
    val timeMachineApplied: StateFlow<Boolean> = timeMachineResult.asStateFlow()

    /**
     * Follows the storage folder and Time Machine setting: applies it now and again after each
     * change, off the UI thread.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        combine(
            storage.folderPreference.changes(),
            storage.excludeFromTimeMachine.changes(),
        ) { _, _ -> }.collect { refresh() }
    }

    /**
     * Applies the Time Machine setting to the current storage folder, and takes Zoukan's marks off
     * every recorded folder that no longer needs them (the setting turned off, a storage folder left).
     */
    @Synchronized
    fun refresh() {
        if (!enabled) return
        timeMachineResult.value = try {
            val wanted = storage.excludeFromTimeMachine.get()
            if (!wanted && storage.timeMachineMarked.get().isEmpty()) {
                true
            } else {
                val folders = currentFolders(wanted)
                update(wanted, folders)
            }
        } catch (e: Exception) {
            note("Could not apply the storage folder's Time Machine setting: $e")
            false
        }
    }

    /**
     * The current storage folder's Zoukan folders that may be marked (made first when [make]), or
     * null when the storage folder cannot be reached. Empty, and nothing made, when not [make].
     */
    private fun currentFolders(make: Boolean): List<File>? {
        if (!make) return emptyList()
        val root = storage.root.absoluteFile
        return try {
            if (storage.isAvailable && root.isDirectory) {
                zoukanFolders(root)
            } else {
                note("The storage folder ${root.path} is not available; its Time Machine setting waits for it")
                null
            }
        } catch (e: Exception) {
            note("Could not find the Zoukan folders of ${root.path}: $e")
            null
        }
    }

    /** Brings Time Machine marks in line with [on]; whether all of it succeeded. */
    private fun update(on: Boolean, folders: List<File>?): Boolean {
        val recorded = storage.timeMachineMarked.get()
        val keep: Set<String> = when {
            !on -> emptySet()
            folders != null -> folders.mapTo(mutableSetOf()) { it.path }
            // The storage folder cannot be reached: its own marks wait for it
            else -> recorded.filterTo(mutableSetOf()) { inStorageFolder(it) }
        }
        val removed = takeOff(recorded - keep)
        if (!on) return removed
        if (folders == null) return false
        val record = storage.timeMachineMarked.get()
        // A folder that already carries the mark when Zoukan first sees it is the user's: never recorded
        val candidates = folders.filter { it.path !in record }
        val already = if (candidates.isEmpty()) emptySet() else timeMachine.marked(candidates, note)
        if (already == null) note("Could not read which Zoukan folders already have the Time Machine mark; they are tried again at the next refresh")
        val fresh = if (already == null) emptyList() else candidates.filter { it !in already }
        val toMark = folders.filter { it in fresh || it.path in record }
        val marked = if (toMark.isEmpty()) emptySet() else timeMachine.set(toMark, true, note)
        val newlyMarked = fresh.filter { it in marked }.map { it.path }
        if (newlyMarked.isNotEmpty()) storage.timeMachineMarked.set(record + newlyMarked)
        return removed && already != null && toMark.all { it in marked }
    }

    /**
     * Takes the Time Machine mark off the recorded [paths] that can be reached, and drops those from
     * the record. A path that cannot be reached, or whose folder is not the one recorded any more,
     * keeps its record. Whether every attempted removal succeeded.
     */
    private fun takeOff(paths: Set<String>): Boolean {
        if (paths.isEmpty()) return true
        val reachable = paths.map(::File).filter { isRecordedFolder(it) }
        if (reachable.size < paths.size) {
            val waiting = paths - reachable.mapTo(mutableSetOf()) { it.path }
            note("Zoukan's Time Machine mark stays on ${waiting.joinToString()} until it can be reached")
        }
        if (reachable.isEmpty()) return true
        val cleared = timeMachine.set(reachable, false, note).mapTo(mutableSetOf()) { it.path }
        if (cleared.isNotEmpty()) storage.timeMachineMarked.set(storage.timeMachineMarked.get() - cleared)
        return reachable.all { it.path in cleared }
    }

    /** A recorded path still names that folder: a real directory with exact case, reached without links. */
    private fun isRecordedFolder(dir: File): Boolean = runCatching {
        val path = dir.toPath()
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !NoFollowDelete.isLink(path) &&
            path.toRealPath().toFile().path == dir.path
    }.getOrDefault(false)

    /** Whether the recorded [path] is a Zoukan folder of the current storage folder. */
    private fun inStorageFolder(path: String): Boolean {
        val root = storage.root.absoluteFile
        val parents = setOfNotNull(
            root.normalize().path,
            runCatching { root.canonicalPath }.getOrNull(),
            runCatching { root.toPath().toRealPath().toString() }.getOrNull(),
        )
        val file = File(path)
        return file.name in ZoukanStorage.ZOUKAN_FOLDER_NAMES && file.parent in parents
    }

    /**
     * The Zoukan folders of [root] that may be marked, as canonical files: each must be a real
     * directory directly in [root] with exactly its expected name. A missing one is made first, when
     * nothing (not even a link, or a name differing only in case) is in its place. Anything else is
     * skipped with a note and never made or marked.
     */
    private fun zoukanFolders(root: File): List<File> {
        val base = root.toPath().toRealPath().toFile()
        return ZoukanStorage.ZOUKAN_FOLDER_NAMES.mapNotNull { name ->
            val dir = File(base, name)
            val path = dir.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !dir.exists()) runCatching { dir.mkdir() }
            val real = if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !NoFollowDelete.isLink(path)) {
                runCatching { path.toRealPath().toFile() }.getOrNull()
            } else {
                null
            }
            if (real != null && real.name == name && real.parentFile == base) {
                real
            } else {
                note("${dir.path} is not Zoukan's own $name folder (missing, a link or named differently); its Time Machine setting is left alone")
                null
            }
        }
    }
}
