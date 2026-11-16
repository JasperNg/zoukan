package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
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

    @Test
    fun `the Zoukan catalog leaves the versions of upstream libraries to the upstream catalogs`() {
        val upstream = listOf("libs", "androidx", "kotlinx", "compose", "sy").map { text("gradle/$it.versions.toml") }
        val zoukan = text("gradle/zoukan.versions.toml")

        // "group:name:version" or { module = "group:name" ... }
        fun modules(toml: String) =
            Regex("""(?m)^[\w-]+\s*=\s*(?:"([\w.-]+:[\w.-]+)(?::[^"]+)?"|\{\s*module\s*=\s*"([\w.-]+:[\w.-]+)")""")
                .findAll(toml).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()

        val upstreamModules = upstream.flatMap(::modules).toSet()
        val upstreamGroups = setOf("com.squareup.okhttp3", "app.cash.sqldelight", "org.jetbrains.kotlinx")
        val pinned = Regex("""(?m)^([\w-]+)\s*=\s*"([\w.-]+):([\w.-]+):[^"]+"""").findAll(zoukan)
            .filter { m -> m.groupValues[2] in upstreamGroups || "${m.groupValues[2]}:${m.groupValues[3]}" in upstreamModules }
            .map { it.groupValues[1] }.toList()
        withClue("Zoukan catalog entries that pin a version an upstream catalog owns") { pinned.shouldBeEmpty() }
    }
}
