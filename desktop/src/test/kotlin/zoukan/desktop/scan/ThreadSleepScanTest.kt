package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * A test that sleeps for a guessed time instead of waiting for the state it needs fails by
 * chance on a slow machine, and is slow on a fast one. Fails on a real-time pause in test code:
 *
 * - `Thread.sleep`, `TimeUnit.X.sleep` and `SystemClock.sleep`, anywhere;
 * - `delay(...)` that pauses the test's own body: inside `runBlocking { }`, directly or through
 *   blocks that run in the same body (`withTimeout`, `withContext`, `if`, `while`, `repeat`...);
 * - the same inside the block of a blocking wrapper: a test helper anywhere in the test sources that
 *   takes a `suspend` block and runs it under `runBlocking` (`fun withX(block: suspend () -> Unit)
 *   { ... runBlocking { block() } }`), as if the block were a `runBlocking` body.
 *
 * Accepted: `delay` under `runTest` (virtual time), and `delay` inside a function or any other
 * lambda (a fake's simulated work, a coroutine the test starts, a helper's polling), which is
 * part of what the test sets up rather than how it waits.
 *
 * Not seen (a limit of reading the text): a wrapper that runs its block under `runBlocking` without
 * taking it as a `suspend` lambda parameter of its own (through a class, a stored lambda, or a
 * library function such as `mockkObject(...) { }` inside a `runBlocking` body, which reads as some
 * other lambda), and a wrapper whose name is used for another function too.
 *
 * Wait on a condition instead: `Await.until`/`Await.untilSuspending` (`zoukan.desktop.testing`),
 * a latch or `CompletableDeferred`, `StateFlow.first { }` under `withTimeout`, Compose's
 * `waitUntil` (journeys: `waitOrDump`, `waitFor`), or virtual time with `runTest`.
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
    fun `test code waits on a condition, not for a guessed time`() {
        val dirs = testSourceDirs()
        check("desktop/src/test" in dirs && "extension-jvm/src/test" in dirs) { "Test source sets missing from $dirs" }
        val sources = SourceScan.sources(dirs)
        val wrappers = sources.flatMap { SleepScanner.blockingWrappers(it.text) }.toSet()
        check("withStubbedSignatures" in wrappers) { "The blocking wrappers were not found: $wrappers" }
        val findings = sources.flatMap { source ->
            SleepScanner.lines(source.text, wrappers).map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Wait for the state the test needs (Await.until, a latch, StateFlow.first under withTimeout, " +
                "waitUntil, runTest's virtual time) instead of sleeping, or add the site to the allow-list " +
                "with its reason (desktop/TESTING.md, \"Waiting in tests\"):",
        )
    }

    @Test
    fun `flags sleeps anywhere and delays that pause a runBlocking body`() {
        SleepScanner.lines(
            """
            fun a() {
                Thread.sleep(100)
                java.lang.Thread.sleep(5)
                TimeUnit.SECONDS.sleep(1)
                MILLISECONDS.sleep(10)
            }
            fun b() = runBlocking<Unit> {
                delay(200)
                withTimeout(1_000) { while (!done) delay(10) }
                repeat(3) { kotlinx.coroutines.delay(5) }
                if (x) {
                    delay(1)
                } else {
                    withContext(Dispatchers.IO) { delay(2) }
                }
            }
            fun c() {
                runBlocking { delay(50) }
                val fake = Server { Thread.sleep(20) }
            }
            fun `the library's list`() {
                Thread.sleep(10)
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 3, 4, 5, 8, 9, 10, 12, 14, 18, 19, 22)
    }

    @Test
    fun `flags a delay in the block of a wrapper that runs it under runBlocking`() {
        val code = """
            private fun withStubs(block: suspend () -> Unit) {
                mockkObject(Signatures) { runBlocking { block() } }
            }
            fun `a test`() = withStubs {
                coroutineScope {
                    launch { delay(5) }
                    delay(200)
                }
            }
            fun other() = mockkObject(Signatures) { delay(1) }
        """.trimIndent()
        SleepScanner.blockingWrappers(code) shouldBe setOf("withStubs")
        SleepScanner.lines(code) shouldBe listOf(7)
        // Found in one file, the wrapper counts in the others too
        SleepScanner.lines("fun t() = withStubs { delay(3) }", setOf("withStubs")) shouldBe listOf(1)
        SleepScanner.lines("fun t() = withStubs { delay(3) }") shouldBe emptyList()
    }

    @Test
    fun `a wrapper that runs its block elsewhere is not a blocking wrapper`() {
        SleepScanner.blockingWrappers(
            """
            fun later(block: suspend () -> Unit) = scope.launch { block() }
            fun plain(block: () -> Unit) { runBlocking { }; block() }
            fun inTest(block: suspend TestScope.() -> Unit) = runTest { block() }
            """.trimIndent(),
        ) shouldBe emptySet()
    }

    @Test
    fun `accepts virtual time, a fake's work, a coroutine's own delay, comments and strings`() {
        SleepScanner.lines(
            """
            fun d() = runTest {
                delay(1_000)
                withTimeout(5_000) { delay(10) }
                advanceUntilIdle()
            }
            fun e() = runBlocking<Unit> {
                val writer = ProgressWriter(scope) { delay(50) }
                launch { delay(10) }
                async(Dispatchers.IO) { delay(10) }
            }
            suspend fun slowFake() {
                delay(100)
            }
            // Thread.sleep(100) in a comment
            val text = "Thread.sleep(100) and delay(5) in a string"
            fun delay(ms: Long) = Unit
            fun f() = scheduler.delay(5)
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    private companion object {
        private const val TESTS = "desktop/src/test/kotlin/zoukan/desktop"

        val ALLOWED = listOf(
            Allowed(
                "$TESTS/testing/Await.kt",
                "Thread.sleep(POLL_MILLIS)",
                "the wait helper's own polling between checks of the condition: what every other wait uses, " +
                    "and it ends as soon as the condition holds",
            ),
            Allowed(
                "$TESTS/e2e/FixtureServer.kt",
                "Thread.sleep(failure.delayMillis)",
                "a fake's behaviour: the fixture server's planned slow answer to a failure, which the journey tests",
                function = "failWith",
            ),
            Allowed(
                "$TESTS/e2e/FixtureServer.kt",
                "Thread.sleep(it)",
                "a fake's behaviour: the fixture server's planned slow image, which the journey tests",
                function = "image",
            ),
            Allowed(
                "$TESTS/download/DownloadQueueStatesTest.kt",
                "Thread.sleep(pageDelayMs)",
                "a fake's behaviour: the test server's time per image, so a download is under way when the test " +
                    "pauses or deletes it; the test waits on states",
            ),
            Allowed(
                "$TESTS/download/DownloadQueueStatesTest.kt",
                "delay(chapter.id % 4)",
                "a fake's behaviour: jitter between queued chapters, so some are queued just as the previous run " +
                    "finds nothing left; the test waits on states",
            ),
            Allowed(
                "$TESTS/browser/CefCookieSameSiteLiveTest.kt",
                "Thread.sleep(250)",
                "a live test, run by hand against real Chromium: watches the cookie for 2 s, as Chromium applies " +
                    "a copy on its own thread at no time it reports and could rewrite it later",
            ),
            Allowed(
                "$TESTS/ui/SmokeChecks.kt",
                "Thread.sleep(pauseMillis)",
                "settle(): the screen checks and the button crawl open hundreds of screens that share no " +
                    "\"loaded\" state, and waitForIdle does not see the screen models' dispatchers; kept as it is, " +
                    "as a change could move the crawl's signatures and baselines",
            ),
            Allowed(
                "$TESTS/ui/ButtonCrawlTest.kt",
                "Thread.sleep(250)",
                "measure(): what it measures is time, a click's effects sampled until they hold still; kept as " +
                    "it is, as a change could move the crawl's signatures and baselines",
            ),
            Allowed(
                "$TESTS/ui/EndToEndReadTest.kt",
                "Thread.sleep(settleMillis)",
                "manual run against a real store: a pause before a screenshot only, so covers and pages draw; " +
                    "the steps wait on conditions",
            ),
            Allowed(
                "$TESTS/ui/EndToEndDownloadTest.kt",
                "Thread.sleep(settleMillis)",
                "manual run against a real store: a pause before a screenshot only; the steps wait on conditions",
            ),
        )
    }
}

/** Finds the real-time pauses [ThreadSleepScanTest] forbids; returns their 1-based lines. */
internal object SleepScanner {
    private val SLEEP = Regex(
        """\b(?:Thread|SystemClock)\s*\.\s*sleep\s*\(|\b(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\s*\.\s*sleep\s*\(""",
    )

    /** `delay(`, plain or as `kotlinx.coroutines.delay(`, but not another object's `x.delay(` nor `fun delay(`. */
    private val DELAY = Regex("""(?:(?<![\w.])|(?<=\bcoroutines\.))delay\s*\(""")
    private val DECLARATION = Regex("""\bfun\s+$""")

    private val RUN_BLOCKING = Regex("""\brunBlocking\s*(?:<[^>]*>)?\s*(?:\(.*\))?\s*$""")
    private val RUN_TEST = Regex("""\brunTest\s*(?:\(.*\))?\s*$""")

    /** Blocks that run in the body around them, so a delay in them pauses that body. */
    private val SAME_BODY = Regex(
        """(?:\b(?:withTimeout|withTimeoutOrNull|withContext|coroutineScope|supervisorScope|repeat|forEach|forEachIndexed|onEach|also|apply|run|let|with|use)\s*(?:<[^>]*>)?\s*(?:\(.*\))?|\b(?:if|while|for|catch|when)\s*\(.*\)|\b(?:try|finally|else|do)|->)\s*$""",
    )

    /** A backticked name on one line, as a test's name: its apostrophes are not char literals. */
    private val BACKTICKED = Regex("""`[^`\n]*`""")

    /** A function that takes a `suspend` block: its name, then its parameter list from `(`. */
    private val SUSPEND_BLOCK_FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

    /**
     * The test helpers in [text] that take a `suspend` lambda and run it under `runBlocking`, so a
     * delay in the block they are given pauses the test's own thread.
     */
    fun blockingWrappers(text: String): Set<String> {
        val m = masked(text)
        return SUSPEND_BLOCK_FUN.findAll(m).mapNotNull { match ->
            val open = match.range.last
            val close = SourceScan.closing(m, open)
            val params = m.substring(open + 1, close)
            if (!Regex("""\bsuspend\b[^,]*->""").containsMatchIn(params)) return@mapNotNull null
            // The body: a block, or an expression body up to the end of its line
            val rest = m.substring(close + 1)
            val brace = rest.indexOf('{')
            val lineEnd = rest.indexOf('\n').let { if (it < 0) rest.length else it }
            val body = when {
                brace < 0 -> rest.substring(0, lineEnd)
                Regex("""^[^{]*=""").containsMatchIn(rest.substring(0, brace)) && lineEnd < brace -> rest.substring(0, lineEnd)
                else -> rest.substring(0, SourceScan.closing(rest, brace) + 1)
            }
            val runs = Regex("""\brunBlocking\b[^{]*\{([^{}]*)""").findAll(body).any { b ->
                Regex("""\b(\w+)\s*\(""").findAll(b.groupValues[1]).any { call -> Regex("""\b${call.groupValues[1]}\s*:\s*suspend\b""").containsMatchIn(params) }
            }
            if (runs) match.groupValues[1] else null
        }.toSet()
    }

    // The masking reads ' as the start of a char literal; a test named `the library's list`
    // would hide the code after it. Same length, so offsets and lines stay
    private fun masked(text: String) = SourceScan.Source("", BACKTICKED.replace(text) { "`" + "x".repeat(it.value.length - 2) + "`" }).masked

    fun lines(text: String, wrappers: Set<String> = blockingWrappers(text)): List<Int> {
        val names = BACKTICKED.replace(text) { "`" + "x".repeat(it.value.length - 2) + "`" }
        val source = SourceScan.Source("", names)
        val m = source.masked
        val wrapper = wrappers.takeIf { it.isNotEmpty() }
            ?.let { Regex("""(?<!\bfun\s)\b(?:${it.joinToString("|") { w -> Regex.escape(w) }})\s*(?:<[^>]*>)?\s*(?:\(.*\))?\s*$""") }
        val hits = sortedSetOf<Int>()
        SLEEP.findAll(m).forEach { hits += source.lineOf(it.range.first) }
        DELAY.findAll(m).forEach { match ->
            val at = match.range.first
            if (DECLARATION.containsMatchIn(m.substring(maxOf(0, at - 8), at))) return@forEach
            if (pausesRunBlockingBody(m, at, wrapper)) hits += source.lineOf(at)
        }
        return hits.toList()
    }

    /** Whether the delay at [offset] runs in a `runBlocking` body, through blocks of the same body only. */
    private fun pausesRunBlockingBody(m: String, offset: Int, wrapper: Regex?): Boolean {
        for (brace in SourceScan.enclosingBraces(m, offset)) {
            val header = SourceScan.header(m, brace)
            when {
                RUN_BLOCKING.containsMatchIn(header) -> return true
                wrapper?.containsMatchIn(header) == true -> return true
                RUN_TEST.containsMatchIn(header) -> return false
                SAME_BODY.containsMatchIn(header) -> continue
                // A function, a class, or a lambda something else runs (a fake, a launched coroutine)
                else -> return false
            }
        }
        return false
    }
}
