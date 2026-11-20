package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
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
        // In an upstream group, but in no upstream catalog: the build's own coverage plugin
        val ownInUpstreamGroup = setOf("org.jetbrains.kotlinx:kover-gradle-plugin")
        val pinned = Regex("""(?m)^([\w-]+)\s*=\s*"([\w.-]+):([\w.-]+):[^"]+"""").findAll(zoukan)
            .filter { m -> "${m.groupValues[2]}:${m.groupValues[3]}" !in ownInUpstreamGroup }
            .filter { m -> m.groupValues[2] in upstreamGroups || "${m.groupValues[2]}:${m.groupValues[3]}" in upstreamModules }
            .map { it.groupValues[1] }.toList()
        withClue("Zoukan catalog entries that pin a version an upstream catalog owns") { pinned.shouldBeEmpty() }
    }

    @Test
    fun `the JVM modules take their Java version from one convention`() {
        for (module in listOf("desktop", "extension-jvm", "android-compat")) {
            val build = text("$module/build.gradle.kts")
            withClue("$module sets its own Java version") {
                build shouldNotContain "JavaVersion.VERSION_"
                build shouldNotContain "JvmTarget.JVM_"
                build shouldContain "id(\"zoukan.jvm\")"
            }
        }
        withClue("configure-on-demand is on") {
            text("gradle.properties") shouldNotContain "org.gradle.configureondemand=true"
        }
    }

    @Test
    fun `wiringTest checks whether the run was narrowed with --tests in either form`() {
        val build = text("desktop/build.gradle.kts")
        // Gradle takes a task's filter as `--tests Foo` and as `--tests=Foo`
        withClue("narrowsTests must see both forms of the filter") {
            build shouldContain """fun narrowsTests(args: List<String>): Boolean = args.any { it == "--tests" || it.startsWith("--tests=") }"""
            build shouldContain "val testsNarrowedOnCommandLine = gradle.startParameter.taskRequests.any { narrowsTests(it.args) }"
        }
        withClue("a check of the command line that does not go through narrowsTests") {
            Regex("""taskRequests\.any \{(?! narrowsTests\(it\.args\) \})""").findAll(build).map { it.value }.toList().shouldBeEmpty()
            build shouldNotContain "\"--tests\" in"
        }
        val wiring = build.substringAfter("val wiringTest = tasks.register<Test>(\"wiringTest\")", "").substringBefore("\n}")
        withClue("wiringTest's check of its classes") { wiring shouldContain "val narrowedOnCommandLine = testsNarrowedOnCommandLine" }
    }

    @Test
    fun `shared KMP modules use the AGP 9 Android KMP library plugin`() {
        val kmpConvention = text("buildSrc/src/main/kotlin/mihon.library.kmp.gradle.kts")
        kmpConvention shouldContain "com.android.kotlin.multiplatform.library"
        kmpConvention shouldNotContain "com.android.library"
        kmpConvention shouldNotContain """id("mihon.library")"""

        val kmpModules = listOf(
            "backup",
            "core/archive",
            "core/common",
            "data",
            "domain",
            "source-api",
            "source-local",
            "i18n",
            "i18n-kmk",
            "i18n-sy",
            "i18n-desktop",
        )
        for (module in kmpModules) {
            val build = text("$module/build.gradle.kts")
            val usesKmpPlugin = "id(\"mihon.library.kmp\")" in build ||
                "id(\"com.android.kotlin.multiplatform.library\")" in build
            withClue("$module does not use mihon.library.kmp or com.android.kotlin.multiplatform.library") {
                usesKmpPlugin shouldBe true
            }
            withClue("$module mixes com.android.library with kotlin(\"multiplatform\")") {
                build shouldNotContain "com.android.library"
                build shouldNotContain """id("mihon.library")"""
                build shouldNotContain "androidTarget()"
            }
        }
    }
}
