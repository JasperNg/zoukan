package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.matchers.shouldBe
import okio.sink
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Phase 3 gate: every fixture survives a restore and backup on desktop with no field lost.
 *
 * Fixtures are the synthetic full-field backups plus every `.tachibk` and `.proto.gz` in
 * `src/test/resources/backups/` (real exports from Komikku and other apps). Each runs
 * `zoukan backup check` in its own JVM, because the desktop runtime starts once per process. The
 * Markdown reports go to `build/reports/backup/`.
 */
class BackupRoundTripTest {

    @TempDir
    lateinit var temp: File

    @TestFactory
    fun `fixtures round-trip with no field lost`(): List<DynamicTest> {
        val synthetic = listOf(false, true).map { legacy ->
            DynamicTest.dynamicTest(if (legacy) "synthetic, legacy fields" else "synthetic, every field") {
                val file = File(temp, if (legacy) "synthetic-legacy.tachibk" else "synthetic.tachibk")
                file.sink().use { BackupCodec.write(SyntheticBackup.create(legacy), it) }
                check(file)
            }
        }
        val real = fixtureDir.listFiles { f -> f.name.endsWith(".tachibk") || f.name.endsWith(".proto.gz") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> DynamicTest.dynamicTest(file.name) { check(file) } }
        return synthetic + real
    }

    private fun check(file: File) {
        val reports = File("build/reports/backup").apply { mkdirs() }
        val report = File(reports, "${file.name.substringBefore('.')}.md")
        val java = File(System.getProperty("java.home"), "bin/java").path
        // The class path is longer than Windows' 32K command line, so it goes in an argument file.
        // Forward slashes, because a quoted argument file value treats backslashes as escapes.
        val classPath = System.getProperty("java.class.path").replace('\\', '/')
        val argFile = File(temp, "classpath.args").apply { writeText("-cp \"$classPath\"\n") }
        val process = ProcessBuilder(
            java,
            "-Djava.awt.headless=true",
            "@${argFile.absolutePath}",
            "zoukan.desktop.MainKt",
            "backup",
            "check",
            file.absolutePath,
            "--report",
            report.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(File(temp, "${file.name}.log"))
            .start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        val log = File(temp, "${file.name}.log").readText()
        if (!finished || process.exitValue() != 0) println(log)
        finished shouldBe true
        process.exitValue() shouldBe 0
    }

    private val fixtureDir: File
        get() = File(javaClass.getResource("/backups/README.md")!!.toURI()).parentFile
}
