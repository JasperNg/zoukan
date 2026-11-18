package zoukan.desktop.scan

import zoukan.desktop.ui.InlineDialogScanner
import java.io.File

/**
 * Shared parts of the source-scan rules in this package: finding the sources, blanking comments
 * and strings, matching braces, and the allow-list, in which every entry needs a reason and must
 * match exactly the sites it names (so a new site in an allowed file fails too).
 *
 * This is text scanning, not a Kotlin or Java parser: it only needs braces, parentheses, strings
 * and comments right ([InlineDialogScanner.mask]).
 */
internal object SourceScan {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** One source file: its [path] from the repository root and its [text]. */
    class Source(val path: String, val text: String) {
        /** [text] with comments and string contents turned into spaces; offsets and lines kept. */
        val masked: String by lazy { InlineDialogScanner.mask(text) }

        /** [text] with only the comments blanked, so string literals (process arguments) stay. */
        val withoutComments: String by lazy { blankComments(text) }

        val lines: List<String> by lazy { text.lines() }

        private val lineStarts: IntArray by lazy {
            val starts = mutableListOf(0)
            text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
            starts.toIntArray()
        }

        /** The 1-based line of [offset]. */
        fun lineOf(offset: Int): Int {
            val i = lineStarts.binarySearch(offset)
            return if (i >= 0) i + 1 else -i - 1
        }

        /**
         * Whether 1-based [line] is Zoukan's own code. In `android-compat`, which is vendored, only
         * the lines inside Zoukan's markers (see `VendoredFilesMarkedTest`) are; elsewhere every line is.
         */
        fun isOwn(line: Int): Boolean = !path.startsWith("android-compat/") || ownLines[line - 1]

        private val ownLines: BooleanArray by lazy { zoukanLines(lines) }

        /**
         * [masked] for reading the code's structure (braces): a backticked name is masked as a run of
         * `x` first, since the masking reads the apostrophe in `the library's list` as a char literal.
         * Same length, so offsets stay.
         */
        private val structure: String by lazy {
            InlineDialogScanner.mask(BACKTICKED.replace(text) { "`" + "x".repeat(it.value.length - 2) + "`" })
        }

        /** The name of the innermost function or method around 1-based [line], or null outside one. */
        fun functionAt(line: Int): String? {
            val start = lineStarts[line - 1]
            val offset = start + lines[line - 1].indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val brace = enclosingFunction(structure, offset) ?: return null
            // The header's offsets are those of the text, where the name is not masked
            val header = header(structure, brace)
            val name = text.substring(brace - header.length, brace)
            val kotlin = KOTLIN_NAME.findAll(name).lastOrNull()
            return (kotlin ?: JAVA_NAME.find(name.trimEnd()))?.groupValues?.get(1)
        }
    }

    private val BACKTICKED = Regex("`[^`\n]*`")

