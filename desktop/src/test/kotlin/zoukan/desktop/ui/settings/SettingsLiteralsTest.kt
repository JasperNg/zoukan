package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Keeps user-visible letters in Settings coming from string resources. */
class SettingsLiteralsTest {

    private val dir = File("src/main/kotlin/zoukan/desktop/ui/settings")

    /** Every source line outside comments, with its file name and 1-based number. */
    private fun codeLines(): List<Triple<String, Int, String>> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f.name, i + 1, line)
            }
        }
        .toList()

    // Technical texts kept on purpose: cookie names and URL-shaped hints are not sentences
    private val allowed = listOf("ipb_member_id", "ipb_pass_hash", "komikku://", "https://")

    @Test
    fun `settings text comes from string resources`() {
        val literal = Regex("""Text\("(\p{L}[^"]*)""")
        val hits = codeLines().filter { (_, _, line) ->
            literal.findAll(line).any { m -> allowed.none { m.groupValues[1].startsWith(it) } }
        }.map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }

    @Test
    fun `the date format list labels its default from a resource`() {
        val hits = codeLines().filter { (_, _, line) -> line.contains("\"Default\"") }
            .map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }

    @Test
    fun `sign-in errors do not show raw exception text`() {
        val raw = Regex("""error\s*=\s*(e|it)\.message""")
        val hits = codeLines().filter { (_, _, line) -> raw.containsMatchIn(line) }
            .map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }
}
