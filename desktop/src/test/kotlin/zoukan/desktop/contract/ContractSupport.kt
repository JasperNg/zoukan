package zoukan.desktop.contract

import java.io.File

/**
 * The repository's own source files, for the tests that check the desktop code against the
 * Android sources and the contract documents (`desktop/PARITY.md`, `desktop/THREAT-MODEL.md`).
 * Gradle runs the desktop tests with the desktop folder as working directory, so the repository
 * root is its parent (as `AppSharedRewritesTest` and `ProjectOutputs` assume).
 */
internal object RepositorySources {

    val root: File by lazy {
        val dir = File(System.getProperty("user.dir")).absoluteFile.parentFile
        check(File(dir, "settings.gradle.kts").isFile && File(dir, "desktop/PARITY.md").isFile) {
            "The repository root was expected at $dir (the parent of the test's working directory)"
        }
        dir
    }

    /** Folders never walked: build output, VCS and IDE state. */
    private val SKIPPED = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".kotlin")

    /** Every file under the root, as a path relative to it with `/` separators, build output left out. */
    val allPaths: List<String> by lazy {
        root.walkTopDown()
            .onEnter { it == root || it.name !in SKIPPED }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }

    /** Every folder under the root, as [allPaths] gives files. */
    val allDirs: Set<String> by lazy {
        allPaths.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
        }.toSet()
    }

    fun text(path: String): String = File(root, path).readText()

    /** The Kotlin files under [dir] (relative to the root), build output left out. */
    fun kotlinFiles(dir: String): List<String> = allPaths.filter { it.startsWith("$dir/") && it.endsWith(".kt") }

    /** Main (not test) source files of every module: Kotlin, Java and SQLDelight. */
    val mainSourcePaths: List<String> by lazy {
        allPaths.filter { path ->
            (path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".sq") || path.endsWith(".sqm")) &&
                "/src/" in path && !isTestSource(path)
        }
    }

    /** Test source files of every module (src/test, src/commonTest, src/desktopTest, ...). */
    fun isTestSource(path: String): Boolean = Regex("""/src/[A-Za-z]*[tT]est[A-Za-z]*/""").containsMatchIn(path)
}

/** Fails listing every problem, one per line, so one run shows all of them. */
internal fun assertNoProblems(what: String, problems: List<String>) {
    if (problems.isNotEmpty()) {
        throw AssertionError("$what (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" })
    }
}

/**
 * Gives the value of a constant used as a default: `NAME` or `Owner.NAME` (also
 * `Outer.Owner.NAME`) where `const val NAME = <literal>` is declared in [files], inside the
 * braces of `object Owner` / `class Owner` when an owner is named. [special] holds expressions
 * that need more than a constant (each throws when the source it reads no longer looks as
 * expected). Returns null when the value cannot be found or is ambiguous, so the expression is
 * then compared as written.
 */
internal class ConstantResolver(
    private val files: Collection<String>,
    private val special: Map<String, () -> String> = emptyMap(),
) : (String) -> String? {

    private val cache = mutableMapOf<String, String?>()

    override fun invoke(expression: String): String? = cache.getOrPut(expression) { resolve(expression) }

    private fun resolve(expression: String): String? {
        special[expression]?.let { return it() }
        val m = Regex("""^(?:(?:\w+\.)*(\w+)\.)?([A-Z][A-Z0-9_]*)$""").find(expression) ?: return null
        val owner = m.groups[1]?.value
        val name = m.groupValues[2]
        val definition = Regex("""const\s+val\s+$name\s*(?::\s*\w+\s*)?=\s*(-?\d+[Lf]?|"[^"$\\]*"|true|false)(?!\w)""")
        val values = files.flatMap { text ->
            val scopes = if (owner == null) listOf(text) else bodiesOf(text, owner)
            scopes.flatMap { scope -> definition.findAll(scope).map { it.groupValues[1].removeSuffix("L") } }
        }.distinct()
        return values.singleOrNull()
    }

    companion object {
        /** The text between the braces of every `object|class|interface [owner]` in [text]. */
        fun bodiesOf(text: String, owner: String): List<String> =
            Regex("""\b(?:object|class|interface)\s+$owner\b""").findAll(text).mapNotNull { m ->
                val open = text.indexOf('{', m.range.last)
                if (open < 0) return@mapNotNull null
                var depth = 0
                for (i in open until text.length) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> if (--depth == 0) return@mapNotNull text.substring(open + 1, i)
                    }
                }
                null
            }.toList()

        /** The one match of [regex] in [text], group 1, or a failure naming [where]. */
        fun only(text: String, regex: Regex, where: String): String {
            val all = regex.findAll(text).toList()
            check(all.size == 1) { "$where: expected one match of /${regex.pattern}/, found ${all.size}; the source changed, update the test" }
            return all.single().groupValues[1]
        }

        /** A set of string values as a comparable text: `set(a, b)`, sorted. */
        fun setValue(values: Collection<String>) = "set(" + values.sorted().joinToString(", ") + ")"
    }
}

