package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.safety_backup_could_not_be_written
import zoukan.i18n.desktop.safety_backup_too_large
import zoukan.i18n.desktop.safety_backup_took_too_long
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Why a [SafetySnapshot] is taken: the change it comes before. Part of the file name, and each keeps its own [SafetySnapshot.KEEP]. */
enum class SnapshotReason(val tag: String) {
    RESTORE("restore"),
    SYNC("sync"),
    CLEAR_DATABASE("clear-database"),
    MIGRATION("migration"),
    CLEAR_CHAPTERS("clear-chapters"),
}

/**
 * Asks whether a change may go ahead although the safety backup before it failed with [error]:
 * true goes ahead, false cancels the change.
 */
fun interface SnapshotFailedPrompt {
    fun continueWithout(error: String): Boolean
}

/**
 * A `.tachibk` backup of the whole library written just before a change that deletes or overwrites
 * library data (a restore, a sync, Clear database, a migration, removing an entry's chapters), so
 * the change can be undone with Restore backup. The Android app takes none.
 *
 * The snapshots go to `backups/safety` in the app's own data folder ([folder]), never to the
 * storage folder's `autobackup` that the Android app reads and that may be shared. Each [reason]
 * keeps its newest [keep]; only files named as this class names them are ever deleted. They are
 * written off the calling thread by [write] (the normal backup creator).
 */
