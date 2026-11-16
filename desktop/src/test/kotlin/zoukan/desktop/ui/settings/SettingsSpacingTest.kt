package zoukan.desktop.ui.settings

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/** Keeps the settings row inset and page width in one shared place instead of typed by hand. */
class SettingsSpacingTest {

    private val dir = File("src/main/kotlin/zoukan/desktop/ui/settings")

    /** Every source line outside comments, as "file:line: text". */
    private fun codeLines(): List<Pair<String, String>> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                    null
                } else {
                    "${f.name}:${i + 1}" to line.trim()
                }
            }
        }
        .toList()

    private fun hits(regex: Regex) = codeLines().filter { (_, line) -> regex.containsMatchIn(line) }
        .map { (where, line) -> "$where: $line" }

    @Test
    fun `settings rows use the shared row inset`() {
        hits(Regex("""horizontal\s*=\s*24\.dp|start\s*=\s*24\.dp""")).shouldBeEmpty()
    }

    @Test
    fun `settings pages use the shared page width`() {
        hits(Regex("""widthIn\(max\s*=\s*900\.dp\)""")).shouldBeEmpty()
    }
}
