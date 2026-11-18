package zoukan.desktop.browser.blocking

/**
 * Reads the host names out of a block list. Two formats are understood, line by line:
 *
 * - a hosts file: `0.0.0.0 ads.example` (or 127.0.0.1, ::, ::1), or a bare `ads.example`;
 * - a filter list's whole-host rules: `||ads.example^`, optionally with `$third-party`.
 *
 * A whole-host exception, `@@||host^` (optionally with `$third-party`), names a host that is never
 * blocked (see [parseRules]). Everything else (comments, element hiding, rules with paths,
 * wildcards or other options) is skipped: only whole hosts are blocked or excepted. Malformed lines
 * never fail the list.
 */
object BlockListParser {
    /** The most hosts one list contributes; the rest are ignored. */
    const val MAX_HOSTS = 200_000

    private val HOST = Regex("""^(?=.{1,253}$)(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z][a-z0-9-]{0,62}$""")
    private val LOCAL_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::0", "::1", "255.255.255.255")
    private val HARMLESS_OPTIONS = setOf("third-party", "3p")

    /** The hosts and exception hosts of one list, each in order and without repeats. */
    data class Rules(val hosts: List<String>, val exceptions: List<String>) {
        fun isEmpty(): Boolean = hosts.isEmpty() && exceptions.isEmpty()
    }

    /** The valid hosts in [text], at most [limit], in order and without repeats. */
    fun parse(text: String, limit: Int = MAX_HOSTS): List<String> = parseRules(text, limit).hosts

    /**
     * The blocked hosts and the exception hosts (`@@||host^`) in [text], at most [limit] of the two
     * together, each in order and without repeats.
     */
    fun parseRules(text: String, limit: Int = MAX_HOSTS): Rules {
        val hosts = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            if (hosts.size + exceptions.size >= limit) break
            val line = rawLine.trim()
            if (line.startsWith("@@")) {
                filterRuleHost(line.substring(2))?.let { exceptions += it }
            } else {
                hostsOf(line).forEach { if (hosts.size + exceptions.size < limit) hosts += it }
            }
        }
        return Rules(hosts.toList(), exceptions.toList())
    }

    internal fun hostsOf(rawLine: String): List<String> {
        val line = rawLine.trim()
        if (line.isEmpty()) return emptyList()
        return when (line[0]) {
            '|' -> listOfNotNull(filterRuleHost(line))
            '!', '[', '#', '@' -> emptyList()
            else -> hostsLine(line)
        }
    }

    /** `||host^` or `||host^$third-party` (without a leading `@@`); null for any other rule. */
    private fun filterRuleHost(line: String): String? {
        if (!line.startsWith("||")) return null
        val body = line.substring(2)
        val caret = body.indexOf('^')
        if (caret <= 0) return null
        val rest = body.substring(caret + 1).removePrefix("|")
        if (rest.isNotEmpty()) {
            if (!rest.startsWith("$")) return null
            val options = rest.substring(1).split(',').map { it.trim().lowercase() }
            if (!options.all { it in HARMLESS_OPTIONS }) return null
        }
        return validHost(body.substring(0, caret))
    }

    private fun hostsLine(line: String): List<String> {
        // A comment starts with # after a space; a # inside a word (element hiding: site##.ad) is
        // not one, and leaves an invalid host
        val tokens = line.replace(TRAILING_COMMENT, "").trim().split(WHITESPACE).filter { it.isNotEmpty() }
        return when {
            tokens.isEmpty() -> emptyList()
            tokens.size == 1 -> listOfNotNull(validHost(tokens[0]))
            tokens[0] in LOCAL_ADDRESSES -> tokens.drop(1).mapNotNull(::validHost)
            else -> emptyList()
        }
    }

    /** [candidate] as a lowercase host name, or null when it is not one (an address, a single label). */
    internal fun validHost(candidate: String): String? {
        val host = candidate.trim().trimEnd('.').lowercase()
        return host.takeIf { HOST.matches(it) }
    }

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_COMMENT = Regex("\\s#.*$")
}