/** One preference read from source: `store.getInt("key", 0)`. [key] is `appState:x` / `private:x` for those prefixes. */
internal data class PreferenceDeclaration(val key: String, val type: String, val default: String, val location: String)

/**
 * Reads preference declarations (`<store>.getBoolean("key", default)` and the other `get...`
 * functions of `PreferenceStore`) from Kotlin source with regexes. The key may be a string
 * literal, `Preference.appStateKey("...")` / `Preference.privateKey("...")`, or a constant
 * (`const val NAME = "..."`) declared in the same file. Named arguments (`key = `,
 * `defaultValue = `) are accepted, and whole-line comments between the arguments are skipped.
 */
internal object PreferenceSource {

    private const val TYPES = "Boolean|Int|Long|Float|String|StringSet|Enum|LongArray|ObjectFromString|ObjectFromInt"

    private val CALL = Regex(
        """\.get($TYPES)\(\s*(?:key\s*=\s*)?""" +
            """(?:"([^"$\\]+)"|(?:[\w.]+\.)?Preference\.(appStateKey|privateKey)\(\s*"([^"$\\]+)"\s*\)|([A-Z][A-Z0-9_]*))""" +
            """\s*,\s*(?:defaultValue\s*=\s*)?""",
    )

    /** A call on something named like a preference store, whatever its first argument is. */
    private val STORE_CALL = Regex("""(?:[sS]tore|PreferenceStore>\(\))\s*\.get($TYPES)\(""")

    private val CONSTANT = Regex("""const\s+val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*\w+\s*)?=\s*"([^"$\\]*)"""")

    class Parsed(val declarations: List<PreferenceDeclaration>, val unparsedStoreCalls: List<String>)

    fun parse(source: String, location: String): Parsed {
        // Whole-line comments (`// KMK -->` between arguments) are blanked; line numbers stay
        val text = source.replace(Regex("""(?m)^[ \t]*//.*$"""), "")
        val constants = CONSTANT.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val declarations = mutableListOf<PreferenceDeclaration>()
        val parsedAt = mutableSetOf<Int>()
        for (m in CALL.findAll(text)) {
            val line = lineOf(text, m.range.first)
            val key = when {
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> (if (m.groupValues[3] == "appStateKey") "appState:" else "private:") + m.groupValues[4]
                else -> constants[m.groupValues[5]] ?: continue
            }
            parsedAt += line
            declarations += PreferenceDeclaration(key, m.groupValues[1], defaultAt(text, m.range.last + 1), "$location:$line")
        }
        val unparsed = STORE_CALL.findAll(text).map { lineOf(text, it.range.first) }.filter { it !in parsedAt }
            .map { "$location:$it" }.toList()
        return Parsed(declarations, unparsed)
    }

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    /** The argument starting at [start]: up to the next `,` or `)` outside brackets and strings, whitespace collapsed. */
    fun defaultAt(text: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) break else depth--
                ',' -> if (depth == 0) break
                else -> {}
            }
            i++
        }
        return text.substring(start, i).split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** How the value is stored, so a String read of an enum preference counts as the same type. */
    fun storedAs(type: String): String = when (type) {
        "Enum", "ObjectFromString" -> "String"
        "ObjectFromInt" -> "Int"
        else -> type
    }
}

/**
 * Turns a default expression into a comparable value. [resolve] gives the value of expressions
 * that need the code around them (a constant, an enum's field); anything else is compared as
 * written, after these rules: an enum default of `getEnum` is stored as its name, so
 * `TappingInvertMode.NONE` becomes `"NONE"`; `-1L` is `-1`; every empty collection is `empty`;
 * `java.util.` is dropped.
 */
