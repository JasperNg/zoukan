package zoukan.desktop.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Every file and symbol a row of `desktop/PARITY.md` names in backticks still exists, so a
 * difference cannot outlive the code it describes. Which backticked text counts as a reference
 * ([ParityReferences.classify]):
 *
 * - a path: text with a `/` whose first part is a top-level folder of the repository
 *   (`desktop/THREAT-MODEL.md`), or that ends in `.kt`/`.kts`. `desktop/.../download/X.kt`
 *   means `X.kt` somewhere under `desktop/`; a path that does not start at a top-level folder
 *   (`storage/StorageFolderPrivacy.kt`, `SafeDelete.kt`) must end some file's path.
 * - a symbol: a camelCase or PascalCase identifier (`planCleanup`, `MergedSource`) or a
 *   snake_case one (`crop_borders_continues_vertical`, `track_dupes`), which must appear as a
 *   whole word in some main source file (Kotlin, Java or SQLDelight; tests do not count).
 *
 * Anything else in backticks (`downloads/`, `0700`, `about:blank`, `src:local`, `http.agent`)
 * is a name the app shows or uses, not a reference, and is left alone.
 */
class ParityReferencesTest {

    @Test
    fun `every file and symbol PARITY md names still exists`() {
        val references = ParityReferences.references(RepositorySources.text("desktop/PARITY.md"))
        // Today's document has about 50; a parser that finds almost none is broken
        check(references.size >= 30) { "Only ${references.size} references found in PARITY.md; the parser is broken" }
        check(references.count { it.kind == ParityReferences.Kind.PATH } >= 10) { "Too few path references found in PARITY.md" }
        assertNoProblems("PARITY.md names things that no longer exist", ParityReferences.problems(references, ParityReferences.repositoryIndex()))
    }

    // --- Self-tests on text in memory ---

    private val index = ParityReferences.Index(
        paths = listOf("desktop/src/main/kotlin/zoukan/desktop/download/SafeDelete.kt", "desktop/PARITY.md"),
        dirs = setOf("desktop", "desktop/src/main/kotlin/zoukan/desktop/download"),
        words = setOf("planCleanup", "track_dupes", "MergedSource"),
    )

    private fun problemsOf(row: String) = ParityReferences.problems(ParityReferences.references(row), index)

    @Test
    fun `self-test - existing files and symbols pass`() {
        problemsOf("| A | Yes | Code: `desktop/.../download/SafeDelete.kt` (`planCleanup`), `download/SafeDelete.kt`, `SafeDelete.kt`, `MergedSource`, `track_dupes`, `desktop/PARITY.md` |")
            .shouldBeEmpty()
        problemsOf("| A | Yes | `downloads/`, `0700`, `src:local`, `http.agent`, `about:blank`, `--private`, `tar` |").shouldBeEmpty()
        ParityReferences.references("| A | Yes | `downloads/`, `0700`, `tar`, `java.net.useSystemProxies` |").shouldBeEmpty()
    }

    @Test
    fun `self-test - a removed file or symbol is caught`() {
        problemsOf("| Clean up | Yes | Code: `desktop/.../download/Gone.kt` |").single() shouldContain "desktop/.../download/Gone.kt"
        problemsOf("| Clean up | Yes | `planCleanupRenamed` |").single() shouldContain "planCleanupRenamed"
        problemsOf("| Clean up | Yes | `removed_pref_key` |").single() shouldContain "\"Clean up\""
        problemsOf("| Clean up | Yes | `desktop/.../browser/` |").single() shouldContain "desktop/.../browser/"
    }

    @Test
    fun `self-test - the header and separator rows are not entries`() {
        ParityReferences.references("| Android feature | Desktop | Notes |\n|---|---|---|").shouldBeEmpty()
        ParityReferences.classify("SafeDelete.kt") shouldBe ParityReferences.Kind.PATH
    }
}

internal object ParityReferences {

    enum class Kind { PATH, SYMBOL }

    class Reference(val row: String, val text: String, val kind: Kind)

    /** What exists: files and folders relative to the root, and every identifier in the main sources. */
    class Index(val paths: List<String>, val dirs: Set<String>, val words: Set<String>)

    private val TOP_LEVEL = setOf(
        "desktop", "app", "domain", "data", "core", "backup", "android-compat", "android-stubs", "extension-jvm",
        "source-api", "source-local", "presentation-core", "core-metadata", "i18n", "i18n-desktop", "buildSrc", "gradle", ".github",
    )
    private val CAMEL = Regex("""^[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*$""")
    private val PASCAL = Regex("""^[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*$""")
    private val SNAKE = Regex("""^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$""")

    fun classify(text: String): Kind? = when {
        text.endsWith(".kt") || text.endsWith(".kts") -> Kind.PATH
        '/' in text && text.substringBefore('/') in TOP_LEVEL -> Kind.PATH
        CAMEL.matches(text) || PASCAL.matches(text) || SNAKE.matches(text) -> Kind.SYMBOL
        else -> null
    }

    /** The backticked references of every table row except headers and separators. */
    fun references(markdown: String): List<Reference> = markdown.lines()
        .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Android feature") && !it.startsWith("| Feature |") }
        .flatMap { line ->
            val row = line.trim('|').substringBefore('|').trim()
            Regex("`([^`]+)`").findAll(line).mapNotNull { m ->
                val text = m.groupValues[1].trim()
                classify(text)?.let { Reference(row, text, it) }
            }.toList()
        }

    fun problems(references: List<Reference>, index: Index): List<String> = references.mapNotNull { ref ->
        val found = when (ref.kind) {
            Kind.SYMBOL -> ref.text in index.words
            Kind.PATH -> pathExists(ref.text, index)
        }
        if (found) null else "row \"${ref.row}\": `${ref.text}` " + (if (ref.kind == Kind.PATH) "matches no file or folder" else "is in no main source file")
    }

    private fun pathExists(text: String, index: Index): Boolean {
        val isDir = text.endsWith("/")
        val clean = text.removeSuffix("/")
        val candidates = if (isDir) index.dirs else index.paths
        return if ("/.../" in clean) {
            val prefix = clean.substringBefore("/.../")
            val suffix = clean.substringAfter("/.../")
            candidates.any { it.startsWith("$prefix/") && it.endsWith("/$suffix") }
        } else if (clean.substringBefore('/') in TOP_LEVEL) {
            clean in candidates
        } else {
            candidates.any { it == clean || it.endsWith("/$clean") }
        }
    }

    fun repositoryIndex(): Index {
        val words = HashSet<String>()
        val word = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        RepositorySources.mainSourcePaths.forEach { path -> word.findAll(RepositorySources.text(path)).forEach { words += it.value } }
        return Index(RepositorySources.allPaths, RepositorySources.allDirs, words)
    }
}
