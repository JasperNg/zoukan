package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

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
        val ALLOWED = listOf(
            Allowed(
                "android-compat/src/main/java/xyz/nulldev/androidcompat/webkit/KcefHelper.kt",
                ".thenAccept { cb(it) }",
                "known defect, fix in progress: the evaluateJavascript callback runs inside the DevTools " +
                    "reply, and an extension (or BrowserWindow) that loads a page from it ends the process. " +
                    "Remove this entry when the fix (an Async stage) lands",
            ),
        )
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
