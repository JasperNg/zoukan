package zoukan.desktop.scan

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed
import zoukan.desktop.scan.SourceScan.Finding

class SourceScanTest {
    private val site = Finding("desktop/src/main/kotlin/zoukan/desktop/A.kt", 3, "dir.deleteRecursively()")

    @Test
    fun `an allowed site with a reason passes`() {
        SourceScan.problems(listOf(site), listOf(Allowed("zoukan/desktop/A.kt", "deleteRecursively", "own temp folder")))
            .shouldBeEmpty()
    }

    @Test
    fun `a site no entry allows fails`() {
        SourceScan.problems(listOf(site), emptyList()) shouldBe listOf(site.toString())
    }

    @Test
    fun `an entry without a reason fails`() {
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively", " ")
        SourceScan.problems(listOf(site), listOf(entry)) shouldBe listOf("Allow-list entry without a reason: $entry")
    }

    @Test
    fun `a stale entry that matches no site fails`() {
        val stale = Allowed("zoukan/desktop/B.kt", "deleteRecursively", "fixed since")
        SourceScan.problems(listOf(site), listOf(Allowed("A.kt", "", "the helper"), stale)) shouldBe
            listOf("Allow-list entry that matches nothing (remove it): $stale")
    }

    @Test
    fun `a new site in an allowed file is flagged, though the entry's part matches it`() {
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively()", "own temp folder")
        val extra = site.copy(line = 9)
        val problems = SourceScan.problems(listOf(site, extra), listOf(entry))
        problems.size shouldBe 1
        problems.single() shouldStartWith "Allow-list entry allows 1 site(s) but 2 match (a new site needs its own entry and reason)"
        problems.single() shouldContain extra.toString()
        // A count that names both sites allows them; a fixed site is flagged the other way
        SourceScan.problems(listOf(site, extra), listOf(entry.copy(count = 2))).shouldBeEmpty()
        SourceScan.problems(listOf(site), listOf(entry.copy(count = 2))).single() shouldContain "a site was fixed: lower the count"
        // A whole-file entry with no count is for a checked helper's own file
        SourceScan.problems(listOf(site, extra), listOf(Allowed("A.kt", "", "the helper", count = null))).shouldBeEmpty()
    }

    @Test
    fun `an entry that names a function allows only the site in that function`() {
        val inClear = site.copy(function = "clear")
        val inOther = site.copy(line = 20, function = "prune")
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively()", "own temp folder", function = "clear")
        SourceScan.problems(listOf(inClear, inOther), listOf(entry)) shouldBe listOf(inOther.toString())
    }

    @Test
    fun `a finding names the function around it, Kotlin and Java`() {
        val source = SourceScan.Source(
            "A.kt",
            """
            class A {
                fun `the library's list`() {
                    one()
                }
                private suspend fun <T> Foo.load(block: () -> T) = runBlocking(Dispatchers.IO) {
                    items.forEach { two() }
                }
                val top = three()
            }
            class J {
                public void run() throws IOException {
                    for (;;) { four(); }
                }
            }
            """.trimIndent(),
        )
        listOf(3, 6, 8, 12).map { source.functionAt(it) } shouldBe listOf("`the library's list`", "load", null, "run")
        SourceScan.finding(source, 6).function shouldBe "load"
    }

    @Test
    fun `only the lines inside Zoukan's markers are its own in a vendored file`() {
        val lines = """
            upstream()
            // ZOUKAN -->
            ours()
            // ZOUKAN <--
            upstream()
            call(/* ZOUKAN --> */ours/* ZOUKAN <-- */)
        """.trimIndent().lines()
        SourceScan.zoukanLines(lines).toList() shouldBe listOf(false, true, true, true, false, true)
        SourceScan.zoukanLines(listOf("// ZOUKAN: whole file -->", "x()", "// ZOUKAN: whole file <--")).toList() shouldBe
            listOf(true, true, true)
    }

    @Test
    fun `the header of a Java for loop keeps its semicolons`() {
        val code = "void run() { for (int i = 0; i < n; i++) { step(); } }"
        SourceScan.header(code, code.indexOf("{ step")).trim() shouldBe "for (int i = 0; i < n; i++)"
    }
}

/**
 * Chromium delivers a DevTools reply on its UI thread, and a navigation started from inside that
 * callback fails a Chromium check and ends the process. Fails when a synchronous stage chained on
 * `executeDevToolsMethod(...)` (`thenAccept`, `whenComplete`, `handle`...), or a DevTools observer
 * method, navigates or closes a browser, calls a function of the same file that does, or calls a
 * function-typed parameter of its function (a caller's callback, which may navigate). An `...Async`
 * stage ends the check: it and the stages after it run on another thread.
 */
class DevToolsCallbackScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "android-compat/src/main/java",
        "core/common/src/desktopMain",
        "extension-jvm/src/main",
    )

    @Test
    fun `no DevTools callback navigates or runs a caller's callback`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            DevToolsCallbackScanner.lines(source.text).map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Do not navigate, close a browser or run a caller's callback inside a DevTools callback; " +
                "use an ...Async stage (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags a load inside whenComplete and a callback run inside thenAccept`() {
        DevToolsCallbackScanner.lines(
            """
            fun learn(browser: CefBrowser, url: String) {
                browser.devToolsClient.executeDevToolsMethod("Emulation.setUserAgentOverride", args).whenComplete { _, _ ->
                    browser.loadURL(url)
                }
            }
            fun CefBrowser.evaluate(script: String, cb: (String) -> Unit) {
                val evaluation = devToolsClient?.executeDevToolsMethod("Runtime.evaluate", script) ?: return
                evaluation
                    .handle { response, _ -> response ?: "null" }
                    .thenAccept { cb(it) }
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 10)
    }

    @Test
    fun `flags a call to a function of the file that navigates, and a callback passed as the stage`() {
        DevToolsCallbackScanner.lines(
            """
            private fun go(browser: CefBrowser) {
                browser.reload()
            }
            fun ask(browser: CefBrowser, done: (String?) -> Unit) {
                browser.devToolsClient.executeDevToolsMethod("Runtime.evaluate", "1").thenRun { go(browser) }
                browser.devToolsClient.executeDevToolsMethod("Runtime.evaluate", "1").thenAccept(done)
            }
            class Observer : CefDevToolsMessageObserver {
                override fun onDevToolsEvent(browser: CefBrowser, method: String, parameters: String) {
                    browser.loadURL("about:blank")
                }
            }
            """.trimIndent(),
        ) shouldBe listOf(5, 6, 10)
    }

    @Test
    fun `accepts an Async stage, logging, and calls outside the callback`() {
        DevToolsCallbackScanner.lines(
            """
            fun CefBrowser.evaluate(script: String, cb: (String) -> Unit) {
                devToolsClient.executeDevToolsMethod("Runtime.evaluate", script)
                    .handle { response, _ -> response ?: "null" }
                    .thenAcceptAsync { cb(it) }
                    .thenRun { cb("again") }
                devToolsClient.executeDevToolsMethod("Emulation.setTouchEmulationEnabled", "{}")
                    .whenComplete { _, ex -> if (ex != null) Log.e(TAG, "failed loadURL(", ex) }
                // .thenAccept { loadURL(url) }
                loadURL(url)
                cb("done")
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        val ALLOWED = emptyList<Allowed>()
    }
}

/** Finds the lines where a DevTools callback navigates or runs a caller's callback (see [DevToolsCallbackScanTest]). */
internal object DevToolsCallbackScanner {
    /** Navigation and browser lifecycle calls, CEF's and the Android WebView's that map onto them. */
    private val CHROMIUM = Regex(
        """(?<![\w$])(?:loadURL|loadRequest|loadString|loadUrl|loadData|loadDataWithBaseURL|postUrl|reload|""" +
            """reloadIgnoreCache|goBack|goForward|goBackOrForward|stopLoad|close|dispose|destroy)\s*\(""",
    )
    private val STAGES = setOf(
        "thenAccept", "thenApply", "thenRun", "thenCompose", "thenCombine", "thenAcceptBoth", "runAfterBoth",
        "runAfterEither", "acceptEither", "applyToEither", "whenComplete", "handle", "exceptionally",
        "exceptionallyCompose",
    )
    private val CALL = Regex("""executeDevToolsMethod\s*\(""")
    private val FUTURE_VAL = Regex("""\b(?:val|var)\s+(\w+)\b[^=\n]*=[^\n]*executeDevToolsMethod\s*\(""")
    private val OBSERVER = Regex("""\bfun\s+(?:onDevToolsMethodResult|onDevToolsEvent|onDevToolsMessage|onDevToolsAgentAttached|onDevToolsAgentDetached)\s*\(""")
    private val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val navigating = navigatingFunctions(m)
        val hits = sortedSetOf<Int>()

        fun bad(range: IntRange, enclosingParams: Set<String>): Boolean {
            val body = m.substring(range)
            val bare = body.trim().removePrefix("::")
            return CHROMIUM.containsMatchIn(body) ||
                navigating.any { Regex("""(?<![\w.$])$it\s*\(""").containsMatchIn(body) } ||
                enclosingParams.any { p ->
                    bare == p || Regex("""(?<![\w.$])$p\s*(?:\(|\.invoke\s*\(|\.onReceiveValue\s*\(|\.accept\s*\()""").containsMatchIn(body)
                }
        }

