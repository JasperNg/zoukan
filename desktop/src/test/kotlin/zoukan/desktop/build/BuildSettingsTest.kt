package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

class BuildSettingsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".github/workflows").isDirectory }

    private fun text(path: String) = File(root, path).readText()

    @Test
    fun `every downloaded dependency is checked against a pinned checksum`() {
        val settings = text("settings.gradle.kts")
        // Each JitPack repository is followed by a content filter
        Regex("""maven\(url = "https://www\.jitpack\.io"\)(\s*\{\s*content\s*\{)?""").findAll(settings).toList()
            .shouldNotBeEmpty()
            .forEach { withClue("JitPack is asked for every group: ${it.value}") { it.groupValues[1].shouldNotBeEmpty() } }
        val metadata = File(root, "gradle/verification-metadata.xml")
        withClue("no dependency verification") { metadata.isFile shouldBe true }
        metadata.readText() shouldContain "<verify-metadata>true</verify-metadata>"
        metadata.readText() shouldContain "<sha256 value="
        withClue("the Gradle wrapper download has no pinned checksum") {
            Regex("""(?m)^distributionSha256Sum=[0-9a-f]{64}$""")
                .containsMatchIn(text("gradle/wrapper/gradle-wrapper.properties")) shouldBe true
        }
    }
}