    /** A Kotlin `fun name(`, with an extension's receiver, type parameters or a backticked name. */
    private val KOTLIN_NAME = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(""")

    /** A Java method's `name(`, the last call-like name before its parameter list ends the header. */
    private val JAVA_NAME = Regex("""(?<![\w.])(\w+)\s*\((?=[^()]*\)\s*(?:throws\s+[\w.,\s]+)?$)""")

    /** The `.kt` and `.java` files under [dirs] (relative to the repository root). */
    fun sources(dirs: List<String>): List<Source> {
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        return dirs.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .sortedBy { it.path }
            .map { Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
    }

    /**
     * A rule's site in the real code: [path], 1-based [line], the trimmed [code] on that line, and
     * the [function] (or method) it is in, null outside one.
     */
    data class Finding(val path: String, val line: Int, val code: String, val function: String? = null) {
        override fun toString() = "$path:$line: ${function?.let { "in $it: " }.orEmpty()}$code"
    }

    /**
     * An allowed site: a file whose path ends with [file], on a line containing [part] (an empty
     * [part] matches every line), in the function named [function] when it is given (the name stays
     * when lines move). The entry must match exactly [count] sites: one more is a new site that needs
     * its own entry, one fewer means a site was fixed. [count] null (any number) is only for a checked
     * helper's own file, where the rule's calls belong. [reason] says why; it may not be blank.
     */
    data class Allowed(
        val file: String,
        val part: String,
        val reason: String,
        val function: String? = null,
        val count: Int? = 1,
    ) {
        fun matches(f: Finding) = f.path.endsWith(file) && part in f.code && (function == null || function == f.function)
    }

    fun finding(source: Source, line: Int) = Finding(source.path, line, source.lines[line - 1].trim(), source.functionAt(line))

    /**
     * What is wrong with [findings] against [allowed]: sites no entry allows, entries without a
     * reason, entries that match no site any more (remove them; the site was fixed or moved), and
     * entries that match more or fewer sites than their count (a new site in an allowed file, or a
     * fixed one).
     */
    fun problems(findings: List<Finding>, allowed: List<Allowed>): List<String> {
        val unreasoned = allowed.filter { it.reason.isBlank() }.map { "Allow-list entry without a reason: $it" }
        val stale = allowed.filter { entry -> findings.none { entry.matches(it) } }
            .map { "Allow-list entry that matches nothing (remove it): $it" }
        val miscounted = allowed.mapNotNull { entry ->
            val sites = findings.filter { entry.matches(it) }
            if (sites.isEmpty() || entry.count == null || sites.size == entry.count) return@mapNotNull null
            val what = if (sites.size > entry.count) "a new site needs its own entry and reason" else "a site was fixed: lower the count"
            "Allow-list entry allows ${entry.count} site(s) but ${sites.size} match ($what): $entry\n  " +
                sites.joinToString("\n  ")
        }
        val unallowed = findings.filter { f -> allowed.none { it.matches(f) } }.map { it.toString() }
        return unreasoned + stale + miscounted + unallowed
    }

    /** Fails with [advice] and the [problems] when there are any. */
    fun verify(findings: List<Finding>, allowed: List<Allowed>, advice: String) {
        val problems = problems(findings, allowed)
        check(problems.isEmpty()) { "$advice\n" + problems.joinToString("\n") }
    }

    /** The index of the bracket that closes the one at [open] in masked text, or the text's end. */
    fun closing(masked: String, open: Int): Int {
        val o = masked[open]
        val c = when (o) {
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            else -> error("Not an opening bracket: $o")
        }
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                o -> depth++
                c -> if (--depth == 0) return i
            }
        }
        return masked.length - 1
    }

    /** The index of the bracket that opens the one at [close] in masked text, or -1. */
    fun opening(masked: String, close: Int): Int {
        val c = masked[close]
        val o = when (c) {
            ')' -> '('
            '}' -> '{'
            ']' -> '['
            else -> error("Not a closing bracket: $c")
        }
        var depth = 0
        for (i in close downTo 0) {
            when (masked[i]) {
                c -> depth++
                o -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** The `{` of every block that contains [offset], innermost first. */
    fun enclosingBraces(masked: String, offset: Int): List<Int> {
        val open = ArrayDeque<Int>()
        for (i in 0 until minOf(offset, masked.length)) {
            when (masked[i]) {
                '{' -> open.addLast(i)
                '}' -> open.removeLastOrNull()
            }
        }
        return open.reversed()
    }

    /**
     * The code before the `{` at [brace], back to the previous `{`, `}` or `;` outside parentheses
     * (so a Java `for (;;)` stays whole): what opens the block.
     */
    fun header(masked: String, brace: Int): String {
        var i = brace - 1
        while (i >= 0 && masked[i] != '{' && masked[i] != '}' && masked[i] != ';') {
            i = if (masked[i] == ')') opening(masked, i) - 1 else i - 1
            if (i < -1) i = -1
        }
        return masked.substring(i + 1, brace)
    }

    private val KOTLIN_FUN = Regex("""\bfun\b""")
    private val JAVA_METHOD = Regex(
        """\b(?:public|private|protected|static|final|synchronized|void)\b[^=]*\)\s*(?:throws\s+[\w.,\s]+)?$""",
    )
    private val CONTROL = Regex("""\b(?:if|for|while|switch|catch|try|else|do|synchronized)\b""")

    /** Whether the block opened by [header] is a function or method body. */
    fun isFunctionHeader(header: String): Boolean =
        KOTLIN_FUN.containsMatchIn(header) ||
            (JAVA_METHOD.containsMatchIn(header.trim()) && !CONTROL.containsMatchIn(header.substringBefore('(')))

    /** The `{` of the function or method body around [offset], or null at the top level. */
    fun enclosingFunction(masked: String, offset: Int): Int? =
        enclosingBraces(masked, offset).firstOrNull { isFunctionHeader(header(masked, it)) }

    private fun blankComments(text: String): String {
        val masked = InlineDialogScanner.mask(text)
        // A comment is code that mask blanked outside any string: blank the same span in the text
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            if (masked[i] == ' ' && text[i] != ' ' && (text.startsWith("//", i) || text.startsWith("/*", i))) {
                val end = if (text.startsWith("//", i)) {
                    text.indexOf('\n', i).let { if (it < 0) text.length else it }
                } else {
                    text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                }
                // Only a real comment: its whole span is blank in the masked text
                if ((i until end).all { masked[it] == ' ' || masked[it] == '\n' }) {
                    for (k in i until end) if (out[k] != '\n') out[k] = ' '
                    i = end
                    continue
                }
            }
            i++
        }
        return out.toString()
    }

    private val OPENER = Regex("""^\s*// ZOUKAN -->\s*$""")
    private val CLOSER = Regex("""^\s*// ZOUKAN <--\s*$""")
    private val WHOLE = Regex("""^\s*// ZOUKAN: whole file -->\s*$""")
    private val INLINE = Regex("""/\* ZOUKAN --> \*/""")

    /** For each line, whether it is inside Zoukan's markers (the conventions `VendoredFilesMarkedTest` checks). */
    internal fun zoukanLines(lines: List<String>): BooleanArray {
        if (lines.any { WHOLE.matches(it) }) return BooleanArray(lines.size) { true }
        var inside = false
        return BooleanArray(lines.size) { i ->
            val line = lines[i]
            when {
                OPENER.matches(line) -> {
                    inside = true
                    true
                }
                CLOSER.matches(line) -> {
                    inside = false
                    true
                }
                else -> inside || INLINE.containsMatchIn(line)
            }
        }
    }
}
