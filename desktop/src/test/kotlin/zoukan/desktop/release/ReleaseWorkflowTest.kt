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
}
