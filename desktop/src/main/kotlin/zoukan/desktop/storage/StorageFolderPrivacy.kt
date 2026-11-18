package zoukan.desktop.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import zoukan.desktop.BackupExclusion
import zoukan.desktop.Platform
import zoukan.desktop.SpotlightExclusion
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * On macOS, keeps the storage folder's downloads, local source and automatic backups out of
 * Spotlight and Time Machine, each while its setting is on ([ZoukanStorage.hideFromSpotlight],
 * [ZoukanStorage.excludeFromTimeMachine], both on by default).
 *
 * Which folders: only a real directory directly in the storage folder whose name is exactly one of
 * [ZoukanStorage.ZOUKAN_FOLDER_NAMES] (case included, after resolving links). Never the chosen
 * folder itself, never a folder reached through a link, and never a folder whose name only differs
 * in case: with the home folder as storage folder, `~/Downloads` is left alone. A folder that is not
 * there yet is made (as the Android app makes them), unless something else already answers to its
 * name.
 *
 * Only Zoukan's own marks are taken off: each mark Zoukan puts on is recorded
 * ([ZoukanStorage.spotlightMarked], [ZoukanStorage.timeMachineMarked]), and turning a setting off or
 * changing the storage folder removes the mark only from recorded folders. An exclusion the user made
 * (with `tmutil addexclusion` or in System Settings) stays. A removal that fails, or a recorded folder
 * that cannot be reached (a drive that is not connected), keeps its record and is tried again at the
 * next refresh.
 *
 * Only folder metadata changes: the Time Machine attribute on each folder (set in this process) and
 * its entry in Spotlight's Search Privacy list (changed by a short-lived helper process, see
 * [SpotlightExclusion], started only when something is to change). No file in them is touched. Every
 * step is best-effort: a failure goes to [note] and is never thrown. Other systems: nothing happens.
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
        /**
         * Puts the mark on [dirs] ([on]) or takes it off. Returns the folders of [dirs] now in the
         * wanted state. Never throws; failures go to [note].
         */
        fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File>

        /** Time Machine's exclusion attribute, set in Zoukan's process (a public system call). */
        object TimeMachine : FolderMark {
            override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File> =
                dirs.filterTo(mutableSetOf()) { dir -> if (on) BackupExclusion.exclude(dir, note) else BackupExclusion.include(dir, note) }
        }

        /** Spotlight's Search Privacy list, changed in one run of the helper process for all [dirs]. */
        object Spotlight : FolderMark {
            override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File> =
                SpotlightExclusion().set(dirs, on, note)
        }
    }

    /** One kind of mark: how it is set, where it is recorded, and whether it is cheap to set again. */
    private class Kind(
        val name: String,
        val mark: FolderMark,
        val record: Preference<Set<String>>,
        val wanted: Preference<Boolean>,
        /** Set again on every refresh (cheap), instead of only on folders not yet recorded. */
        val markAgain: Boolean,
        val result: MutableStateFlow<Boolean>,
    )

    private val timeMachineResult = MutableStateFlow(true)
    private val spotlightResult = MutableStateFlow(true)

    /**
     * Whether the last refresh left Time Machine as its setting asks: on, every Zoukan folder of the
     * storage folder is excluded (false while the storage folder cannot be reached); off, every
     * reachable folder Zoukan had excluded is back in. True before the first refresh and on other
     * systems. A folder skipped because it is not Zoukan's own (see the class) is not a failure.
     */
    val timeMachineApplied: StateFlow<Boolean> = timeMachineResult.asStateFlow()

    /** As [timeMachineApplied], for Spotlight's Search Privacy list. */
    val spotlightApplied: StateFlow<Boolean> = spotlightResult.asStateFlow()

    private val kinds by lazy {
        listOf(
            Kind("Time Machine", timeMachine, storage.timeMachineMarked, storage.excludeFromTimeMachine, markAgain = true, timeMachineResult),
            Kind("Spotlight", spotlight, storage.spotlightMarked, storage.hideFromSpotlight, markAgain = false, spotlightResult),
        )
    }

    /**
     * Follows the storage folder and both settings: applies them now and again after each change,
     * off the UI thread.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        combine(
            storage.folderPreference.changes(),
            storage.hideFromSpotlight.changes(),
            storage.excludeFromTimeMachine.changes(),
        ) { _, _, _ -> }.collect { refresh() }
    }

    /**
     * Applies both settings to the current storage folder, and takes Zoukan's marks off every
     * recorded folder that no longer needs them (a setting turned off, a storage folder left).
     */
    @Synchronized
    fun refresh() {
        if (!enabled) return
        try {
            val wanted = kinds.map { it.wanted.get() }
            if (wanted.none { it } && kinds.all { it.record.get().isEmpty() }) {
                kinds.forEach { it.result.value = true }
                return
            }
            val folders = currentFolders(wanted.any { it })
            kinds.forEachIndexed { i, kind ->
                kind.result.value = try {
                    update(kind, wanted[i], folders)
                } catch (e: Exception) {
                    note("Could not apply the storage folder's ${kind.name} setting: $e")
                    false
                }
            }
        } catch (e: Exception) {
            note("Could not apply the storage folder's Spotlight and Time Machine settings: $e")
            kinds.forEach { it.result.value = false }
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
                note("The storage folder ${root.path} is not available; its Spotlight and Time Machine settings wait for it")
                null
            }
        } catch (e: Exception) {
            note("Could not find the Zoukan folders of ${root.path}: $e")
            null
        }
    }

    /** Brings [kind] in line with its setting [on]; whether all of it succeeded. */
    private fun update(kind: Kind, on: Boolean, folders: List<File>?): Boolean {
        val recorded = kind.record.get()
        val keep: Set<String> = when {
            !on -> emptySet()
            folders != null -> folders.mapTo(mutableSetOf()) { it.path }
            // The storage folder cannot be reached: its own marks wait for it
            else -> recorded.filterTo(mutableSetOf()) { inStorageFolder(it) }
        }
        val removed = takeOff(kind, recorded - keep)
        if (!on) return removed
        if (folders == null) return false
        val record = kind.record.get()
        val toMark = if (kind.markAgain) folders else folders.filter { it.path !in record }
        val marked = if (toMark.isEmpty()) emptySet() else kind.mark.set(toMark, true, note)
        val newlyMarked = marked.map { it.path }.filter { it !in record }
        if (newlyMarked.isNotEmpty()) kind.record.set(record + newlyMarked)
        return removed && toMark.all { it in marked }
    }

    /**
     * Takes [kind]'s mark off the recorded [paths] that can be reached, and drops those from the
     * record. A path that cannot be reached, or whose folder is not the one recorded any more, keeps
     * its record. Whether every attempted removal succeeded.
     */
    private fun takeOff(kind: Kind, paths: Set<String>): Boolean {
        if (paths.isEmpty()) return true
        val reachable = paths.map(::File).filter { isRecordedFolder(it) }
        if (reachable.size < paths.size) {
            val waiting = paths - reachable.mapTo(mutableSetOf()) { it.path }
            note("Zoukan's ${kind.name} mark stays on ${waiting.joinToString()} until it can be reached")
        }
        if (reachable.isEmpty()) return true
        val cleared = kind.mark.set(reachable, false, note).mapTo(mutableSetOf()) { it.path }
        if (cleared.isNotEmpty()) kind.record.set(kind.record.get() - cleared)
        return reachable.all { it.path in cleared }
    }

    /** A recorded path still names that folder: a directory, reached without links. */
    private fun isRecordedFolder(dir: File): Boolean =
        runCatching { dir.isDirectory && dir.canonicalPath == dir.path }.getOrDefault(false)

    /** Whether the recorded [path] is a Zoukan folder of the current storage folder. */
    private fun inStorageFolder(path: String): Boolean {
        val root = storage.root.absoluteFile
        val parents = setOfNotNull(root.normalize().path, runCatching { root.canonicalPath }.getOrNull())
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
        val base = root.canonicalFile
        return ZoukanStorage.ZOUKAN_FOLDER_NAMES.mapNotNull { name ->
            val dir = File(base, name)
            if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && !dir.exists()) runCatching { dir.mkdir() }
            val real = runCatching { dir.canonicalFile }.getOrNull()
            if (real != null && dir.isDirectory && real.name == name && real.parentFile == base) {
                real
            } else {
                note("${dir.path} is not Zoukan's own $name folder (missing, a link or named differently); its Spotlight and Time Machine settings are left alone")
                null
            }
        }
    }
}
