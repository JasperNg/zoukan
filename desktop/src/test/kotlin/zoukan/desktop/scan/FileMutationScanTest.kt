package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * Deleting files, changing permissions and writing extended attributes go through the helpers that
 * check the path is the app's own and never follow a symbolic link (`SafeDelete`, `NoFollowDelete`,
 * `AppTempFiles.clear`, `CacheFolders`, `BrowserProfileCleanup`, `DataFolderPrivacy`). Fails on
 * these anywhere else in the app's own code:
 *
 * - `deleteRecursively()`, which follows a linked folder and deletes what it points to (okio's
 *   `deleteRecursively(path)` too);
 * - `Files.walk` / `walkFileTree` / `walk()` in a function that also deletes;
 * - a one-file delete: `File.delete()`, `deleteOnExit()`, `Files.delete`, `Files.deleteIfExists`,
 *   `Path.deleteIfExists()` / `deleteExisting()`, okio's `delete(path, mustExist = ...)`;
 * - permission changes: `setPosixFilePermissions`, `setReadable`, `setWritable`, `setExecutable`,
 *   `Files.setAttribute`, `setOwner`;
 * - extended attributes: `setxattr`, `removexattr`, `UserDefinedFileAttributeView`, and starting
 *   `xattr`, `tmutil`, `mdutil`, `chflags`, `chmod`, `chown`, `attrib` or `icacls`.
 *
 * A one-file delete never follows a link itself (a link is removed as itself); the risk is a path
 * that is not the app's to delete. So one is accepted without an entry when its target is
 * provably the code's own: a `val` made by `createTempFile` in the same function. Every other site
 * is listed by file with its exact count and the folder it deletes in. `x.delete()` on a
 * preference key (`prefs.key().delete()`, a `val` taken from a preference store) is not a file.
 *
 * Moving a file over another (`renameTo`, `Files.move` with `REPLACE_EXISTING`) is left out: a
 * rename replaces a link at the target as itself and never writes through it.
 */
class FileMutationScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    @Test
    fun `tree deletes, permission changes and attribute writes go through the checked helpers`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            FileMutationScanner.lines(source.text).filter { source.isOwn(it) }.map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Delete trees with SafeDelete.delete / deleteTreeNoFollow and change permissions or attributes " +
                "in the checked helpers (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags tree deletes, a walk that deletes, permission and attribute changes`() {
        FileMutationScanner.lines(
            """
            fun clear(dir: File) {
                dir.deleteRecursively()
            }
            fun prune(root: Path) {
                Files.walk(root).use { s -> s.forEach { Files.delete(it) } }
            }
            fun lock(f: File) {
                Files.setPosixFilePermissions(f.toPath(), perms)
                f.setWritable(false)
                libc.setxattr(f.path, "name", value, size, 0, 0)
                ProcessBuilder("tmutil", "addexclusion", f.path).start()
                ProcessBuilder("/usr/bin/xattr", "-w", "x", "y", f.path).start()
            }
            fun tidy(f: File, dir: File) {
                f.delete()
                Files.deleteIfExists(f.toPath())
                File(dir, "cover.jpg").delete()
                dir.listFiles().forEach { it.delete() }
                f.toPath().deleteIfExists()
                fileSystem.delete(path, mustExist = false)
                fileSystem.deleteRecursively(path)
                f.deleteOnExit()
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 5, 8, 9, 10, 11, 12, 15, 16, 17, 18, 19, 20, 21, 22)
    }

    @Test
    fun `accepts deletes of a file the function made, preference keys, a walk that only reads, and mentions`() {
        FileMutationScanner.lines(
            """
            fun size(root: Path): Long = Files.walk(root).use { s -> s.mapToLong { Files.size(it) }.sum() }
            fun tidy(dir: File) {
                val tmp = File.createTempFile("x", ".tmp", dir)
                val other: Path = Files.createTempFile("y", ".tmp")
                try { write(tmp) } finally { tmp.delete() }
                Files.deleteIfExists(other)
                File.createTempFile(".check", null, dir).delete()
                preferences.lastSync().delete()
                val old = store.getInt("old", -1)
                old.delete()
                secrets.delete(KEY)
                // dir.deleteRecursively()
                log("deleteRecursively() and setxattr( and tmutil are only words here")
                ProcessBuilder("pmset", "-g", "ps").start()
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        const val HELPER = "a checked helper: resolves the path inside the app's own folder and never follows a link"
        const val DATA = "the app's data folder"
        const val EXTENSIONS = "the extensions folder in the app's data folder: the installer's own files at names it makes from a checked package folder and version code"
        const val DOWNLOADS = "a chapter folder in the downloads folder that the downloader is writing: its own temporary and part files, listed by name in that folder (a link is removed as itself)"
        val ALLOWED = listOf(
            // The helpers themselves
            Allowed("zoukan/desktop/CacheFolders.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/browser/BrowserProfileCleanup.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/download/SafeDelete.kt", "", HELPER, count = null),
            Allowed("xyz/nulldev/androidcompat/util/NoFollowDelete.kt", "", "the no-follow tree delete itself", count = null),
            Allowed("zoukan/desktop/DataFolderPrivacy.kt", "", "the privacy-mark helper: sets owner-only permissions and the Time Machine attribute on the app's own folders, without following links (XATTR_NOFOLLOW, isSymbolicLink)", count = null),
            Allowed("zoukan/desktop/secrets/FileSecretStore.kt", "setPosixFilePermissions", "makes the secret file it has just written owner-only, by a name it checked", function = "restrict"),
            // The app's data folder, at names the app chose
            Allowed("app/CrashReporter.kt", "", "$DATA: the logs and crashes folders. Only the app's own `.running-<pid>` markers and a crash copy it was writing (a linked logs or crashes folder is where the app writes those files too); a JVM crash file only directly in the working or temp folder, and only when it is Zoukan's (deleteJvmCrashOriginal)", count = 5),
            Allowed("app/SingleInstance.kt", "", "$DATA: the single-instance port and command files", count = 3),
            Allowed("app/SelfTest.kt", "file.delete()", "$DATA: the crash report the self-test has just written", function = "run"),
            Allowed("backup/SafetySnapshot.kt", "file.delete()", "$DATA: the safety-backup folder, only names this class writes for that reason", function = "prune"),
            Allowed("backup/SafetySnapshot.kt", "written.delete()", "$DATA: the safety-backup folder, the snapshot take() has just written under a free name, dropped because it has the same bytes as the newest one", function = "sameAsNewest"),
            Allowed("browser/ChromiumNatives.kt", "", "$DATA: the Chromium install folder, its own download file", count = 2),
            Allowed("browser/blocking/UserBlockLists.kt", "", "$DATA: the block-list folder, its own lists and their .tmp files", count = 2),
            Allowed("compat/AtomicFile.kt", "newFile.delete()", "the `.new` file AtomicFile writes beside the file it replaces", count = 2),
            Allowed("db/DesktopDatabase.kt", "", "$DATA: the database's own pre-migration copies, by name", count = 2),
            Allowed("manga/CustomCovers.kt", "", "$DATA: the custom covers and the cover cache, files by name (a link is removed as itself)", count = 2),
            Allowed("secrets/EncryptedFileSystem.kt", "", "$DATA: the image cache, Coil's own entries and their .tmp files", count = 3),
            Allowed("secrets/EncryptedFiles.kt", "it.delete()", "the temporary files of its own writes in the folder of the file they replace, by their exact name pattern", function = "deleteTemporaryFiles"),
            Allowed("ui/common/Images.kt", "", "$DATA: the cover cache, one cover by its hashed name", count = 2),
            Allowed("update/UpdateChecker.kt", "", "$DATA: the updates folder, the installer it downloads and its .part file", count = 3),
            Allowed("update/Updates.kt", "", "$DATA: the updates folder, the installer and signature it downloaded", count = 7),
            Allowed("SpotlightExclusion.kt", "Files.deleteIfExists(file)", "the helper process's temporary work folder: only that process's own JVM crash files, by its process id, regular files only", function = "deleteCrashFiles"),
            Allowed("xyz/nulldev/androidcompat/androidimpl/CustomContext.java", "", "$DATA: the settings folder, an extension's preferences file at a name made a valid file name (SafePath)", count = 2),
            Allowed("xyz/nulldev/androidcompat/io/sharedprefs/JavaSharedPreferences.kt", "deleteIfExists()", "$DATA: the settings folder, the stale .tmp beside a settings file", count = 2),
            Allowed("zoukan/extension/ApkConverter.kt", "tmp.delete()", EXTENSIONS, function = "convert"),
            Allowed("zoukan/extension/DesktopExtensionInstaller.kt", "", EXTENSIONS, count = 9),
            Allowed("zoukan/extension/DesktopExtensionLoader.kt", "", EXTENSIONS, count = 5),
            Allowed("zoukan/extension/PreviousFiles.kt", "", EXTENSIONS, count = 3),
            // The storage folder (may have come from Android): only the app's own files there
            Allowed("download/ChapterDownloader.kt", "", DOWNLOADS, function = "download", count = 4),
            Allowed("download/TallImageSplitter.kt", "", "$DOWNLOADS; a page's split parts, which it wrote itself", count = 4),
            Allowed("backup/AutoBackup.kt", "file.delete()", "the automatic backup folder: only the names this install wrote and recorded, never the Android app's (a link is removed as itself)", function = "prune"),
            Allowed("local/LocalSource.kt", "Files.deleteIfExists(path)", "the local source folder: only the app's own stale cover temporary files (`.cover.<digits>.tmp`), regular files over an hour old, never a link", function = "removeStaleCoverTemps"),
            Allowed("manga/EditManga.kt", "old.delete()", "the local source folder: the entry's folder is checked to be inside it by its canonical path (mangaDir), and the old cover is replaced by the new one there (a link is removed as itself)", function = "setCover"),
            // A folder the user chose
            Allowed("backup/DesktopBackups.kt", "", "the backup's own .tmp beside the backup file, written and checked before it replaces anything", count = 2),
            Allowed("ui/reader/PageActions.kt", "", "the pictures folder: the page image saved under the same cleaned name, and its own .tmp", function = "save", count = 2),
            // Not a file
            Allowed("ui/library/UpdateErrorsScreen.kt", "model.delete()", "not a file: the screen model deletes the selected update errors from the database"),
        )
    }
}