class SafetySnapshot(
    private val folder: () -> File,
    private val write: suspend (File) -> Unit,
    private val keep: Int = KEEP,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    /**
     * Writes a snapshot before a [reason] change and rotates the old ones out. Never throws but for
     * cancellation: a failure, or a write that takes longer than the timeout, is the result.
     */
    suspend fun take(reason: SnapshotReason): Result<File> = try {
        withContext(dispatcher) {
            // One at a time, so two changes at once cannot pick the same name or rotate each other's file
            lock.withLock {
                withTimeout(timeoutMillis) {
                    val dir = folder().apply { mkdirs() }
                    val file = freeFile(dir, "${PREFIX}${reason.tag}_${now().format(FILE_TIME)}")
                    writeWithin(file)
                    val same = if (reason == SnapshotReason.SYNC) sameAsNewest(dir, reason, file) else null
                    if (same != null) {
                        logcat { "The library is as in the safety backup $same, which is kept" }
                        Result.success(same)
                    } else {
                        prune(dir, reason, keep, file)
                        logcat { "Safety backup saved to $file" }
                        Result.success(file)
                    }
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        logcat(LogPriority.ERROR, e) { "The safety backup before ${reason.tag} timed out" }
        Result.failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        logcat(LogPriority.ERROR, e) { "The safety backup before ${reason.tag} failed" }
        Result.failure(e)
    }

    /**
     * [write]s the snapshot to [file]. The backup of the whole library is built in memory, and a
     * large library can run out of memory there: that allocation is the write's own and is released
     * when it fails, so it is a failed snapshot the user decides on (as Android's backup job shows a
     * failure), not a failure of the app. Only an out-of-memory error from this write is turned into one.
     */
    private suspend fun writeWithin(file: File) {
        try {
            write(file)
        } catch (e: OutOfMemoryError) {
            throw SnapshotTooLargeException(e)
        }
    }

    /** The snapshot did not fit in memory; its message is the out-of-memory error's. */
    class SnapshotTooLargeException(cause: OutOfMemoryError) : java.io.IOException(cause.message ?: "Out of memory", cause)

    /**
     * Takes a snapshot before a [reason] change the user started. True when the change may go
     * ahead: the snapshot was saved, or it failed and the user chose to continue in [prompt].
     */
    suspend fun guard(reason: SnapshotReason, prompt: SnapshotFailedPrompt): Boolean {
        val error = take(reason).exceptionOrNull() ?: return true
        val message = errorText(error)
        return try {
            withContext(dispatcher) { prompt.continueWithout(message) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            // A prompt that cannot be shown keeps the data as it is
            logcat(LogPriority.ERROR, e) { "Could not ask whether to continue without a safety backup" }
            false
        }
    }

    companion object {
        /** Snapshots kept for each reason. */
        const val KEEP = 5

        /** A write that takes longer than this counts as failed, so a change never waits forever. */
        const val TIMEOUT_MILLIS = 5 * 60_000L

        internal const val PREFIX = "safety_"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val lock = Mutex()

        /** Where the snapshots go: inside the app's data folder, never the shared storage folder. */
        fun folder(dirs: ZoukanDirs): File = File(dirs.backups, "safety")

        /** The app's snapshots, with the data folder and the backup creator looked up when one is taken. */
        fun forApp(): SafetySnapshot = SafetySnapshot(
            folder = { folder(Injekt.get<ZoukanDirs>()) },
            // The default choices: library, chapters, history, tracking, categories, settings; no private settings
            write = { file -> DesktopBackups(Injekt.get<ZoukanDirs>().android).create(file, BackupOptions(), replaceExisting = false) },
        )

        /**
         * What a failure says, in the app's words: for the prompt before a change and for the sync
         * notification, which the OS notification centre can show and keep. Never the error's own
         * message, which can name the backup file and so the data folder and the user's name; [take]
         * logs it to the app log.
         */
        internal fun errorText(error: Throwable): String = when (error) {
            is SnapshotTooLargeException -> localized(DMR.strings.safety_backup_too_large)
            is TimeoutCancellationException -> localized(DMR.strings.safety_backup_took_too_long)
            else -> localized(DMR.strings.safety_backup_could_not_be_written)
        }

        /**
         * Deletes [reason]'s snapshots in [folder] beyond the newest [keep], never [justWritten].
         * Newest by the time in the name, which this class writes; other files are never touched.
         */
        internal fun prune(folder: File, reason: SnapshotReason, keep: Int, justWritten: File) {
            snapshots(folder, reason)
                .filter { it.first != justWritten }
                .drop((keep - 1).coerceAtLeast(0))
                .forEach { (file, _) ->
                    if (!file.delete()) logcat(LogPriority.WARN) { "Could not delete the old safety backup $file" }
                }
        }

        /**
         * The newest earlier [reason] snapshot in [folder] when [written] has the same bytes, after
         * [written] is deleted; else null, and [written] stays. For a sync, which takes a snapshot each
         * time it would restore: when the same entries fail to restore on every sync and the library
         * does not change, each sync would add a copy of the same library and rotate the snapshot from
         * before the first of them out. Equal bytes are an equal backup, so a library that changed in
         * any way the backup holds is always a new snapshot.
         */
        internal fun sameAsNewest(
            folder: File,
            reason: SnapshotReason,
            written: File,
            bufferSize: Int = 64 * 1024,
            open: (File) -> java.io.InputStream = { it.inputStream() },
        ): File? {
            val newest = snapshots(folder, reason).firstOrNull { it.first != written }?.first ?: return null
            // As streams through a fixed buffer, never whole: a large library's snapshots would
            // otherwise be held twice on the heap. A compare that fails keeps the new snapshot
            val same = try {
                newest.length() == written.length() && open(newest).use { a -> open(written).use { b -> sameBytes(a, b, bufferSize) } }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Could not compare the safety backup with ${newest.name}: $e" }
                false
            }
            // A copy that cannot be deleted stays, and the snapshots rotate as usual
            return newest.takeIf { same && written.delete() }
        }

        /** Whether [a] and [b] hold the same bytes, read [bufferSize] at a time. */
        private fun sameBytes(a: java.io.InputStream, b: java.io.InputStream, bufferSize: Int): Boolean {
            val bufA = ByteArray(bufferSize)
            val bufB = ByteArray(bufferSize)
            while (true) {
                val n = a.readNBytes(bufA, 0, bufferSize)
                val m = b.readNBytes(bufB, 0, bufferSize)
                if (n != m) return false
                if (n == 0) return true
                if (!java.util.Arrays.equals(bufA, 0, n, bufB, 0, m)) return false
            }
        }

        /** [reason]'s snapshots in [folder], newest first, by the time in the name. */
        private fun snapshots(folder: File, reason: SnapshotReason): List<Pair<File, Pair<String, Int>>> {
            val pattern = Regex("""${Regex.escape(PREFIX + reason.tag)}_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})(?:-(\d+))?\.tachibk""")
            return folder.listFiles().orEmpty()
                .filter { it.isFile }
                .mapNotNull { file -> pattern.matchEntire(file.name)?.let { m -> file to (m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 1)) } }
                .sortedWith(compareByDescending<Pair<File, Pair<String, Int>>> { it.second.first }.thenByDescending { it.second.second })
        }

        /** [base] plus `.tachibk` if free, else `-2`, `-3`, ...: a snapshot never takes an existing file's name. */
        internal fun freeFile(folder: File, base: String): File = AutoBackup.freeFile(folder, base)
    }
}
