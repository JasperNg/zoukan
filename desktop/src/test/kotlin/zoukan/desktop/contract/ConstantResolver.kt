package zoukan.desktop.contract

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