        fun stagesFrom(start: Int) {
            val params = functionParams(m, start)
            var i = start
            while (true) {
                i = skipSpace(m, i)
                if (m.startsWith("!!", i)) i = skipSpace(m, i + 2)
                if (m.startsWith("?.", i)) {
                    i += 2
                } else if (i < m.length && m[i] == '.') {
                    i++
                } else {
                    return
                }
                val name = Regex("""\w+""").matchAt(m, skipSpace(m, i))?.value ?: return
                i = skipSpace(m, i) + name.length
                val stageAt = i - name.length
                var args: IntRange? = null
                var lambda: IntRange? = null
                var j = skipSpace(m, i)
                if (j < m.length && m[j] == '(') {
                    val close = SourceScan.closing(m, j)
                    args = (j + 1) until close
                    i = close + 1
                    j = skipSpace(m, i)
                }
                if (j < m.length && m[j] == '{') {
                    val close = SourceScan.closing(m, j)
                    lambda = (j + 1) until close
                    i = close + 1
                }
                if (name.endsWith("Async")) return
                if (name in STAGES) {
                    // The line reported is the stage's own line
                    if (listOfNotNull(args, lambda).any { bad(it, params) }) hits += source.lineOf(stageAt)
                }
            }
        }

        CALL.findAll(m).forEach { call ->
            val open = call.range.last
            stagesFrom(SourceScan.closing(m, open) + 1)
        }
        FUTURE_VAL.findAll(m).forEach { declared ->
            val name = declared.groupValues[1]
            Regex("""(?<![\w.$])$name\b(?=\s*(?:!!)?\s*\??\.)""").findAll(m, declared.range.last).forEach { use ->
                stagesFrom(use.range.last + 1)
            }
        }
        OBSERVER.findAll(m).forEach { declaration ->
            val open = m.indexOf('{', SourceScan.closing(m, declaration.range.last))
            if (open < 0) return@forEach
            val body = (open + 1) until SourceScan.closing(m, open)
            val nav = CHROMIUM.find(m.substring(body))
                ?: navigating.firstNotNullOfOrNull { Regex("""(?<![\w.$])$it\s*\(""").find(m.substring(body)) }
            if (nav != null) hits += source.lineOf(body.first + nav.range.first)
        }
        return hits.toList()
    }

    private fun skipSpace(m: String, from: Int): Int {
        var i = from
        while (i < m.length && m[i].isWhitespace()) i++
        return i
    }

    /** Names of functions declared in [m] whose body navigates or closes a browser. */
    private fun navigatingFunctions(m: String): Set<String> = FUN.findAll(m).mapNotNull { declaration ->
        val paramsEnd = SourceScan.closing(m, declaration.range.last)
        val rest = m.substring(paramsEnd + 1).trimStart()
        if (!rest.startsWith("{") && !Regex("""^:\s*[\w<>?,. ]+\{""").containsMatchIn(rest)) return@mapNotNull null
        val open = m.indexOf('{', paramsEnd)
        val body = m.substring(open + 1, SourceScan.closing(m, open))
        declaration.groupValues[1].takeIf { CHROMIUM.containsMatchIn(body) }
    }.toSet()

    /** The function-typed parameters (`(T) -> R`, `...Callback`) of the function around [offset]. */
    private fun functionParams(m: String, offset: Int): Set<String> {
        val brace = SourceScan.enclosingFunction(m, offset) ?: return emptySet()
        val header = SourceScan.header(m, brace)
        val open = header.indexOf('(').takeIf { it >= 0 } ?: return emptySet()
        val headerStart = brace - header.length
        val close = SourceScan.closing(m, headerStart + open)
        val params = m.substring(headerStart + open + 1, close)
        val parts = mutableListOf<String>()
        var depth = 0
        var last = 0
        params.forEachIndexed { i, c ->
            when (c) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> if (!(c == '>' && i > 0 && params[i - 1] == '-')) depth--
                ',' -> if (depth == 0) {
                    parts += params.substring(last, i)
                    last = i + 1
                }
            }
        }
        parts += params.substring(last)
        return parts.mapNotNull { p ->
            val name = Regex("""^\s*(?:@\w+\s+)*(?:vararg\s+|noinline\s+|crossinline\s+)?(\w+)\s*:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
                .find(p) ?: return@mapNotNull null
            val type = name.groupValues[2]
            name.groupValues[1].takeIf { "->" in type || Regex("""Callback\b|Consumer\b""").containsMatchIn(type) }
        }.toSet()
    }
}

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
        val ALLOWED = emptyList<Allowed>()
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

/**
 * A catch-all must let the failures through that the app must not keep running after: an
 * `OutOfMemoryError` that is caught leaves the app running in a broken state, and the crash report
 * never sees it. Every `catch (e: Throwable)` / `catch (e: Error)` (Kotlin) and
 * `catch (Throwable t)` / `catch (Error e)` (Java) in the app's own code must, at the top level of
 * its block (not under an `if`):
 *
 * - call `e.rethrowIfFatal()` (`FatalErrors.rethrowIfFatal(e)` in Java), which rethrows a
 *   `VirtualMachineError` and a `LinkageError`; or
 * - rethrow the caught error itself unconditionally (`throw e`, after cleanup). Wrapping it
 *   (`throw IOException(e)`) does not count: the wrapper is an exception the caller catches.
 *
 * On a path that runs extension code (or loads a native library), a `LinkageError` is an outdated
 * extension (`SourceErrors.kt`) and is shown as an error: there the catch calls
 * `e.rethrowIfVmFailure()` instead, or keeps the older `if (e !is Exception && e !is LinkageError)
 * throw e`. Native-boundary catches that cannot throw back into native code are listed in [ALLOWED].
 *
 * Kotlin's `runCatching` catches every Throwable too. It is left out of this rule: the app's own
 * code has about 300 calls, most around one file or preference operation, and moving them all to a
 * checked helper is a change of its own. The project's `runCatchingCancellable` rethrows the VM's
 * failures (`app/Cancellation.kt`), so code around suspend calls should use it.
 */
class FatalErrorCatchScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    private val sites by lazy {
        SourceScan.sources(sourceDirs).flatMap { source ->
            FatalErrorCatchScanner.sites(source.text).filter { source.isOwn(it.line) }
                .map { it.kind to SourceScan.finding(source, it.line) }
        }
    }

    @Test
    fun `every catch-all rethrows fatal errors`() {
        SourceScan.verify(
            sites.filter { it.first == FatalErrorCatchScanner.Kind.SWALLOWS }.map { it.second },
            ALLOWED,
            "Call e.rethrowIfFatal() first in this catch (xyz.nulldev.androidcompat.util), or rethrow " +
                "the caught error after cleanup (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags every catch-all without a rethrow, a conditional rethrow and a wrapper`() {
        FatalErrorCatchScanner.lines(
            """
            fun once() {
                try { step() } catch (e: Throwable) { log(e) }
                try { step() } catch (e: Error) { log(e) }
                try { a() } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                }
                try { a() } catch (e: Throwable) { throw IOException("wrapped", e) }
                try { a() } catch (e: Throwable) {
                    if (bad) e.rethrowIfFatal()
                }
                try { a() } catch (e: Throwable) { other.rethrowIfFatal() }
                try { a() } catch (e: Throwable) { CrashReporter.fatal(e) }
                try { a() } catch (e: Throwable) { if (x) throw e }
            }
            class Java {
                void run() {
                    try { step(); } catch (final Throwable t) { Log.e(TAG, "threw", t); }
                    try { a(); } catch (Error error) {
                        if (error instanceof VirtualMachineError) { throw error; }
                    }
                }
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 3, 4, 7, 8, 11, 12, 13, 17, 18)
    }

    @Test
    fun `accepts the helper, an unconditional rethrow and narrower catches`() {
        FatalErrorCatchScanner.lines(
            """
            fun accepted() {
                try { a() } catch (e: Throwable) { e.rethrowIfFatal(); log(e) }
                try { a() } catch (e: Throwable) { cleanUp(); throw e }
                try { a() } catch (e: Throwable) {
                    toast(e)
                    e.rethrowIfFatal()
                }
                try { a() } catch (e: Exception) { log(e) }
                try { a() } catch (e: CancellationException) { log(e) }
                val x = try { a() } catch (t: Throwable) {
                    t.rethrowIfFatal()
                    null
                }
            }
            class Java {
                void run() {
                    try { a(); } catch (Throwable t) { FatalErrors.rethrowIfFatal(t); log(t); }
                    try { a(); } catch (Error e) { cleanUp(); throw e; }
                }
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    @Test
    fun `tells the extension form apart`() {
        FatalErrorCatchScanner.sites(
            """
            fun extension() {
                try { a() } catch (e: Throwable) { e.rethrowIfVmFailure(); log(e) }
                try { a() } catch (e: Throwable) {
                    // A refusal, or the native store failing to load
                    if (e !is Exception && e !is LinkageError) throw e
                    log(e)
                }
                try { a() } catch (e: Throwable) { e.rethrowIfFatal() }
            }
            class Java {
                void run() {
                    try { a(); } catch (Error error) { xyz.nulldev.androidcompat.util.FatalErrors.rethrowIfVmFailure(error); }
                }
            }
            """.trimIndent(),
        ).map { it.line to it.kind } shouldBe listOf(
            2 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
            3 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
            12 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
        )
    }

    private companion object {
        val ALLOWED = listOf(
            Allowed("zoukan/desktop/Main.kt", "} catch (e: Throwable) {", "the start-up's last handler: CrashReporter.fatal reports every failure, fatal ones included, and ends the app", function = "dispatch"),
            Allowed("app/AppShutdown.kt", "} catch (e: Throwable) {", "the last step before the process exits: the failure is logged and the exit goes on; a rethrow could keep the process from exiting", function = "runForApp"),
            Allowed("network/AppDnsResolver.kt", "} catch (_: Throwable) {", "loading the JDK's resolver provider: an error escaping it leaves InetAddress unusable for the whole run, so the JDK's own resolver is used instead", function = "get"),
            Allowed("image/FfmpegImageDecoder.kt", "} catch (_: Throwable) {", "a read or seek callback from FFmpeg's native code: nothing may be thrown back into native code; the error code ends the decode, which then fails", function = "call", count = 2),
            Allowed("security/SecureScreen.kt", "} catch (e: Throwable) {", "a callback from macOS's main dispatch queue through JNA: nothing may be thrown back into native code", function = "invoke"),
            Allowed("webkit/KcefContentRules.kt", "} catch (e: Throwable) {", "called from native code: NativeCallback.guard wraps every callback Chromium makes through JCEF (the request filters in onBeforeResourceLoad on its IO thread, the browser windows' handlers), so nothing may be thrown back into native code; an Error is logged and reported once, and the callback does what it does by default", function = "guard"),
            Allowed("webkit/KcefContentRules.kt", "} catch (_: Throwable) {", "called from native code: NativeCallback.failed logs and reports a callback's failure, and running out of memory while it does must not be thrown back into native code either", function = "failed"),
        )
    }
}

/** Finds the catch-alls [FatalErrorCatchScanTest] forbids or restricts. */
internal object FatalErrorCatchScanner {
    enum class Kind {
        /** Lets no fatal error through. */
        SWALLOWS,

        /** Rethrows the VM's failures but keeps a LinkageError: only on a path that runs extension code. */
        KEEPS_LINKAGE,
    }

    data class Site(val line: Int, val kind: Kind)

    private val KOTLIN_CATCH = Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*(?:kotlin\.|java\.lang\.)?(?:Throwable|Error)\s*\)""")
    private val JAVA_CATCH = Regex("""\bcatch\s*\(\s*(?:final\s+)?(?:[\w.]+\s*\|\s*)*(?:java\.lang\.)?(?:Throwable|Error)\s+(\w+)\s*\)""")

    /** The 1-based lines of the catch-alls that swallow fatal errors. */
    fun lines(text: String): List<Int> = sites(text).filter { it.kind == Kind.SWALLOWS }.map { it.line }

    fun sites(text: String): List<Site> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val catches = (KOTLIN_CATCH.findAll(m) + JAVA_CATCH.findAll(m)).map { it to it.groupValues[1] }
        return catches.mapNotNull { (match, name) ->
            val open = m.indexOf('{', match.range.last)
            if (open < 0) return@mapNotNull null
            val block = m.substring(open + 1, SourceScan.closing(m, open))
            val kind = classify(block, name) ?: return@mapNotNull null
            Site(source.lineOf(match.range.first), kind)
        }.sortedBy { it.line }.toList()
    }

    /** Null when [block] lets every fatal error through. */
    private fun classify(block: String, name: String): Kind? {
        val n = Regex.escape(name)
        val topLevel = topLevelText(block)
        // An unconditional rethrow of the caught error itself
        if (Regex("""(?:^|[;\n{])\s*throw\s+$n\s*(?:;|$)""", RegexOption.MULTILINE).containsMatchIn(topLevel)) return null
        if (Regex("""(?:\b$n\.rethrowIfFatal\s*\(\s*\)|\bFatalErrors\.rethrowIfFatal\s*\(\s*$n\s*\))""").containsMatchIn(topLevel)) return null
        val keepsLinkage = Regex("""(?:\b$n\.rethrowIfVmFailure\s*\(\s*\)|\bFatalErrors\.rethrowIfVmFailure\s*\(\s*$n\s*\))""").containsMatchIn(topLevel) ||
            Regex("""(?m)^\s*if\s*\(\s*$n\s*!is\s+Exception\s*&&\s*$n\s*!is\s+LinkageError\s*\)\s*throw\s+$n\s*$""").containsMatchIn(block)
        return if (keepsLinkage) Kind.KEEPS_LINKAGE else Kind.SWALLOWS
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
}