/** Finds the file changes [FileMutationScanTest] forbids; returns their 1-based lines. */
internal object FileMutationScanner {
    private val CODE = listOf(
        Regex("""\.deleteRecursively\s*\("""),
        Regex("""\bsetPosixFilePermissions\s*\("""),
        Regex("""\.set(?:Readable|Writable|Executable)\s*\("""),
        Regex("""\bFiles\.setAttribute\s*\("""),
        Regex("""\bFiles\.setOwner\s*\("""),
        Regex("""(?<![\w$])(?:f?setxattr|f?removexattr)\s*\("""),
        Regex("""\bUserDefinedFileAttributeView\b"""),
    )
    private val TOOL = Regex(""""(?:/usr/bin/|/bin/|/usr/sbin/)?(?:xattr|tmutil|mdutil|chflags|chmod|chown|attrib|icacls)(?:\.exe)?"""")
    private val WALK = Regex("""\bFiles\.walk(?:FileTree)?\s*\(|\.walk(?:TopDown|BottomUp)?\s*\(\s*\)""")
    private val DELETE = Regex("""\bdelete\w*\s*\(""")
    private val FUN_DECLARATION = Regex("""\bfun\s+\w*(?:set|remove)xattr\s*\(""")

    /** One-file deletes whose target is the argument: `Files.delete(x)`, okio's `delete(x, mustExist = ...)`. */
    private val DELETE_ARGUMENT = Regex("""\bFiles\.delete(?:IfExists)?\s*\(\s*(\w+)?|\.delete\s*\(\s*(\w+)?[^()]*,\s*mustExist\b""")

    /** One-file deletes whose target is the receiver: `x.delete()`, `x.deleteIfExists()`, `x.deleteExisting()`, `x.deleteOnExit()`. */
    private val DELETE_RECEIVER = Regex("""\.(?:delete|deleteIfExists|deleteExisting|deleteOnExit)\s*\(\s*\)""")

    /** Calls whose result is a file: `File(...).delete()` is a file delete, `prefs.key().delete()` is not. */
    private val FILE_CALLS = setOf("File", "createTempFile", "resolve", "resolveSibling", "toFile", "toPath", "absoluteFile")

    /** A `val` made by `createTempFile` (java.io, java.nio or kotlin.io.path) in the same function. */
    private fun tempFileIn(body: String, name: String) =
        Regex("""\b(?:val|var)\s+${Regex.escape(name)}\s*(?::\s*[\w.?]+\s*)?=\s*(?:[\w.]+\.)?createTemp(?:File|Directory)?\s*\(""").containsMatchIn(body)

    /** Whether [name] is declared in [m] as a preference (a key in a PreferenceStore), not a file. */
    private fun isPreference(m: String, name: String) =
        Regex("""\b(?:val|var)\s+${Regex.escape(name)}\s*=\s*\w*(?:[Pp]reference\w*|store)\.\w+\(""").containsMatchIn(m)

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val hits = sortedSetOf<Int>()
        CODE.forEach { pattern ->
            pattern.findAll(m).forEach { match ->
                // A native binding's own declaration is not a call
                val lineStart = m.lastIndexOf('\n', match.range.first) + 1
                if (!FUN_DECLARATION.containsMatchIn(m.substring(lineStart, match.range.last + 1))) {
                    hits += source.lineOf(match.range.first)
                }
            }
        }
        TOOL.findAll(source.withoutComments).forEach { hits += source.lineOf(it.range.first) }
        WALK.findAll(m).forEach { walk ->
            val body = SourceScan.enclosingFunction(m, walk.range.first)?.let { m.substring(it, SourceScan.closing(m, it)) }
                ?: statement(m, walk.range.first)
            if (DELETE.containsMatchIn(body)) hits += source.lineOf(walk.range.first)
        }
        DELETE_ARGUMENT.findAll(m).forEach { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (!isTemp(m, match.range.first, name)) hits += source.lineOf(match.range.first)
        }
        DELETE_RECEIVER.findAll(m).forEach { match ->
            val receiver = receiver(m, match.range.first) ?: return@forEach
            if (receiver == TEMP) return@forEach
            if (!isTemp(m, match.range.first, receiver)) hits += source.lineOf(match.range.first)
        }
        return hits.toList()
    }

