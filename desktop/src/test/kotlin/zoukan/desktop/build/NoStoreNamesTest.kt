package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The app names no third-party extension store: a user who does not know one cannot find it from
 * the app (its buttons, strings, help text, defaults, constants or migrations). Only the tests and
 * their tooling name the real stores they download from (`SampleStores`).
 */
class NoStoreNamesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".github/workflows").isDirectory }

    private val storeNames = Regex("keiyou?shi|yuzono", RegexOption.IGNORE_CASE)

    /** An extension store's index on GitHub: `raw.githubusercontent.com/<owner>/<something>extensions|repo`. */
    private val storeUrl = Regex("""githubusercontent\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]*(extensions|repo)""")

    /** `<module>/src/<set>/` or `<group>/<module>/src/<set>/`, for a source set that is not a test one. */
    private val shippedSourceSet = Regex("""^(?:[^/]+/)?[^/]+/src/([^/]+)/""")

    private fun tracked(): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        withClue("git ls-files failed: $out") { process.waitFor() shouldBe 0 }
        return out.split('\u0000').filter(String::isNotEmpty)
    }

    private fun shipped(path: String): Boolean {
        if (path.startsWith("desktop/packaging/")) return !path.startsWith("desktop/packaging/check/")
        val set = shippedSourceSet.find(path)?.groupValues?.get(1) ?: return false
        if (set.contains("test", ignoreCase = true) || set == "androidTest") return false
        // Only real modules: the folder holding src/ has a build script
        val module = path.substringBefore("/src/")
        return File(root, "$module/build.gradle.kts").isFile
    }

    private fun isText(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(1024)
        val n = input.read(head)
        n <= 0 || head.take(n).none { it == 0.toByte() }
    }

    @Test
    fun `no shipped file names or links an extension store`() {
        val files = tracked().filter(::shipped).map { File(root, it) }.filter { it.isFile && isText(it) }
        withClue("found no shipped files under $root") { files.shouldNotBeEmpty() }
        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (storeNames.containsMatchIn(line) || storeUrl.containsMatchIn(line)) {
                    "${file.relativeTo(root)}:${i + 1}: ${line.trim().take(160)}"
                } else {
                    null
                }
            }
        }
        withClue("store names or URLs in the app (keep them in the tests)") { hits.shouldBeEmpty() }
    }
}