/**
 * Recursive tree deletes, permission changes and extended attribute writes go through the helpers
 * that check the path is the app's own and never follow a symbolic link (`SafeDelete`,
 * `NoFollowDelete`, `CacheFolders`, `DataFolderPrivacy`). Fails on these anywhere else in the
 * app's own code:
 *
 * - `deleteRecursively()`, which follows a linked folder and deletes what it points to (okio's
 *   `deleteRecursively(path)` too);
 * - `Files.walk` / `walkFileTree` / `walk()` in a function that also deletes;
 * - permission changes: `setPosixFilePermissions`, `setReadable`, `setWritable`, `setExecutable`,
 *   `Files.setAttribute`, `setOwner`;
 * - extended attributes: `setxattr`, `removexattr`, `UserDefinedFileAttributeView`, and starting
 *   `xattr`, `tmutil`, `mdutil`, `chflags`, `chmod`, `chown`, `attrib` or `icacls`.
 */
class FileMutationScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    @Test
    fun `tree deletes, permission changes and attribute writes go through the checked helpers`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            FileMutationScanner.lines(source.text).filter { source.isOwn(it) }.map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Delete trees with SafeDelete.delete / deleteTreeNoFollow and change permissions or attributes " +
                "in the checked helpers (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags tree deletes, a walk that deletes, permission and attribute changes`() {
        FileMutationScanner.lines(
            """
            fun clear(dir: File) {
                dir.deleteRecursively()
                fileSystem.deleteRecursively(path)
            }
            fun prune(root: Path) {
                Files.walk(root).use { s -> s.forEach { Files.delete(it) } }
            }
            fun lock(f: File) {
                Files.setPosixFilePermissions(f.toPath(), perms)
                f.setWritable(false)
                libc.setxattr(f.path, "name", value, size, 0, 0)
                ProcessBuilder("tmutil", "addexclusion", f.path).start()
                ProcessBuilder("/usr/bin/xattr", "-w", "x", "y", f.path).start()
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 3, 6, 9, 10, 11, 12, 13)
    }

    @Test
    fun `accepts a walk that only reads and mentions`() {
        FileMutationScanner.lines(
            """
            fun size(root: Path): Long = Files.walk(root).use { s -> s.mapToLong { Files.size(it) }.sum() }
            fun tidy() {
                // dir.deleteRecursively()
                log("deleteRecursively() and setxattr( and tmutil are only words here")
                ProcessBuilder("pmset", "-g", "ps").start()
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        const val HELPER = "a checked helper: resolves the path inside the app's own folder and never follows a link"
        val ALLOWED = listOf(
            Allowed("zoukan/desktop/CacheFolders.kt", "", HELPER, count = null),
            Allowed("zoukan/desktop/DataFolderPrivacy.kt", "", "the privacy-mark helper: sets owner-only permissions and the Time Machine attribute on the app's own folders, without following links (XATTR_NOFOLLOW, isSymbolicLink)", count = null),
            Allowed("zoukan/desktop/secrets/FileSecretStore.kt", "setPosixFilePermissions", "makes the secret file it has just written owner-only, by a name it checked", function = "restrict"),
        )
    }
}

