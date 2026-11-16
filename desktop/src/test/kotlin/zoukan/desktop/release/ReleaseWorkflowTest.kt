package zoukan.desktop.release

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/** Text checks on the workflow files that build and publish the installers. */
class ReleaseWorkflowTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".github/workflows").isDirectory }

    private fun text(path: String) = File(root, path).readText()

    private fun workflowFiles() = File(root, ".github/workflows").listFiles { f -> f.extension == "yml" }!!.sortedBy { it.name }

    @Test
    fun `the runtime and WiX downloads are checked against a pinned digest`() {
        val problems = mutableListOf<String>()
        val hex = Regex("[0-9a-f]{128}")
        for (file in listOf(".github/workflows/ci.yml", ".github/workflows/desktop-build.yml")) {
            val content = text(file)
            val lines = content.lines()
            val names = Regex("""-o "[^"]*/(jbrsdk-[^"]+)"""").findAll(content).map { it.groupValues[1] }.toSet()
            for (name in names) {
                val pinned = lines.any { name in it && hex.containsMatchIn(it) && !it.contains("curl") }
                val checked = lines.any { "sha512sum -c" in it || "shasum -a 512 -c" in it }
                if (!pinned || !checked) problems += "$file: $name is downloaded without a pinned SHA-512"
            }
        }
        val gradle = text("desktop/packaging.gradle.kts")
        val wix = gradle.substringAfter("val wixSha256").substringBefore("val packageMsi")
        if (!Regex("[0-9a-f]{64}").containsMatchIn(wix) || "MessageDigest.getInstance(\"SHA-256\")" !in wix) {
            problems += "desktop/packaging.gradle.kts: downloadWix downloads wix314-binaries.zip without a pinned SHA-256"
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `every action is pinned to a full commit SHA`() {
        val pinned = Regex("""^[^@\s]+@[0-9a-f]{40}\s+#\s*v\d+(\.\d+)*\s*$""")
        val usesLine = Regex("""^\s*-?\s*uses:\s*(.*)$""")
        val problems = mutableListOf<String>()
        for (file in workflowFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val ref = usesLine.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                if (ref.startsWith("./")) return@forEachIndexed
                if (!pinned.matches(ref)) problems += "${file.name}:${index + 1} $ref"
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `the release waits for the tests of the commit it ships`() {
        val ci = text(".github/workflows/ci.yml")
        val build = text(".github/workflows/desktop-build.yml")
        val problems = mutableListOf<String>()
        if (!Regex("""(?m)^on:(?:\n(?:[ \t].*)?)*?^ {2}workflow_call:""").containsMatchIn(ci)) {
            problems += "ci.yml: no workflow_call trigger, so the release workflow cannot run it"
        }
        if (!Regex("""(?m)^ {2}\w+:\n(?: {4}.*\n)*? {4}uses: \./\.github/workflows/ci\.yml\s*$""").containsMatchIn(build)) {
            problems += "desktop-build.yml: no job that calls ./.github/workflows/ci.yml"
        }
        val callerJob = Regex("""(?m)^ {2}(\w+):\n(?: {4}.*\n)*? {4}uses: \./\.github/workflows/ci\.yml""")
            .find(build)?.groupValues?.get(1)
        val releaseNeeds = Regex("""(?m)^ {2}release:\n(?: {4}.*\n)*? {4}needs: \[([^\]]*)]""")
            .find(build)?.groupValues?.get(1).orEmpty().split(",").map { it.trim() }
        if (callerJob == null || callerJob !in releaseNeeds) {
            problems += "desktop-build.yml: the release job does not need the job that calls ci.yml (needs: $releaseNeeds)"
        }
        if (Regex("""cancel-in-progress:\s*true\s*$""", RegexOption.MULTILINE).containsMatchIn(ci)) {
            problems += "ci.yml: cancel-in-progress is true, so a later merge cancels the master run of an earlier one"
        }
        val group = Regex("""(?m)^ {2}group:\s*(.*)$""").find(ci)?.groupValues?.get(1).orEmpty()
        if ("github.workflow" !in group) {
            problems += "ci.yml: the concurrency group has no github.workflow, so the called run shares a group with the stand-alone run"
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `the update bots take no policy from another repository`() {
        val file = File(root, "renovate.json")
        if (!file.isFile) return
        val remote = Regex(""""github>[^"]*"""").findAll(file.readText()).map { it.value }.toList()
        remote.shouldBeEmpty()
    }

    private fun job(workflow: String, name: String): String {
        val start = Regex("(?m)^ {2}$name:\\s*$").find(workflow)?.range?.first ?: return ""
        val next = Regex("(?m)^ {2}[\\w-]+:\\s*$").findAll(workflow).map { it.range.first }.firstOrNull { it > start }
        return workflow.substring(start, next ?: workflow.length)
    }

    @Test
    fun `the release jobs self-test the installers they publish`() {
        val build = text(".github/workflows/desktop-build.yml")
        val problems = mutableListOf<String>()
        if (!Regex("""install-check-windows\.ps1\s+-SkipBuild""").containsMatchIn(job(build, "windows"))) {
            problems += "windows job does not run install-check-windows.ps1 -SkipBuild"
        }
        if (!Regex("""install-check-macos\.sh\s+--skip-build""").containsMatchIn(job(build, "macos"))) {
            problems += "macos job does not run install-check-macos.sh --skip-build"
        }
        if ("""desktop\build\packaging\app\Zoukan\Zoukan.exe""" in build) {
            problems += "a step starts the app folder's Zoukan.exe instead of the installed app"
        }
        if ("desktop/build/packaging/app/Zoukan.app/Contents/MacOS/Zoukan" in build) {
            problems += "a step starts the app folder's macOS binary instead of the app from the DMG"
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `release docs and comments match the workflow`() {
        val problems = mutableListOf<String>()
        val releasing = text("desktop/RELEASING.md")
        for (phrase in listOf("To release by hand", "a signed build, say", "two quick merges make two releases")) {
            if (phrase in releasing) problems += "desktop/RELEASING.md still says \"$phrase\""
        }
        val tableAt = releasing.indexOf("### Windows (Authenticode")
        val headingAt = releasing.indexOf("not used for published releases")
        if (headingAt < 0 || tableAt < 0 || headingAt > tableAt) {
            problems += "desktop/RELEASING.md has no heading \"not used for published releases\" above the signing tables"
        }
        val build = text(".github/workflows/desktop-build.yml")
        Regex("""(?m)^\s*runs-on:\s*(\S+)""").findAll(build).map { it.groupValues[1] }.distinct().forEach {
            if (it !in releasing) problems += "desktop/RELEASING.md does not name the runner image $it"
        }
        val banned = mapOf(
            ".github/workflows/ci.yml" to listOf("checked locally before each merge"),
            "desktop/build.gradle.kts" to listOf("once that task exists", "once it exists", "task yet"),
            "desktop/packaging/check/nightly-windows.ps1" to listOf("once that task exists", "once it exists", "task yet"),
            ".github/workflows/desktop-build.yml" to listOf("the one inside the DMG"),
        )
        for ((file, phrases) in banned) {
            val content = text(file)
            for (phrase in phrases) if (phrase in content) problems += "$file still says \"$phrase\""
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `packaging changes build the installers on pull requests`() {
        val build = text(".github/workflows/desktop-build.yml")
        val paths = Regex("""(?m)^ {2}pull_request:\s*\n {4}paths:\s*(\[[^\]]*]|(?:\n {6}- .*)+)""")
            .find(build)?.groupValues?.get(1).orEmpty()
        val missing = listOf(".github/workflows/desktop-build.yml", "desktop/packaging/**", "desktop/packaging.gradle.kts")
            .filter { it !in paths }
        missing.shouldBeEmpty()
    }
}