    private const val TEMP = "<temporary file>"

    private fun isTemp(m: String, offset: Int, name: String): Boolean {
        if (name.isEmpty()) return false
        val body = SourceScan.enclosingFunction(m, offset)?.let { m.substring(it, SourceScan.closing(m, it)) } ?: return false
        return tempFileIn(body, name)
    }

    /**
     * The name of the file a `.delete()` at [dot] is called on: the variable (`tmp`, `it`), or the
     * call that makes it (`File`); null when the receiver is not a file (a preference key).
     */
    private fun receiver(m: String, dot: Int): String? {
        var i = dot - 1
        while (i >= 0 && (m[i] == '?' || m[i] == '!' || m[i].isWhitespace())) i--
        if (i < 0) return null
        if (m[i] == ')') {
            val open = SourceScan.opening(m, i)
            if (open <= 0) return null
            val call = Regex("""(\w+)\s*$""").find(m.substring(0, open))?.groupValues?.get(1) ?: return ""
            return when (call) {
                // Made and deleted in one expression
                "createTempFile" -> TEMP
                in FILE_CALLS -> ""
                else -> null
            }
        }
        val name = Regex("""(\w+)$""").find(m.substring(0, i + 1))?.groupValues?.get(1) ?: return null
        return if (isPreference(m, name)) null else name
    }

    /** The line of [offset] and the lines after it that continue it, for an expression-body function. */
    private fun statement(m: String, offset: Int): String {
        val start = m.lastIndexOf('\n', offset) + 1
        val end = m.indexOf('\n', offset).let { if (it < 0) m.length else it }
        return m.substring(start, end)
    }
}
