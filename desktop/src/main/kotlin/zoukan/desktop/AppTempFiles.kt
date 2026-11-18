package zoukan.desktop

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.download.SafeDelete
import java.io.File
import java.nio.file.Files

/**
 * Temporary files that hold what the app has decrypted or downloaded (a page set as a cover), kept
 * in a folder of the app's own instead of the system's temporary folder, so that they stay in the
 * data folder and anything a crash leaves behind is removed at the next start.
 */
object AppTempFiles {
    @Volatile
    private var root: File? = null

    /** Uses [dir] from now on, emptying it first: nothing in it is in use at startup. Never throws. */
    fun init(dir: File) {
        runCatching { clear(dir) }.onFailure { logcat(LogPriority.WARN, it) { "Could not empty the temporary folder" } }
        root = dir
    }

    /**
     * A new folder in the app's temporary folder. Before [init] (only in tests) it is in the
     * system's temporary folder.
     */
    fun newDir(prefix: String): File {
        val base = root ?: return Files.createTempDirectory(prefix).toFile()
        base.mkdirs()
        return Files.createTempDirectory(base.toPath(), prefix).toFile()
    }

    /** Deletes what is in [dir], without following links, and keeps [dir]. */
    internal fun clear(dir: File) {
        dir.listFiles().orEmpty().forEach { SafeDelete.deleteTreeNoFollow(it.toPath()) }
    }
}
