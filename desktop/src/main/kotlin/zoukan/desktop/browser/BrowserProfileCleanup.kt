package zoukan.desktop.browser

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.compat.localized
import zoukan.desktop.download.SafeDelete
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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

    /** Whether a profile folder that is a link was logged: once a run, not at every clean. */
    private val linkLogged = java.util.concurrent.atomic.AtomicBoolean()

    /** Folders deleted with their contents. */
    internal val FOLDER_NAMES = setOf("Cache", "Code Cache")

    /**
     * Cleans [browserCache] (the profile and Chromium's root cache folder), its `Default` profile
     * folder and their `Network` folders. Returns how many files and folders it deleted.
     */
    fun clean(browserCache: File): Int = runCatching {
        val root = browserCache.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching 0
        if (SafeDelete.isLink(root)) {
            if (linkLogged.compareAndSet(false, true)) {
                logcat(LogPriority.WARN) { "The browser's profile folder is a link; it is not cleaned" }
            }
            return@runCatching 0
        }
        val profile = root.resolve("Default")
        // A linked folder (a Windows junction too, which Java reports as a plain folder) is not cleaned through
        listOf(root, profile, root.resolve("Network"), profile.resolve("Network"))
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !SafeDelete.isLink(it) }
            .sumOf { cleanFolder(it) }
    }.onFailure { logcat(LogPriority.WARN, it) { "Could not clean the browser's profile" } }.getOrDefault(0)

    private fun cleanFolder(folder: Path): Int {
        val entries = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList())
        var deleted = 0
        for (entry in entries) {
            val name = entry.fileName?.toString() ?: continue
            // A link (or a Windows junction) is never followed, and never deleted: it is not something Chromium made
            if (SafeDelete.isLink(entry)) continue
            val isFolder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            val delete = if (isFolder) name in FOLDER_NAMES else isRecord(name)
            if (!delete) continue
            val ok = if (isFolder) deleteTree(entry) else runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)
            if (ok) deleted++
        }
        return deleted
    }

    /**
     * Android's "Clear WebView data": deletes everything in [browserCache], cookies and site data
     * too. A link (or a Windows junction) in it is removed as itself, never what it points to. A
     * [browserCache] that is itself a link is left alone, and that fails with an IOException saying
     * so: the user asked for the data to go, and must not be told it went. A file Chromium still
     * holds stays until the next time.
     */
    fun clearAll(browserCache: File) {
        val root = browserCache.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (SafeDelete.isLink(root)) throw IOException(localized(DMR.strings.is_a_link_and_was_not_cleared, browserCache.name))
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        val entries = runCatching { Files.list(root).use { it.toList() } }.getOrDefault(emptyList())
        entries.forEach { SafeDelete.deleteTreeNoFollow(it) }
    }

    /** Whether the file [name] is one of the browser's records that this deletes. */
    internal fun isRecord(name: String): Boolean = name in FILE_NAMES || FILE_PREFIXES.any { name.startsWith(it) }

    /** Deletes [folder] and what it holds, without following links or junctions. True when it is gone. */
    private fun deleteTree(folder: Path): Boolean {
        SafeDelete.deleteTreeNoFollow(folder)
        return !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)
    }
}