internal fun canonicalDefault(type: String, expression: String, resolve: (String) -> String?): String {
    val e = expression.replace("java.util.", "")
    resolve(e)?.let { return it }
    if (type == "Enum") Regex("""^(?:[A-Za-z_][\w]*\.)+([A-Za-z_]\w*)$""").find(e)?.let { return "\"${it.groupValues[1]}\"" }
    Regex("""^(-?\d+)L$""").find(e)?.let { return it.groupValues[1] }
    if (e.matches(Regex("""^(emptySet|emptyList|setOf|listOf|mutableSetOf|mutableListOf)(<[\w.]+>)?\(\)$"""))) return "empty"
    return e
}

/** A preference that differs from Android on purpose or that is waiting for a decision. */
internal class KnownPreferenceDifference(val key: String, val reviewed: Boolean, val why: String)

/** Compares the desktop's preference declarations with the Android ones. Returns one line per problem. */
internal object PreferenceParity {

    /** A key the desktop declares that Android does not have must say so in its name. */
    const val DESKTOP_ONLY_PREFIX = "zoukan_"

    fun problems(
        desktop: List<PreferenceDeclaration>,
        android: List<PreferenceDeclaration>,
        known: List<KnownPreferenceDifference>,
        parityText: String,
        desktopResolve: (String) -> String?,
        androidResolve: (String) -> String?,
    ): List<String> {
        val byKey = android.groupBy { it.key }
        val knownKeys = known.associateBy { it.key }
        val excused = mutableSetOf<String>()
        val problems = mutableListOf<String>()
        fun excuse(key: String): Boolean {
            val bare = key.substringAfter(':')
            if (key in knownKeys || "`$bare`" in parityText) {
                excused += key
                return true
            }
            return false
        }
        for (d in desktop) {
            val bare = d.key.substringAfter(':')
            val matches = byKey[d.key]
            if (matches == null) {
                if (bare.startsWith(DESKTOP_ONLY_PREFIX) || excuse(d.key)) continue
                val otherPrefix = android.filter { it.key.substringAfter(':') == bare }.map { "${it.key} at ${it.location}" }
                problems += "${d.location}: key \"${d.key}\" is not an Android preference" +
                    (if (otherPrefix.isNotEmpty()) " (Android has it with another prefix: ${otherPrefix.joinToString()})" else "") +
                    "; use Android's key, or name a desktop-only key $DESKTOP_ONLY_PREFIX..."
                continue
            }
            if (matches.none { PreferenceSource.storedAs(it.type) == PreferenceSource.storedAs(d.type) }) {
                if (!excuse(d.key)) {
                    problems += "${d.location}: \"${d.key}\" is read as ${d.type}; Android stores it as " +
                        matches.joinToString { "${it.type} (${it.location})" }
                }
                continue
            }
            val ours = canonicalDefault(d.type, d.default, desktopResolve)
            val theirs = matches.map { canonicalDefault(it.type, it.default, androidResolve) to it }
            if (theirs.none { it.first == ours } && !excuse(d.key)) {
                problems += "${d.location}: \"${d.key}\" defaults to $ours (${d.default}); Android: " +
                    theirs.joinToString { (value, decl) -> "$value (${decl.default}) at ${decl.location}" }
            }
        }
        // A known difference that no longer differs, or names a key the desktop no longer reads, is stale
        val desktopKeys = desktop.map { it.key }.toSet()
        for (k in known) {
            if (k.key !in desktopKeys) {
                problems += "known difference \"${k.key}\" names a key the desktop no longer reads; remove it"
            } else if (k.key !in excused) {
                problems += "known difference \"${k.key}\" matches Android now; remove it"
            }
        }
        return problems
    }
}

/** A name in the storage folder: how to read Android's and the desktop's. */
internal class NamePair(
    val what: String,
    val android: () -> String,
    val desktop: () -> String,
    val known: KnownNameDifference? = null,
)

/** A name that differs from Android: the two values as they are now, and why. */
internal class KnownNameDifference(val desktop: String, val android: String, val reviewed: Boolean, val why: String)

internal object StorageNames {

    /** Group 1 of the one match of [regex] in [text], or in the repository file [path] when [text] is null. */
    fun from(path: String, text: String?, regex: Regex): () -> String = {
        ConstantResolver.only(text ?: RepositorySources.text(path), regex, path)
    }