/** Finds the file changes [FileMutationScanTest] forbids; returns their 1-based lines. */
internal object FileMutationScanner {
    private val CODE = listOf(
        Regex("""\.deleteRecursively\s*\("""),
        Regex("""\bsetPosixFilePermissions\s*\("""),
        Regex("""\.set(?:Readable|Writable|Executable)\s*\("""),
        Regex("""\bFiles\.setAttribute\s*\("""),
        Regex("""\bFiles\.setOwner\s*\("""),
        Regex("""(?<![\w$])(?:f?setxattr|f?removexattr)\s*\("""),
        Regex("""\bUserDefinedFileAttributeView\b"""),
    )
    private val TOOL = Regex(""""(?:/usr/bin/|/bin/|/usr/sbin/)?(?:xattr|tmutil|mdutil|chflags|chmod|chown|attrib|icacls)(?:\.exe)?"""")
    private val WALK = Regex("""\bFiles\.walk(?:FileTree)?\s*\(|\.walk(?:TopDown|BottomUp)?\s*\(\s*\)""")
    private val DELETE = Regex("""\bdelete\w*\s*\(""")
    private val FUN_DECLARATION = Regex("""\bfun\s+\w*(?:set|remove)xattr\s*\(""")

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val hits = sortedSetOf<Int>()
        CODE.forEach { pattern ->
            pattern.findAll(m).forEach { match ->
                // A native binding's own declaration is not a call
                val lineStart = m.lastIndexOf('\n', match.range.first) + 1
                if (!FUN_DECLARATION.containsMatchIn(m.substring(lineStart, match.range.last + 1))) {
                    hits += source.lineOf(match.range.first)
                }
            }
        }
        TOOL.findAll(source.withoutComments).forEach { hits += source.lineOf(it.range.first) }
        WALK.findAll(m).forEach { walk ->
            val body = SourceScan.enclosingFunction(m, walk.range.first)?.let { m.substring(it, SourceScan.closing(m, it)) }
                ?: statement(m, walk.range.first)
            if (DELETE.containsMatchIn(body)) hits += source.lineOf(walk.range.first)
        }
        return hits.toList()
    }

