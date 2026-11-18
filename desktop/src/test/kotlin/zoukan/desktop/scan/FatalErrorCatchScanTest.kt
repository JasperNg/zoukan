package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * A catch that keeps something running after an `Error` must still let the VM's own failures
 * through: an `OutOfMemoryError` swallowed by a loop leaves it running in a broken state, and the
 * crash report never sees it. Fails on:
 *
 * - every `catch (e: Error)` / `catch (Error e)`;
 * - a `catch (e: Throwable)` / `catch (Throwable t)` inside a loop (`while`, `for`, `do`, `repeat`,
 *   `forEach`...) in the same function, that swallows the failure;
 *
 * unless the handler rethrows unconditionally (`throw e`, or a wrapper), rethrows when the failure
 * is an `OutOfMemoryError`, a `VirtualMachineError` or not an `Exception` (the
 * `if (e !is Exception && e !is LinkageError) throw e` idiom), ends in `CrashReporter.fatal`, or an
 * earlier catch of the same `try` handles `OutOfMemoryError` or `VirtualMachineError` first.
 *
 * A `LinkageError` may be caught: an extension built against another API fails with one, and the
 * app shows it as an outdated extension and keeps running (`SourceErrors.kt`, the main looper).
 *
 * A one-off `catch (e: Throwable)` outside a loop is left out on purpose: the app catches Throwable
 * around single user actions so that an extension's Error (out of memory on a huge cover included)
 * becomes a message instead of a crash, and the action ends there.
 */
class FatalErrorCatchScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    @Test
    fun `no catch keeps running after an OutOfMemoryError`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            FatalErrorCatchScanner.lines(source.text).filter { source.isOwn(it) }.map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Rethrow VirtualMachineError (OutOfMemoryError) from this catch, as Looper.java and " +
                "KcefContentRules.kt do (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags a catch of Error and a Throwable swallowed in a loop`() {
        FatalErrorCatchScanner.lines(
            """
            fun loop() {
                while (running) {
                    try {
                        step()
                    } catch (e: Throwable) {
                        log(e)
                    }
                }
            }
            fun once() {
                try { step() } catch (e: Error) { log(e) }
                items.forEach { item ->
                    try { item.run() } catch (_: Throwable) { }
                }
            }
            class Java {
                void run() {
                    for (;;) {
                        try { step(); } catch (final Throwable t) { Log.e(TAG, "threw", t); }
                    }
                }
            }
            """.trimIndent(),
        ) shouldBe listOf(5, 11, 13, 19)
    }

    @Test
    fun `accepts rethrows, VM error checks, an earlier VM catch and one-off catches`() {
        FatalErrorCatchScanner.lines(
            """
            fun accepted() {
                while (true) {
                    try { a() } catch (e: Throwable) { cleanUp(); throw e }
                    try { a() } catch (e: Throwable) { throw IOException("wrapped", e) }
                    try { a() } catch (e: Throwable) {
                        if (e !is Exception && e !is LinkageError) throw e
                        log(e)
                    }
                    try { a() } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                    }
                    try { a() } catch (e: OutOfMemoryError) { throw e } catch (e: Throwable) { log(e) }
                    try { a() } catch (e: Throwable) { CrashReporter.fatal(e) }
                    try { a() } catch (e: Exception) { log(e) }
                }
                try { a() } catch (e: Throwable) { toast(e) }
            }
            class Looper {
                void loop() {
                    try { a(); } catch (Error error) {
                        if (error instanceof VirtualMachineError) { throw error; }
                    }
                }
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    @Test
    fun `a loop in an outer function does not count for a catch in a nested function`() {
        FatalErrorCatchScanner.lines(
            """
            fun outer() {
                for (x in xs) {
                    fun inner() {
                        try { a() } catch (e: Throwable) { log(e) }
                    }
                    inner()
                }
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        const val PER_ITEM = "one item's failure (an extension's Error included) is recorded and the loop " +
            "goes on to the next item; an OutOfMemoryError there is swallowed too"
        val ALLOWED = listOf(
            Allowed("harness/ExtensionWiringCheck.kt", "} catch (e: Throwable) {", "the extension-wiring check (a test harness, not the app): records each setting's failure and goes on to the next", function = "checkSettings"),
            Allowed("ui/settings/MissingExtensions.kt", "} catch (e: Throwable) {", PER_ITEM, function = "installMissingExtensions"),
            Allowed("ui/reader/ReaderScreenModel.kt", "} catch (e: Throwable) {", "prefetching one page of many; that page loads again when it is shown", function = "prefetchAll"),
            Allowed("download/ChapterDownloader.kt", "} catch (e: Throwable) {", "the retry loop keeps the failure in `last` and rethrows it after the last attempt", function = "retry"),
        )
    }
}

/** Finds the catches [FatalErrorCatchScanTest] forbids; returns their 1-based lines. */
internal object FatalErrorCatchScanner {
    private val KOTLIN_CATCH = Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*(?:kotlin\.|java\.lang\.)?(Throwable|Error)\s*\)""")
    private val JAVA_CATCH = Regex("""\bcatch\s*\(\s*(?:final\s+)?(?:[\w.]+\s*\|\s*)*(?:java\.lang\.)?(Throwable|Error)\s+(\w+)\s*\)""")
    private val ANY_CATCH = Regex("""\bcatch\s*\(([^)]*)\)\s*$""")
    private val LOOP = listOf(
        Regex("""(?s)\b(?:while|for)\s*\(.*\)\s*$"""),
        Regex("""\bdo\s*$"""),
        Regex("""\b(?:repeat|forEach|forEachIndexed|onEach|forEachLine|loop)\b\s*(?:\([^()]*\))?\s*(?:\w+(?:\s*,\s*\w+)*\s*->)?\s*$"""),
    )
    private val VM_CHECK = Regex("""\b(?:OutOfMemoryError|VirtualMachineError)\b|!is\s+Exception\b|!\s*\(\s*\w+\s+instanceof\s+Exception\s*\)""")

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val catches = KOTLIN_CATCH.findAll(m).map { Triple(it, it.groupValues[2], it.groupValues[1]) } +
            JAVA_CATCH.findAll(m).map { Triple(it, it.groupValues[1], it.groupValues[2]) }
        return catches.mapNotNull { (match, type, name) ->
            val open = m.indexOf('{', match.range.last)
            if (open < 0) return@mapNotNull null
            val block = m.substring(open + 1, SourceScan.closing(m, open))
            val checked = type == "Error" || inLoop(m, match.range.first)
            if (!checked || letsFatalThrough(m, match.range.first, block, name)) null else source.lineOf(match.range.first)
        }.sorted().toList()
    }

    private fun inLoop(m: String, offset: Int): Boolean {
        for (brace in SourceScan.enclosingBraces(m, offset)) {
            val header = SourceScan.header(m, brace)
            if (SourceScan.isFunctionHeader(header)) return false
            if (LOOP.any { it.containsMatchIn(header) }) return true
        }
        return false
    }

    private fun letsFatalThrough(m: String, catchAt: Int, block: String, name: String): Boolean {
        // An unconditional throw: a statement of the block itself that starts with `throw`
        val topLevel = topLevelText(block)
        if (Regex("""(?:^|[;\n{])\s*throw\b""").containsMatchIn(topLevel)) return true
        if ("CrashReporter.fatal(" in block) return true
        if (Regex("""\bthrow\b""").containsMatchIn(block) && VM_CHECK.containsMatchIn(block)) return true
        return earlierCatches(m, catchAt).any { Regex("""\b(?:OutOfMemoryError|VirtualMachineError)\b""").containsMatchIn(it) }
    }

    /** [block] without its nested blocks, and without lines that start with `if`/`when`/`else`. */
    private fun topLevelText(block: String): String {
        val out = StringBuilder()
        var depth = 0
        block.forEach { c ->
            when (c) {
                '{' -> depth++
                '}' -> depth--
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.lines().filterNot { Regex("""^\s*(?:if|when|else|\?:|\|\||&&)\b""").containsMatchIn(it) || "?:" in it }
            .joinToString("\n")
    }

    /** The parameter text of the catches before the one at [catchAt] in the same `try`. */
    private fun earlierCatches(m: String, catchAt: Int): List<String> {
        val found = mutableListOf<String>()
        var i = catchAt - 1
        while (true) {
            while (i >= 0 && m[i].isWhitespace()) i--
            if (i < 0 || m[i] != '}') return found
            val open = SourceScan.opening(m, i)
            if (open < 0) return found
            val header = SourceScan.header(m, open)
            val catch = ANY_CATCH.find(header) ?: return found
            found += catch.groupValues[1]
            i = open - header.length + header.indexOf("catch") - 1
        }
    }
}
