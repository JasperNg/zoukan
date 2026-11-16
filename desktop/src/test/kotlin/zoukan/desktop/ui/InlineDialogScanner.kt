package zoukan.desktop.ui

/**
 * Finds the `AlertDialog(` and `Dialog(` calls in a Kotlin source file and the function each one
 * is in, for the coverage matrix's inline-dialog baseline.
 *
 * A call belongs to the innermost `fun` whose body is still open at the call's line, found by
 * counting `{` and `}` outside string literals and comments. A call on the line that declares a
 * `fun` belongs to that fun, also when the fun has an expression body. This is not a Kotlin
 * parser: it only needs braces, parentheses, strings and comments right.
 */
internal object InlineDialogScanner {
    /** A dialog call: its 1-based [line], and the [function] it is in, or null outside any function. */
    data class Site(val line: Int, val function: String?)

    private val DIALOG_CALL = Regex("""\b(AlertDialog|Dialog)\(""")
    private val FUN = Regex(
        """^\s*(?:(?:@\w+|private|internal|public|protected|inline|override|suspend|operator|infix|tailrec)\s+)*""" +
            """fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""",
    )

    /** One `fun` seen so far: declared at [depth] braces and [parens] parentheses, [open] once its body's `{` came. */
    private class Frame(val name: String, val line: Int, val depth: Int, val parens: Int, var open: Boolean = false)

    fun sites(source: String): List<Site> {
        val sites = mutableListOf<Site>()
        val frames = mutableListOf<Frame>()
        var depth = 0
        var parens = 0
        mask(source).lines().forEachIndexed { index, line ->
            FUN.find(line)?.let { declaration ->
                // A fun without a block body (abstract, or `= expression`) ends before the next one starts
                frames.removeAll { !it.open }
                frames += Frame(declaration.groupValues[1], index, depth, parens)
            }
            if (DIALOG_CALL.containsMatchIn(line)) {
                val function = frames.lastOrNull { it.line == index } ?: frames.lastOrNull { it.open }
                sites += Site(index + 1, function?.name)
            }
            line.forEach { c ->
                when (c) {
                    '(' -> parens++
                    ')' -> parens--
                    '{' -> {
                        val last = frames.lastOrNull()
                        // The body's brace, not one in a default argument such as `onClick: () -> Unit = {}`
                        if (last != null && !last.open && depth == last.depth && parens == last.parens) last.open = true
                        depth++
                    }
                    '}' -> {
                        depth--
                        frames.removeAll { (it.open && depth <= it.depth) || depth < it.depth }
                    }
                }
            }
        }
        return sites
    }

    /**
     * [source] with comments and the text of string and char literals turned into spaces, line
     * breaks kept, so braces and calls inside them do not count. The code in a `${...}` template
     * stays.
     */
    internal fun mask(source: String): String {
        val out = StringBuilder(source)
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, source.length)) if (out[k] != '\n') out[k] = ' '
        }

        // Innermost first: PLAIN or RAW for a string, or a template's open-brace count (>= 0)
        val stack = ArrayDeque<Int>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val top = stack.lastOrNull()
            if (top == null || top >= 0) {
                when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        blank(i, end)
                        i = end
                    }
                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                        blank(i, end)
                        i = end
                    }
                    source.startsWith("\"\"\"", i) -> {
                        stack.addLast(RAW)
                        blank(i, i + 3)
                        i += 3
                    }
                    c == '"' -> {
                        stack.addLast(PLAIN)
                        blank(i, i + 1)
                        i++
                    }
                    c == '\'' -> {
                        // A char literal: 'x', '\n', 'A' or '\''
                        val from = if (source.getOrNull(i + 1) == '\\') i + 3 else i + 2
                        val end = source.indexOf('\'', from).let { if (it < 0) source.length else it + 1 }
                        blank(i, end)
                        i = end
                    }
                    c == '{' && top != null -> {
                        stack[stack.lastIndex] = top + 1
                        i++
                    }
                    c == '}' && top != null -> {
                        if (top == 0) {
                            // The end of a ${...} template: back in the string
                            stack.removeLast()
                            blank(i, i + 1)
                        } else {
                            stack[stack.lastIndex] = top - 1
                        }
                        i++
                    }
                    else -> i++
                }
            } else {
                val raw = top == RAW
                when {
                    !raw && c == '\\' -> {
                        blank(i, i + 2)
                        i += 2
                    }
                    raw && source.startsWith("\"\"\"", i) -> {
                        // A raw string may end with more quotes: the last three close it
                        var end = i + 3
                        while (end < source.length && source[end] == '"') end++
                        blank(i, end)
                        stack.removeLast()
                        i = end
                    }
                    !raw && c == '"' -> {
                        blank(i, i + 1)
                        stack.removeLast()
                        i++
                    }
                    c == '$' && source.getOrNull(i + 1) == '{' -> {
                        blank(i, i + 2)
                        stack.addLast(0)
                        i += 2
                    }
                    else -> {
                        blank(i, i + 1)
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    private const val PLAIN = -1
    private const val RAW = -2
}