    /** The line of [offset] and the lines after it that continue it, for an expression-body function. */
    private fun statement(m: String, offset: Int): String {
        val start = m.lastIndexOf('\n', offset) + 1
        val end = m.indexOf('\n', offset).let { if (it < 0) m.length else it }
        return m.substring(start, end)
    }
}

/**
 * ImageIO's `read(InputStream)`, `read(URL)`, `write(..., OutputStream)` and its
 * `createImageInputStream` / `createImageOutputStream` on a stream copy the image to a temporary
 * file in the system's temporary folder while it is read or written (its file cache), so a
 * decrypted page or cover would land there in plain. Fails on these in the app's own code:
 *
 * - `ImageIO.read(...)` unless its argument is a `MemoryCacheImageInputStream(...)` or a stream over
 *   a byte array (`byteArrayImageInputStream(...)`, TwelveMonkeys' `ByteArrayImageInputStream(...)`);
 * - `ImageIO.write(...)` and `ImageIO.createImageInputStream(...)` / `createImageOutputStream(...)`.
 *
 * Read and write images held in memory with `MemoryImageIo` (desktop) or a
 * `MemoryCacheImageInputStream` / `MemoryCacheImageOutputStream` (android-compat). A `File`
 * argument is opened directly, with no cache file; such a site needs an entry with its reason.
 * Every command also turns ImageIO's file cache off at start (`runCommand`); this rule does not
 * rely on that, which a test or a caller outside `runCommand` would not have.
 */
class ImageIoCacheScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    @Test
    fun `images in memory are read and written through streams cached in memory`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            ImageIoCacheScanner.lines(source.text).filter { source.isOwn(it) }.map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Read and write images in memory with MemoryImageIo or a MemoryCacheImageInputStream / " +
                "MemoryCacheImageOutputStream (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags ImageIO's file-cached reads and writes, and accepts memory-cached ones`() {
        ImageIoCacheScanner.lines(
            """
            val a = ImageIO.read(ByteArrayInputStream(bytes))
            val b = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))
            ImageIO.write(image, "png", out)
            ImageIO.createImageInputStream(input)
            ImageIO.createImageOutputStream(output)
            val c = ImageIO.read(javax.imageio.stream.MemoryCacheImageInputStream(it))
            val d = ImageIO.read(/* ZOUKAN --> */javax.imageio.stream.MemoryCacheImageInputStream(it)/* ZOUKAN <-- */)
            // ImageIO.read(stream) in a comment
            val e = "ImageIO.write(image) in a string"
            val f = javax.imageio.ImageIO.read(url)
            val g = ImageIO.read(byteArrayImageInputStream(bytes))
            val h = ImageIO.read(com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream(bytes))
            val i = ImageIO.read(ByteArrayImageInputStream(bytes))
            val j = ImageIO.read(ByteArrayInputStreamWrapper(bytes))
            """.trimIndent(),
        ) shouldBe listOf(1, 3, 4, 5, 10, 14)
    }

    private companion object {
        private const val MAIN = "desktop/src/main/kotlin/zoukan/desktop"

        val ALLOWED = listOf(
            Allowed(
                "$MAIN/image/MemoryImageIo.kt",
                "ImageIO.write(image, \"png\", stream)",
                "the helper itself: writes to a ByteArrayImageOutputStream, a stream into a byte array",
                function = "png",
            ),
            Allowed(
                "$MAIN/download/TallImageSplitter.kt",
                "ImageIO.createImageInputStream(image)",
                "a File, which ImageIO opens directly (FileImageInputStream), with no cache file: the page is " +
                    "already a file in the download folder",
                function = "splitIfTall",
            ),
            Allowed(
                "$MAIN/download/TallImageSplitter.kt",
                "ImageIO.createImageOutputStream(file)",
                "a File, which ImageIO opens directly (FileImageOutputStream), with no cache file: the part is " +
                    "written into the download folder",
                function = "writeJpeg",
            ),
        )
    }
}

