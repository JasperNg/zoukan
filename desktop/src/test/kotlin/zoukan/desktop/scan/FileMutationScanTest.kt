package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * Deleting a folder tree, changing permissions and writing extended attributes go through the
 * helpers that check the path is the app's own and never follow a symbolic link (`SafeDelete`,
 * `AppTempFiles.clear`, `CacheFolders`, `BrowserProfileCleanup`, `DataFolderPrivacy`). Fails on
 * the dangerous forms anywhere else in the app's own code (`SafeDelete` deletes one path at a time,
 * so it needs no entry):
 *
 * - `deleteRecursively()`, which follows a linked folder and deletes what it points to;
 * - `Files.walk` / `walkFileTree` / `walk()` in a function that also deletes;
 * - permission changes: `setPosixFilePermissions`, `setReadable`, `setWritable`, `setExecutable`,
 *   `Files.setAttribute`, `setOwner`;
 * - extended attributes: `setxattr`, `removexattr`, `UserDefinedFileAttributeView`, and starting
 *   `xattr`, `tmutil`, `mdutil`, `chflags`, `chmod`, `chown`, `attrib` or `icacls`.
 *
 * A plain one-file `delete()` / `Files.delete` is left out: there are well over a hundred, almost
 * all of the app's own temporary files.
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
            """.trimIndent(),
        ) shouldBe listOf(2, 5, 8, 9, 10, 11, 12)
    }

    @Test
    fun `accepts one-file deletes, a walk that only reads, and mentions in comments and strings`() {
        FileMutationScanner.lines(
            """
            fun size(root: Path): Long = Files.walk(root).use { s -> s.mapToLong { Files.size(it) }.sum() }
            fun tidy(f: File) {
                f.delete()
                Files.deleteIfExists(f.toPath())
                // dir.deleteRecursively()
                log("deleteRecursively() and setxattr( and tmutil are only words here")
                ProcessBuilder("pmset", "-g", "ps").start()
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        const val HELPER = "a checked helper: resolves the path inside the app's own folder and never follows a link"
        const val OWN_TEMP = "a folder this code created moments before and filled with its own files only"
        const val DIAGNOSTIC = "a throwaway profile folder of a diagnostic run (self-test, backup check), created by it"
        val ALLOWED = listOf(
            // The helpers themselves
            Allowed("zoukan/desktop/CacheFolders.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/browser/BrowserProfileCleanup.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/DataFolderPrivacy.kt", "", "the privacy-mark helper: sets owner-only permissions and the Time Machine attribute on the app's own folders, without following links (XATTR_NOFOLLOW, isSymbolicLink)", count = null),
            Allowed("zoukan/desktop/secrets/FileSecretStore.kt", "setPosixFilePermissions", "makes the secret file it has just written owner-only, by a name it checked", function = "restrict"),
            // Folders the code made itself
            Allowed("ui/reader/ReaderScreenModel.kt", "dir.deleteRecursively()", OWN_TEMP, function = "setPageAsCover"),
            Allowed("update/Updates.kt", "folder.toFile().deleteRecursively()", OWN_TEMP, function = "reject"),
            Allowed("app/SelfTest.kt", "profile.deleteRecursively()", DIAGNOSTIC, function = "deleteProfile"),
            Allowed("backup/BackupCheck.kt", "profile.deleteRecursively()", DIAGNOSTIC, function = "run"),
            Allowed("browser/ChromiumNatives.kt", "installDir.deleteRecursively()", "the app's own Chromium folder, filled only from the pinned, checksummed download; the links in Chromium's bundle point inside it (a failed or outdated install is cleared, then the folder made again)", function = "ensureInstalled", count = 2),
            Allowed("zoukan/extension/DesktopExtensionInstaller.kt", "checkedPackageDir(extensionsDir, pkgName).deleteRecursively()", "checkedPackageDir resolves links and requires the folder to sit directly in the extensions folder, which holds only files the installer wrote", function = "uninstall"),
            // Suspected defects: should go through SafeDelete
        )
    }
}

/** Finds the file changes [FileMutationScanTest] forbids; returns their 1-based lines. */
internal object FileMutationScanner {
    private val CODE = listOf(
        Regex("""\.deleteRecursively\s*\(\s*\)"""),
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
        return hits.toList()
    }

    /** The line of [offset] and the lines after it that continue it, for an expression-body function. */
    private fun statement(m: String, offset: Int): String {
        val start = m.lastIndexOf('\n', offset) + 1
        val end = m.indexOf('\n', offset).let { if (it < 0) m.length else it }
        return m.substring(start, end)
    }
}
