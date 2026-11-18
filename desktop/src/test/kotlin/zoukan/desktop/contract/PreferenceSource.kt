package zoukan.desktop.contract

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

    private val CONSTANT = Regex("""const\s+val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"$\\]*)"""")

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
