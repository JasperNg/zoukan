package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class HardCodedStringsTest {
    private val root = File("src/main/kotlin/zoukan/desktop")

    /** The screens whose labels must come from the string resources. Other areas add their folders here. */
    private val scanned = listOf(
        "ui/library",
        "ui/browse",
        "ui/category",
        "ui/whatsnew",
        "ui/stats",
        "ui/migration",
        "migration/MigrateManga.kt",
    )

    private val patterns = listOf(
        Regex("""Text\("[^"$]*\p{L}"""),
        Regex("""(confirmLabel|acceptText|dismissText|label) = "[^"$]*\p{L}"""),
        Regex("""-> "[A-Z][a-z]"""),
        Regex(""""\$\{\w+\}[dhm]"|"0m""""),
    )

    /** Symbols and example text that are not words to translate. */
    private val allowed = listOf("https://…", "•", "18+")

    @Test
    fun `library and browse screens take their labels from the string resources`() {
        val hits = scanned.map { File(root, it) }
            .flatMap { f -> if (f.isFile) listOf(f) else f.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapIndexedNotNull null
                    if (patterns.none { it.containsMatchIn(line) } || allowed.any { it in line }) {
                        null
                    } else {
                        "${file.relativeTo(root)}:${i + 1}: $trimmed"
                    }
                }
            }
        hits shouldBe emptyList()
    }
}
