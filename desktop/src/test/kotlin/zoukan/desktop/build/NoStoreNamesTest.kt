package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The app names no third-party extension store: a user who does not know one cannot find it from
 * the app (its buttons, strings, help text, defaults or constants). Only the tests and their
 * tooling name the real stores they download from (`SampleStores`).
 */
class NoStoreNamesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".github/workflows").isDirectory }

    private val names = Regex("keiyou?shi|yuzono", RegexOption.IGNORE_CASE)
    private val extensions = setOf("kt", "java", "xml", "json", "properties", "txt", "md")

    /** Source sets that ship: every `src/<set>` of every module except the test ones. */
    private fun shippedSourceSets(): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" && !it.name.startsWith(".") }
        .filter { it.isDirectory && it.parentFile?.name == "src" }
        .filterNot { it.name.contains("test", ignoreCase = true) || it.name == "androidTest" }
        .toList()

    @Test
    fun `no shipped source set names an extension store`() {
        val sets = shippedSourceSets()
        withClue("found no source sets under $root") { sets.shouldNotBeEmpty() }
        val hits = sets.flatMap { set ->
            set.walkTopDown()
                .filter { it.isFile && it.extension in extensions }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        if (names.containsMatchIn(line)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                    }
                }
                .toList()
        }
        withClue("store names in the app's code (keep them in the tests)") { hits.shouldBeEmpty() }
    }
}
