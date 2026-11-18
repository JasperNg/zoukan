package zoukan.desktop.browser

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes what the built-in browser's profile (`<data>/cache/browser`) records about the pages it
 * visited and keeps no use for: history, top sites, favicons, visited links, the network action
 * predictor, Reporting and NEL reports, omnibox shortcuts, the HTTP and code caches, and Chromium's
 * own log. Chromium's files are not encrypted, so the less of them on disk the better.
 *
 * It runs before Chromium starts and again at quit. It never touches what sign-ins and sites need:
 * cookies, local storage, IndexedDB, Local State (the DNS over HTTPS setting) and the rest.
 *
 * It never throws: a file that cannot be deleted (Chromium still holds it, on Windows) is left
 * for the next run.
 */
internal object BrowserProfileCleanup {
    /** Files whose name starts with one of these, with their `-journal` and `-wal` companions. */
    internal val FILE_PREFIXES = listOf(
        "History",
        "Top Sites",
        "Favicons",
        "Network Action Predictor",
        "Reporting and NEL",
        "Shortcuts",
    )

    /** Files with exactly these names. */
    internal val FILE_NAMES = setOf("Visited Links", "chrome_debug.log")

    /** Folders deleted with their contents. */
    internal val FOLDER_NAMES = setOf("Cache", "Code Cache")

    /**
     * Cleans [browserCache] (the profile and Chromium's root cache folder), its `Default` profile
     * folder and their `Network` folders. Returns how many files and folders it deleted.
     */
    fun clean(browserCache: File): Int = runCatching {
        val root = browserCache.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching 0
        val profile = root.resolve("Default")
        listOf(root, profile, root.resolve("Network"), profile.resolve("Network"))
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .sumOf { cleanFolder(it) }
    }.onFailure { logcat(LogPriority.WARN, it) { "Could not clean the browser's profile" } }.getOrDefault(0)

    private fun cleanFolder(folder: Path): Int {
        val entries = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList())
        var deleted = 0
        for (entry in entries) {
            val name = entry.fileName?.toString() ?: continue
            // A link is never followed, and never deleted: it is not something Chromium made
            if (Files.isSymbolicLink(entry)) continue
            val isFolder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            val delete = if (isFolder) name in FOLDER_NAMES else isRecord(name)
            if (!delete) continue
            val ok = if (isFolder) deleteTree(entry) else runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)
            if (ok) deleted++
        }
        return deleted
    }

    /** Whether the file [name] is one of the browser's records that this deletes. */
    internal fun isRecord(name: String): Boolean = name in FILE_NAMES || FILE_PREFIXES.any { name.startsWith(it) }

    /** Deletes [folder] and what it holds, without following links. True when it is gone. */
    private fun deleteTree(folder: Path): Boolean {
        runCatching {
            Files.walkFileTree(
                folder,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)
    }
}
