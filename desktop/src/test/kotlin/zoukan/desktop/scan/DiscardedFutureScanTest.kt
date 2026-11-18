package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * An exception thrown in a future's callback completes the returned future exceptionally; when
 * that future is dropped, the exception vanishes: no log, no crash report. Fails on a statement
 * whose value is dropped and whose last call is a `CompletableFuture` stage that runs a callback
 * (`thenAccept`, `thenApply`, `thenRun`, `thenCompose`, `whenComplete`..., their `Async` forms,
 * `supplyAsync`, `runAsync`), or an executor's `submit`.
 *
 * Accepted: a chain that ends in `exceptionally` or `handle`; a future that is kept (assigned,
 * returned, passed on, or joined with `get`/`join`); a callback whose whole body is wrapped in
 * `try`/`runCatching`, or only logs.
 */
class DiscardedFutureScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
        "core/common/src/commonMain",
        "core/common/src/desktopMain",
    )

    @Test
    fun `no dropped future hides an exception from its callback`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            DiscardedFutureScanner.lines(source.text).map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "End the chain with exceptionally/handle that logs, keep the future, or wrap the callback " +
                "in try/runCatching (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags dropped stages and submits whose callback can throw`() {
        DiscardedFutureScanner.lines(
            """
            fun a(future: CompletableFuture<String>, cb: (String) -> Unit) {
                future.thenAccept { cb(it) }
                future
                    .handle { r, _ -> r ?: "null" }
                    .thenAcceptAsync { cb(it) }
                CompletableFuture.runAsync { work() }
                executor.submit { work() }
                future.whenComplete { r, _ -> save(r) }
                future.thenAccept(cb)
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 5, 6, 7, 8, 9)
    }

    @Test
    fun `accepts handled, kept, joined, wrapped and logging-only chains`() {
        DiscardedFutureScanner.lines(
            """
            fun b(future: CompletableFuture<String>, cb: (String) -> Unit): CompletableFuture<Unit> {
                future.thenAccept { cb(it) }.exceptionally { e -> log(e); null }
                future.thenApply { it.length }.handle { n, e -> n ?: 0 }
                val kept = future.thenAccept { cb(it) }
                future.thenAccept { cb(it) }.join()
                future.thenAccept { runCatching { cb(it) }.onFailure { log(it) } }
                future.thenAccept {
                    try {
                        cb(it)
                    } catch (e: Exception) {
                        log(e)
                    }
                }
                future.whenComplete { res, ex ->
                    if (ex != null) {
                        Log.e(TAG, "failed", ex)
                    } else {
                        Log.v(TAG, "done: ${'$'}res")
                    }
                }
                future.whenComplete { _, ex -> if (ex != null) logcat(LogPriority.ERROR, ex) { "failed" } }
                progressWriter.submit(update)
                consume(future.thenAccept { cb(it) })
                return future.thenApply { }
            }
            fun c(future: CompletableFuture<String>) = future.thenAccept { println(it) }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        val ALLOWED = listOf(
            Allowed(
                "android-compat/src/main/java/xyz/nulldev/androidcompat/webkit/KcefHelper.kt",
                "{ cb(it) }",
                "known defect: an exception from the extension's evaluateJavascript callback vanishes with " +
                    "the dropped future, without a log (on Android it would reach the main thread)",
            ),
        )
    }
}

