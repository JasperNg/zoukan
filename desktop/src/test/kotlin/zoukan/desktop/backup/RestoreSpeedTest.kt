package zoukan.desktop.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.db.DesktopSqliteDriver
import zoukan.desktop.testing.TestHome
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Restores a library-sized backup ([LargeBackup]) with [RestoreBenchmark], in its own JVM because
 * the runtime starts once per process, with the library watched as an open Library tab does.
 *
 * Besides the result, it checks what made restores slow before, by counts rather than by time, so
 * it holds on slow machines: the database thread keeps one connection for the whole restore
 * instead of opening one per entry, and a watched library hears of the changes a few times
 * instead of after every entry.
 *
 * Gradle properties, for timing by hand: `-Pzoukan.restoreBench=<entries>,<chapters>`,
 * `-Pzoukan.restoreBench.dir=<work dir>` (keeps the timings and content dumps) and
 * `-Pzoukan.restoreBench.jfr=<file.jfr>`. With a work dir, the library is watched only when the
 * dir holds an `observe` file.
 */
class RestoreSpeedTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a large restore is complete, repeatable and fast`() {
        val (entries, chapters) = (System.getProperty("zoukan.restoreBench") ?: "300,60").split(",").map { it.trim().toInt() }
        val work = System.getProperty("zoukan.restoreBench.dir")?.let(::File)
            ?: File(temp, "bench").apply { mkdirs() }.also { File(it, "observe").createNewFile() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classPath = System.getProperty("java.class.path").replace('\\', '/')
        val argFile = File(temp, "classpath.args").apply { writeText("-cp \"$classPath\"\n") }
        val jfr = System.getProperty("zoukan.restoreBench.jfr")?.let { listOf("-XX:StartFlightRecording=filename=$it,settings=profile") }.orEmpty()
        val log = File(temp, "bench.log")
        val process = ProcessBuilder(
            listOf(java, "-Djava.awt.headless=true", "-Xmx1g") + TestHome.childJvmArguments() + jfr +
                listOf("@${argFile.absolutePath}", "zoukan.desktop.backup.RestoreBenchmark", work.absolutePath, "$entries", "$chapters"),
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            // Where it is stuck, for the log
            ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/jcmd").path) + TestHome.jdkToolArguments() + listOf("${process.pid()}", "Thread.print"))
                .redirectErrorStream(true)
                .redirectOutput(File(work, "threads.txt"))
                .start()
                .waitFor(30, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
        println(log.readText().lines().filter { !it.contains(" I/") && !it.contains(" D/") }.takeLast(40).joinToString("\n"))
        finished shouldBe true
        process.exitValue() shouldBe 0

        // Nothing failed and nothing was lost on the round trip
        File(work, "result.txt").readText() shouldBe ""
        // Restoring the same backup again changes nothing
        File(work, "content-existing.txt").readText() shouldBe File(work, "content-empty.txt").readText()

        val timings = File(work, "timings.txt").readLines().associate { line -> line.substringBefore(' ') to line.substringAfter(' ').toLong() }
        for (pass in listOf("empty", "existing")) {
            // One connection for the whole restore (a few if it pauses for longer than the idle time)
            (timings.getValue("$pass.connections-opened") <= 3) shouldBe true
            if (File(work, "observe").exists()) {
                // The first list, a refresh every two seconds, one at the end and a few for the
                // writes around the entries; not one per entry
                val allowed = 5 + timings.getValue("$pass.total") / DesktopSqliteDriver.NOTIFY_INTERVAL_MS
                (timings.getValue("$pass.library-emissions") <= allowed) shouldBe true
            }
            // Generous: before these fixes the default size took about 10 times as long
            (timings.getValue("$pass.total") < 60_000) shouldBe true
        }
    }
}