/** Finds the ImageIO calls [ImageIoCacheScanTest] forbids; returns their 1-based lines. */
internal object ImageIoCacheScanner {
    private val CALL = Regex(
        """\bImageIO\s*\.\s*(?:write|createImageInputStream|createImageOutputStream)\s*\(|""" +
            """\bImageIO\s*\.\s*read\s*\((?!\s*(?:(?:javax\s*\.\s*imageio\s*\.\s*stream\s*\.\s*)?MemoryCacheImageInputStream|""" +
            """(?:[\w.]+\s*\.\s*)?(?:ByteArrayImageInputStream|byteArrayImageInputStream))\s*\()""",
    )

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        return CALL.findAll(source.masked).map { source.lineOf(it.range.first) }.distinct().toList()
    }
}

/**
 * Fails on blocking sleeps (`Thread.sleep`, `TimeUnit.*.sleep`, `SystemClock.sleep`) in test code.
 * Tests wait on a condition instead (`Await.until`, a latch, `StateFlow.first` under `withTimeout`,
 * Compose's `waitUntil`, or virtual time with `runTest`).
 */
class ThreadSleepScanTest {
    /** Every test source set: `<module>/src/test` and `<module>/src/<name>Test`. */
    private fun testSourceDirs(): List<String> = SourceScan.root.walk()
        .onEnter { it.name != "build" && it.name != "node_modules" && !it.name.startsWith(".") }
        .maxDepth(4)
        .filter { it.isDirectory && it.parentFile?.name == "src" && (it.name == "test" || it.name.endsWith("Test")) }
        .map { it.relativeTo(SourceScan.root).invariantSeparatorsPath }
        .sorted()
        .toList()

    @Test
    fun `test code waits on a condition instead of sleeping`() {
        val dirs = testSourceDirs()
        check("desktop/src/test" in dirs && "extension-jvm/src/test" in dirs) { "Test source sets missing from $dirs" }
        val findings = SourceScan.sources(dirs).flatMap { source ->
            SleepScanner.lines(source.text).map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Wait for the state the test needs (Await.until, a latch, StateFlow.first under withTimeout, " +
                "waitUntil, runTest's virtual time) instead of sleeping:",
        )
    }

    @Test
    fun `flags blocking sleeps and ignores comments and strings`() {
        SleepScanner.lines(
            """
            fun a() {
                Thread.sleep(100)
                java.lang.Thread.sleep(5)
                TimeUnit.SECONDS.sleep(1)
                MILLISECONDS.sleep(10)
                SystemClock.sleep(25)
                // Thread.sleep(100) in a comment
                val text = "Thread.sleep(100) in a string"
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 3, 4, 5, 6)
    }

    private companion object {
        private const val TESTS = "desktop/src/test/kotlin/zoukan/desktop"

        val ALLOWED = listOf(
            Allowed(
                "$TESTS/testing/TestingHarness.kt",
                "Thread.sleep(POLL_MILLIS)",
                "the wait helper's own polling between checks of the condition",
            ),
            Allowed(
                "$TESTS/e2e/FixtureServer.kt",
                "Thread.sleep(failure.delayMillis)",
                "a fake's behaviour: the fixture server's planned slow answer to a failure",
                function = "failWith",
            ),
            Allowed(
                "$TESTS/e2e/FixtureServer.kt",
                "Thread.sleep(it)",
                "a fake's behaviour: the fixture server's planned slow image",
                function = "image",
            ),
            Allowed(
                "$TESTS/browser/CefCookieSameSiteLiveTest.kt",
                "Thread.sleep(250)",
                "a live test, run by hand against real Chromium: polls cookies on Chromium's thread",
            ),
        )
    }
}

/** Finds blocking sleep calls [ThreadSleepScanTest] forbids; returns their 1-based lines. */
internal object SleepScanner {
    private val SLEEP = Regex(
        """\b(?:Thread|SystemClock)\s*\.\s*sleep\s*\(|\b(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\s*\.\s*sleep\s*\(""",
    )
    private val BACKTICKED = Regex("""`[^`\n]*`""")

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", BACKTICKED.replace(text) { "`" + "x".repeat(it.value.length - 2) + "`" })
        return SLEEP.findAll(source.masked).map { source.lineOf(it.range.first) }.distinct().toList()
    }
}