/** Finds the dropped futures [DiscardedFutureScanTest] forbids; returns their 1-based lines. */
internal object DiscardedFutureScanner {
    private val BASE = listOf(
        "thenAccept", "thenApply", "thenRun", "thenCompose", "thenCombine", "thenAcceptBoth", "runAfterBoth",
        "runAfterEither", "acceptEither", "applyToEither", "whenComplete",
    )
    private val RISKY = (BASE + BASE.map { it + "Async" } + listOf("supplyAsync", "runAsync")).toSet()
    private val EXECUTOR = Regex("""(?i)(?:executor|pool|workers|service)\w*\s*$""")
    private val NOT_A_STATEMENT = Regex("""^(?:val|var|return|throw|if|else|when|fun|class|object|interface|import|package|@|is\b|in\b|case\b)""")
    private val CONTINUES = setOf('=', '(', ',', '+', '-', '*', '/', '&', '|', '?', ':', '.', '[', '<', '>', '!')
    private val LOGGING = Regex("""^(?:Log\.\w+|logger\.\w+|log\.\w+|LOG\.\w+|logcat|println)\b""")
    private val NON_STATEMENT_BLOCK = Regex("""\b(?:fun|if|else|try|finally|for|while|do|init|class|object|interface|catch|when)\b|->\s*$""")

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val hits = sortedSetOf<Int>()
        var lineStart = 0
        while (lineStart < m.length) {
            val lineEnd = m.indexOf('\n', lineStart).let { if (it < 0) m.length else it }
            val first = (lineStart until lineEnd).firstOrNull { !m[it].isWhitespace() }
            if (first != null && isStatementStart(m, first)) {
                chainEnd(m, first)?.let { (stage, line) -> if (stage) hits += source.lineOf(line) }
            }
            lineStart = lineEnd + 1
        }
        return hits.toList()
    }

    private fun isStatementStart(m: String, at: Int): Boolean {
        val rest = m.substring(at, minOf(m.length, at + 12))
        if (NOT_A_STATEMENT.containsMatchIn(rest) || rest.startsWith("}") || rest.startsWith("?.") || rest.startsWith(".")) return false
        if (!(m[at].isLetter() || m[at] == '_')) return false
        var i = at - 1
        while (i >= 0 && m[i].isWhitespace()) i--
        if (i < 0) return true
        if (m[i] in CONTINUES) return false
        if (m.startsWith("->", i - 1)) return false
        return true
    }

    /**
     * Walks the call chain of the statement at [start]. Returns (whether its value is a dropped risky
     * future, the offset to report), or null when it is not a plain call chain.
     */
    private fun chainEnd(m: String, start: Int): Pair<Boolean, Int>? {
        var i = start
        var lastName: String? = null
        var lastAt = start
        var lastArgs = ""
        var lastLambda: String? = null
        var receiver = ""
        while (i < m.length) {
            val c = m[i]
            when {
                c.isLetterOrDigit() || c == '_' -> {
                    val name = Regex("""\w+""").matchAt(m, i)!!.value
                    receiver = m.substring(start, i)
                    lastName = name
                    lastAt = i
                    lastArgs = ""
                    lastLambda = null
                    i += name.length
                }
                c == '.' || c == '!' || (c == '?' && m.getOrNull(i + 1) == '.') -> i += if (c == '?') 2 else 1
                c == '(' -> {
                    val close = SourceScan.closing(m, i)
                    lastArgs = m.substring(i + 1, close)
                    i = close + 1
                }
                c == '{' -> {
                    val close = SourceScan.closing(m, i)
                    lastLambda = m.substring(i + 1, close)
                    i = close + 1
                }
                c == '[' -> i = SourceScan.closing(m, i) + 1
                c == ' ' || c == '\t' -> i++
                c == '\n' || c == '\r' -> {
                    // The chain goes on when the next line starts with a call
                    var j = i
                    while (j < m.length && m[j].isWhitespace()) j++
                    if (m.startsWith(".", j) || m.startsWith("?.", j)) i = j else break
                }
                c == ';' || c == '}' -> break
                else -> return null
            }
        }
        val name = lastName ?: return null
        // The last expression of a lambda is its value, not a dropped one
        if (lastExpressionOfLambda(m, i)) return null
        val risky = when {
            name in RISKY -> !safe(lastLambda ?: lastArgs.substringAfter('{', "").substringBeforeLast('}', ""))
            name == "submit" -> EXECUTOR.containsMatchIn(receiver.trimEnd('.', '?', ' ')) &&
                !safe(lastLambda ?: lastArgs.substringAfter('{', "").substringBeforeLast('}', ""))
            else -> false
        }
        return risky to lastAt
    }

    private fun lastExpressionOfLambda(m: String, end: Int): Boolean {
        var j = end
        while (j < m.length && (m[j].isWhitespace() || m[j] == ';')) j++
        if (j >= m.length || m[j] != '}') return false
        val open = SourceScan.opening(m, j)
        if (open < 0) return false
        return !NON_STATEMENT_BLOCK.containsMatchIn(SourceScan.header(m, open))
    }

    /** Whether a callback [body] cannot throw out: wrapped whole in try/runCatching, or only logging. */
    private fun safe(body: String): Boolean {
        val code = body.trim().replace(Regex("""^[\w\s,]*->"""), "").trim()
        if (code.isEmpty()) return false
        if (code.startsWith("runCatching") || code.startsWith("try")) {
            // Nothing after the wrapped block but its handlers
            val open = code.indexOf('{')
            if (open < 0) return false
            val close = SourceScan.closing(code, open)
            val after = code.substring(close + 1).trim()
            return after.isEmpty() || after.startsWith(".") || after.startsWith("catch") || after.startsWith("finally")
        }
        // Only logging, possibly under if/else
        val statements = code.replace(Regex("""\b(?:if|while)\s*\("""), "\u0000(").let { s ->
            // Drop the conditions: each `\u0000(` up to its matching `)`
            val out = StringBuilder()
            var k = 0
            while (k < s.length) {
                if (s[k] == '\u0000') {
                    k = SourceScan.closing(s, k + 1) + 1
                    out.append('\n')
                } else {
                    out.append(s[k])
                    k++
                }
            }
            out.toString()
        }.replace(Regex("""\belse\b"""), "\n").replace(Regex("""[{}]"""), "\n")
        val parts = statements.lines().map { it.trim() }.filter { it.isNotEmpty() && it != ")" }
        return parts.isNotEmpty() && parts.all { LOGGING.containsMatchIn(it) || it.startsWith("\"") || it.startsWith(")") }
    }
}
