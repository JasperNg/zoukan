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
        for (workflow in workflowFiles()) {
            val file = ".github/workflows/${workflow.name}"
            val content = workflow.readText()
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
    fun `the PR gate is the fast set and the long checks run on Windows`() {
        val ci = text(".github/workflows/ci.yml")
        val problems = mutableListOf<String>()
        val jobs = Regex("""(?m)^ {2}([\w-]+):\s*$""").findAll(ci.substringAfter("\njobs:\n")).map { it.groupValues[1] }.toList()
        val gateNeeds = Regex("""(?m)^ {4}needs: \[([^\]]*)]""").find(job(ci, "pr-gate"))?.groupValues?.get(1).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val missing = jobs - "pr-gate" - gateNeeds.toSet()
        if (jobs.isEmpty() || gateNeeds.isEmpty()) problems += "ci.yml: could not read the jobs or the PR gate's needs"
        if (missing.isNotEmpty()) problems += "ci.yml: PR gate does not need $missing, so they do not gate a merge or a release"
        // The long checks run on Windows before each merge (desktop/RELEASING.md), not on GitHub
        for (task in listOf(":desktop:journeyTest", ":desktop:buttonCrawlTest", ":desktop:extensionWiringTest", ":desktop:nightlyCheck")) {
            if (task in ci) problems += "ci.yml runs $task, a long check for Windows"
        }
        val nightly = text(".github/workflows/nightly.yml")
        val nightlyOn = nightly.substringAfter("\non:\n").substringBefore("\n\n")
        for (trigger in listOf("pull_request:", "push:")) {
            if (trigger in nightlyOn) problems += "nightly.yml starts on $trigger; it runs on its schedule and by hand"
        }
        if (!Regex("""(?m)^ {2}schedule:\n {4}- cron: '[^']+'""").containsMatchIn(nightlyOn)) problems += "nightly.yml has no schedule"
        if ("workflow_dispatch:" !in nightlyOn) problems += "nightly.yml cannot be started by hand"
        // A fork inherits the schedule: it must not run the long checks on the fork's minutes
        for (name in listOf("nightly", "screenshots")) {
            if ("github.event_name != 'schedule' || !github.event.repository.fork" !in job(nightly, name)) {
                problems += "nightly.yml: the $name job runs on the schedule in forks too"
            }
        }
        // The journeys on pull requests that change their hotspots, in a workflow of their own
        val journeys = text(".github/workflows/journeys.yml")
        if (":desktop:journeyTest" !in journeys) problems += "journeys.yml does not run :desktop:journeyTest"
        if (!Regex("""(?m)^ {2}pull_request:\n {4}paths:\n""").containsMatchIn(journeys)) {
            problems += "journeys.yml does not start on pull requests filtered by paths, so it slows every pull request"
        }
        if (!Regex("""(?m)^\s*runs-on:\s*windows-""").containsMatchIn(journeys)) problems += "journeys.yml does not run on Windows"
        // The whole unit tier on Windows and macOS on every push to master
        val unitTier = ":desktop:test :extension-jvm:test :domain:desktopTest :android-compat:test :backup:desktopTest"
        val filesOs = job(ci, "files-os")
        if (unitTier !in filesOs || "macos-" !in filesOs || "windows-" !in filesOs) {
            problems += "ci.yml: files-os does not run the whole unit tier on Windows and macOS"
        }
        val unit = job(ci, "unit")
        if (unitTier !in unit) problems += "ci.yml: the unit job does not run the whole unit tier on Linux"
        // The coverage of changed lines is information only: it must not gate a merge
        for (step in listOf("name: Coverage report", "name: Coverage of changed lines")) {
            val body = unit.substringAfter(step, "").substringBefore("\n      - ")
            if (body.isEmpty()) {
                problems += "ci.yml: the unit job has no step \"$step\""
            } else if ("continue-on-error: true" !in body) {
                problems += "ci.yml: \"$step\" can fail the unit job"
            }
        }
        // The report compares with the merge base, so a pull request needs the whole history. In an
        // expression a number 0 is falsy: `cond && 0 || 1` always gives 1, a shallow checkout
        val fetchDepth = Regex("""(?m)^\s*fetch-depth:\s*(.+)$""").find(unit)?.groupValues?.get(1)?.trim()
        if (fetchDepth != "\${{ github.event_name == 'pull_request' && '0' || '1' }}") {
            problems += "ci.yml: the unit job's checkout does not fetch the whole history on pull requests (fetch-depth: $fetchDepth)"
        }
        for (workflow in workflowFiles()) {
            if (Regex("""&&\s*(0|false|''|"")\s*\|\|""").containsMatchIn(workflow.readText())) {
                problems += "${workflow.name}: `cond && <falsy> || x` always gives x"
            }
        }
        // A changed-line report that cannot be made must say so in the summary, not only in the log
        val changedLines = unit.substringAfter("name: Coverage of changed lines", "").substringBefore("\n      - ")
        if ("::warning" !in changedLines || "Not computed" !in changedLines) {
            problems += "ci.yml: \"Coverage of changed lines\" does not warn in the job summary when it cannot make the report"
        }
        if (":app:testDebugUnitTest" !in job(ci, "android-compile")) problems += "ci.yml does not run the Android app's unit tests"
        // The record of the long checks, checked before a push to master
        if (":desktop:verifyLongChecks" !in text("desktop/TESTING.md")) problems += "desktop/TESTING.md does not describe :desktop:verifyLongChecks"
        if ("tasks.register(\"verifyLongChecks\")" !in text("desktop/build.gradle.kts")) {
            problems += "desktop/build.gradle.kts has no verifyLongChecks task"
        }
        val command = ":desktop:longChecks --continue"
        for (doc in listOf("desktop/RELEASING.md", "desktop/TESTING.md")) {
            if (command !in text(doc)) problems += "$doc does not give the pre-merge command `$command`"
        }
        val longChecks = text("desktop/build.gradle.kts").substringAfter("val longChecks by tasks.registering", "").substringBefore("\n}")
        for (part in listOf("nightlyCheck", "buttonCrawlTest")) {
            if (part !in longChecks) problems += "desktop/build.gradle.kts: longChecks does not run $part"
        }
        problems.shouldBeEmpty()
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
        // The release step reads $LASTEXITCODE after the script; a script that ends without exit
        // leaves it unchanged ($null in a fresh step), so a passing check would fail the release
        if (!Regex("""(?m)^exit 0\s*$""").containsMatchIn(text("desktop/packaging/check/install-check-windows.ps1"))) {
            problems += "install-check-windows.ps1 does not end with an explicit exit 0 when every check passes"
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
    fun `every job runs on a pinned runner image`() {
        val runsOn = Regex("""^\s*runs-on:\s*(\S+)""")
        val problems = mutableListOf<String>()
        for (file in workflowFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val image = runsOn.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                if (image.endsWith("-latest")) {
                    problems += "${file.name}:${index + 1} $image runs on a moving image; pin a version"
                }
            }
        }
        val macos = Regex("""^\s*runs-on:\s*(\S+)""", RegexOption.MULTILINE)
            .find(job(text(".github/workflows/desktop-build.yml"), "macos"))?.groupValues?.get(1).orEmpty()
        val version = Regex("""^macos-(\d+)$""").find(macos)?.groupValues?.get(1)?.toInt()
        if (version == null || version < 14) {
            problems += "desktop-build.yml: the macos job image \"$macos\" is not an Apple Silicon image (macos-14 or newer)"
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

    @Test
    fun `the version floor has one home that Gradle and the release workflow both read`() {
        val problems = mutableListOf<String>()
        val entries = text("gradle.properties").lines()
            .filter { it.startsWith("zoukan.versionFloor") }
        val floor = Regex("""^zoukan\.versionFloor=([1-9]\d*)\.(\d+)\.(\d+)\s*$""")
        if (entries.size != 1 || !floor.matches(entries.single())) {
            problems += "gradle.properties must hold exactly one zoukan.versionFloor=MAJOR.MINOR.PATCH line: $entries"
        } else if (floor.matchEntire(entries.single())!!.groupValues[1].toInt() > 255) {
            problems += "the floor's MAJOR is above 255, the MSI limit"
        }

        val script = text("desktop/build.gradle.kts")
        if ("\"zoukan.versionFloor\"" !in script) problems += "desktop/build.gradle.kts does not read zoukan.versionFloor"
        if (Regex(""""\d+\.\d+\.\d+"""").containsMatchIn(script)) {
            problems += "desktop/build.gradle.kts holds a quoted version literal"
        }

        val build = text(".github/workflows/desktop-build.yml")
        val start = build.indexOf("id: pick")
        val pick = if (start < 0) {
            ""
        } else {
            build.substring(start).let { rest ->
                val end = Regex("""(?m)^ {2}\S|^ {6}- """).find(rest, 1)?.range?.first ?: rest.length
                rest.substring(0, end)
            }
        }
        if ("gradle.properties" !in pick || "zoukan.versionFloor" !in pick) {
            problems += "the pick step does not read zoukan.versionFloor from gradle.properties"
        }
        if ("desktop/build.gradle.kts" in pick) problems += "the pick step still reads desktop/build.gradle.kts"
        if ("floor: \$floor" !in pick && "Version floor" !in pick) problems += "the pick step does not print the floor it read"
        problems.shouldBeEmpty()
    }
}
