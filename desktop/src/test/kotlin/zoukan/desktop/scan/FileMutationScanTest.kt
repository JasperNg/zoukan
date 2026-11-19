package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * Recursive tree deletes, permission changes and extended attribute writes go through the helpers
 * that check the path is the app's own and never follow a symbolic link (`SafeDelete`,
 * `NoFollowDelete`, `CacheFolders`, `DataFolderPrivacy`). Fails on these anywhere else in the
 * app's own code:
 *
 * - `deleteRecursively()`, which follows a linked folder and deletes what it points to (okio's
 *   `deleteRecursively(path)` too);
 * - `Files.walk` / `walkFileTree` / `walk()` in a function that also deletes;
 * - permission changes: `setPosixFilePermissions`, `setReadable`, `setWritable`, `setExecutable`,
 *   `Files.setAttribute`, `setOwner`;
 * - extended attributes: `setxattr`, `removexattr`, `UserDefinedFileAttributeView`, and starting
 *   `xattr`, `tmutil`, `mdutil`, `chflags`, `chmod`, `chown`, `attrib` or `icacls`.
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
                fileSystem.deleteRecursively(path)
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
        ) shouldBe listOf(2, 3, 6, 9, 10, 11, 12, 13)
    }

    @Test
    fun `accepts a walk that only reads and mentions`() {
        FileMutationScanner.lines(
            """
            fun size(root: Path): Long = Files.walk(root).use { s -> s.mapToLong { Files.size(it) }.sum() }
            fun tidy() {
                // dir.deleteRecursively()
                log("deleteRecursively() and setxattr( and tmutil are only words here")
                ProcessBuilder("pmset", "-g", "ps").start()
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        const val HELPER = "a checked helper: resolves the path inside the app's own folder and never follows a link"
        val ALLOWED = listOf(
            Allowed("zoukan/desktop/CacheFolders.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/DataFolderPrivacy.kt", "", "the privacy-mark helper: sets owner-only permissions and the Time Machine attribute on the app's own folders, without following links (XATTR_NOFOLLOW, isSymbolicLink)", count = null),
            Allowed("zoukan/desktop/secrets/FileSecretStore.kt", "setPosixFilePermissions", "makes the secret file it has just written owner-only, by a name it checked", function = "restrict"),
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
