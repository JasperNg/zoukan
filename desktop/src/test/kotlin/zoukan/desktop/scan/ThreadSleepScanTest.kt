package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

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
                "$TESTS/testing/Await.kt",
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