    fun problems(pairs: List<NamePair>): List<String> = pairs.mapNotNull { pair ->
        val values = runCatching { pair.desktop() to pair.android() }
        val (desktop, android) = values.getOrElse { return@mapNotNull "${pair.what}: ${it.message}" }
        val known = pair.known
        when {
            known == null && desktop != android -> "${pair.what}: desktop \"$desktop\", Android \"$android\""
            known != null && desktop == android -> "${pair.what}: the recorded difference is stale, both are \"$desktop\"; remove it"
            known != null && (known.desktop != desktop || known.android != android) ->
                "${pair.what}: recorded as desktop \"${known.desktop}\", Android \"${known.android}\", but now desktop \"$desktop\", Android \"$android\""
            else -> null
        }
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

internal object ThreatModel {

    class Row(val boundary: String, val tests: List<String>, val gap: String)

    private fun section(text: String, heading: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == heading }
        check(start >= 0) { "THREAT-MODEL.md has no \"$heading\" heading" }
        val level = heading.takeWhile { it == '#' }.length
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val l = lines[i].trimStart()
            l.startsWith("#") && l.takeWhile { it == '#' }.length <= level
        } ?: lines.size
        return lines.subList(start + 1, end)
    }

    /** The boundary names: each list item of "## Trust boundaries" up to its colon, without a part in brackets. */
    fun boundaries(text: String): List<String> = section(text, "## Trust boundaries")
        .takeWhile { it.trim() != "### Checked by" }
        .filter { it.startsWith("- ") }
        .map { item -> item.removePrefix("- ").substringBefore(':').replace(Regex("""\s*\([^)]*\)"""), "").trim() }

    fun rows(text: String): List<Row> = section(text, "### Checked by")
        .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Boundary |") }
        .map { line ->
            val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            check(cells.size == 3) { "A Checked by row must have 3 cells: $line" }
            Row(cells[0], Regex("`([^`]+)`").findAll(cells[1]).map { it.groupValues[1] }.toList(), cells[2])
        }

    fun problems(boundaries: List<String>, rows: List<Row>, testProblem: (String) -> String?): List<String> {
        val problems = mutableListOf<String>()
        val byName = rows.groupBy { it.boundary }
        boundaries.filter { it !in byName }.forEach { problems += "boundary \"$it\" has no row in the Checked by table" }
        byName.filter { it.value.size > 1 }.keys.forEach { problems += "boundary \"$it\" has more than one row" }
        rows.filter { it.boundary !in boundaries }.forEach { problems += "row \"${it.boundary}\" names no boundary of the list" }
        for (row in rows) {
            if (row.tests.isEmpty() && row.gap.isBlank()) problems += "row \"${row.boundary}\" has neither tests nor a gap"
            row.tests.forEach { test -> testProblem(test)?.let { problems += "row \"${row.boundary}\": $it" } }
        }
        return problems
    }

    /**
     * What is wrong with a fully qualified test class name, or null when a test source declares the
     * class with an @Test: in a file of its own name, or else in one file of its package (several
     * small subclasses of one contract share a file). A class whose file has no @Test has tests when
     * it extends a class of the same package whose file has them (a contract's subclass).
     */
    fun testSourceProblem(name: String): String? {
        if (!Regex("""^[a-z][\w.]*\.[A-Z]\w*$""").matches(name)) return "\"$name\" is not a fully qualified class name"
        val simple = name.substringAfterLast('.')
        val file = declaringFile(name.substringBeforeLast('.'), simple)
            ?: return "$name has no test source (*/src/*Test*/kotlin/${name.replace('.', '/')}.kt, or a file of its package that declares it)"
        val text = RepositorySources.text(file)
        if ("@Test" in text) return null
        val parent = Regex("""\bclass\s+$simple\b[^{]*?:\s*(\w+)\s*\(""").find(text)?.groupValues?.get(1)
        val parentFile = parent?.let { declaringFile(name.substringBeforeLast('.'), it) }
        if (parentFile != null && "@Test" in RepositorySources.text(parentFile)) return null
        return "$name has no @Test"
    }

    /** The test source that declares class [simple] of package [pkg]: the file of that name, or the one file of the package that declares it. */
    private fun declaringFile(pkg: String, simple: String): String? {
        val dir = "/kotlin/" + pkg.replace('.', '/') + "/"
        val declares = Regex("""\bclass\s+$simple\b""")
        val inPackage = RepositorySources.allPaths.filter {
            RepositorySources.isTestSource(it) && it.endsWith(".kt") && it.substringBeforeLast('/').endsWith(dir.trimEnd('/'))
        }
        inPackage.firstOrNull { it.endsWith("$dir$simple.kt") }?.let { return it.takeIf { f -> declares.containsMatchIn(RepositorySources.text(f)) } }
        return inPackage.filter { declares.containsMatchIn(RepositorySources.text(it)) }.singleOrNull()
    }
}
