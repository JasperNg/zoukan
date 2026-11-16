package zoukan.desktop.ui

/** One entry of coverage-matrix.yaml: a feature key and the tests that cover it. */
data class CoverageRow(
    val key: String,
    val feature: String,
    val tier: String,
    val smoke: Boolean,
    val tests: List<String>,
    val gap: String?,
    /** The line of the entry's `- key:` in the file, for error messages. */
    val line: Int,
)

/**
 * Reads `coverage-matrix.yaml`. The file uses a small YAML subset, parsed here line by line, so
 * the test needs no YAML library. Anything outside the subset fails, so the file cannot drift into
 * YAML features nobody reads.
 */
object CoverageMatrix {
    const val RESOURCE = "/coverage-matrix.yaml"
    val TIERS = setOf("unit", "smoke", "journey", "nightly", "manual")

    private val FIELDS = setOf("key", "feature", "tier", "smoke", "tests", "gap")
    private val REQUIRED = FIELDS - "gap"
    private val ENTRY = Regex("""^- key: (.*)$""")
    private val FIELD = Regex("""^ {2}([a-z]+): (.*)$""")
    private val NO_VALUE = setOf("null", "~", "")
    private val BOOLEANS = setOf("true", "false", "yes", "no", "on", "off")

    /** A gap shorter than this cannot say why nothing covers the key. */
    private const val MIN_GAP = 10

    fun load(): List<CoverageRow> = parse(
        requireNotNull(CoverageMatrix::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is not on the test classpath" }
            .reader().use { it.readText() },
    )

    /** The subset the matrix uses: "- key: v" starts an entry, "  field: v" lines follow, tests is "[a, b]". */
    fun parse(text: String): List<CoverageRow> {
        val rows = mutableListOf<CoverageRow>()
        var fields: MutableMap<String, String>? = null
        var start = 0
        fun finish() {
            val entry = fields ?: return
            val missing = REQUIRED - entry.keys
            require(missing.isEmpty()) { "line $start: the entry has no ${missing.sorted().joinToString()}" }
            rows += CoverageRow(
                key = entry.getValue("key"),
                feature = entry.getValue("feature"),
                tier = entry.getValue("tier"),
                smoke = entry.getValue("smoke") == "true",
                tests = list(entry.getValue("tests"), start),
                gap = entry["gap"],
                line = start,
            )
        }
        text.lines().forEachIndexed { index, raw ->
            val n = index + 1
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            val entry = ENTRY.matchEntire(line)
            if (entry != null) {
                finish()
                start = n
                fields = mutableMapOf("key" to scalar(entry.groupValues[1], n))
                return@forEachIndexed
            }
            val field = FIELD.matchEntire(line)
                ?: throw IllegalArgumentException("line $n: expected \"- key: ...\" or \"  field: value\", got \"$line\"")
            val current = fields ?: throw IllegalArgumentException("line $n: a field before the first \"- key:\"")
            val (name, value) = field.destructured
            require(name in FIELDS) { "line $n: unknown field \"$name\" (known: ${FIELDS.joinToString()})" }
            require(name !in current) { "line $n: \"$name\" is set twice in one entry" }
            current[name] = when (name) {
                "tests" -> value.trim().also { list(it, n) }
                "smoke" -> scalar(value, n).also { require(it == "true" || it == "false") { "line $n: smoke must be true or false" } }
                // YAML reads a plain null or ~ as no value; a gap says why in words
                "gap" -> scalar(value, n).also {
                    require(it.trim().lowercase() !in NO_VALUE && it.trim().length >= MIN_GAP) {
                        "line $n: gap \"$it\" must say why nothing covers the key, in $MIN_GAP characters or more"
                    }
                }
                // YAML reads these plain words as null or a boolean, not as text
                else -> scalar(value, n).also {
                    require(value.trim().startsWith("\"") || it.lowercase() !in NO_VALUE + BOOLEANS) {
                        "line $n: YAML reads a plain \"$it\" as null or a boolean; double-quote it"
                    }
                }
            }
        }
        finish()
        return rows
    }

    /** A plain or double-quoted value. A plain value must not hold what YAML would read differently. */
    private fun scalar(raw: String, n: Int): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "line $n: empty value" }
        if (!value.startsWith("\"")) {
            require(": " !in value && " #" !in value && !value.endsWith(":")) {
                "line $n: a value with \": \" or \" #\" must be double-quoted"
            }
            require(value.first() !in "[{&*!|>'%@`") { "line $n: a value starting with '${value.first()}' must be double-quoted" }
            return value
        }
        require(value.length >= 2 && value.endsWith("\"")) { "line $n: unterminated quoted value" }
        val out = StringBuilder()
        var i = 1
        while (i < value.length - 1) {
            val c = value[i]
            if (c == '\\') {
                require(i + 1 < value.length - 1) { "line $n: a lone \\ at the end of a quoted value" }
                when (val next = value[i + 1]) {
                    '"', '\\' -> out.append(next)
                    else -> throw IllegalArgumentException("line $n: unsupported escape \\$next")
                }
                i += 2
            } else {
                require(c != '"') { "line $n: an unescaped \" inside a quoted value" }
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** A flow list "[a, b]" or "[]". */
    private fun list(raw: String, n: Int): List<String> {
        require(raw.startsWith("[") && raw.endsWith("]")) { "line $n: tests must be a list like [a, b]" }
        val inner = raw.substring(1, raw.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { item ->
            val name = item.trim()
            require(name.isNotEmpty() && name.none { it in "\"'[]{}: " }) { "line $n: bad tests item \"$name\"" }
            name
        }
    }
}
