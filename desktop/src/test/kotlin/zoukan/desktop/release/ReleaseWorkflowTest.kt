package zoukan.desktop.release

import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `the PR gate covers offline checks and leaves live extension wiring to nightly`() {
        val ci = text(".github/workflows/ci.yml")
        val problems = mutableListOf<String>()
        val jobs = Regex("""(?m)^ {2}([\w-]+):\s*$""").findAll(ci.substringAfter("\njobs:\n")).map { it.groupValues[1] }.toList()
        val gateNeeds = Regex("""(?m)^ {4}needs: \[([^\]]*)]""").find(job(ci, "pr-gate"))?.groupValues?.get(1).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val missing = jobs - "pr-gate" - gateNeeds.toSet()
        if (jobs.isEmpty() || gateNeeds.isEmpty()) problems += "ci.yml: could not read the jobs or the PR gate's needs"
        if (missing.isNotEmpty()) problems += "ci.yml: PR gate does not need $missing, so they do not gate a merge or a release"
        if (":desktop:extensionWiringTest" in ci) problems += "ci.yml runs :desktop:extensionWiringTest"
        val nightly = text(".github/workflows/nightly.yml")
        val nightlyOn = nightly.substringAfter("\non:\n").substringBefore("\n\n")
        for (trigger in listOf("pull_request:", "push:")) {
            if (trigger in nightlyOn) problems += "nightly.yml starts on $trigger; it runs on its schedule and by hand"
        }
        if (!Regex("""(?m)^ {2}schedule:\n {4}- cron: '[^']+'""").containsMatchIn(nightlyOn)) problems += "nightly.yml has no schedule"
        if ("workflow_dispatch:" !in nightlyOn) problems += "nightly.yml cannot be started by hand"
        // A fork inherits the schedule: it must not run the nightly job on the fork's minutes
        if ("github.event_name != 'schedule' || !github.event.repository.fork" !in job(nightly, "extension-wiring")) {
            problems += "nightly.yml: the extension-wiring job runs on the schedule in forks too"
        }
        if (":desktop:extensionWiringTest" !in job(nightly, "extension-wiring")) {
            problems += "nightly.yml: the extension-wiring job does not run :desktop:extensionWiringTest"
        }
        // The offline journeys run on Windows in ci.yml and gate PRs and releases
        val journeys = job(ci, "journeys")
        if (journeys.isEmpty()) {
            problems += "ci.yml has no journeys job"
        } else {
            if (":desktop:journeyTest" !in journeys) problems += "ci.yml: the journeys job does not run :desktop:journeyTest"
            if ("needs.changes.outputs.code == 'true'" !in journeys) {
                problems += "ci.yml: the journeys job does not check needs.changes.outputs.code == 'true'"
            }
            if (!Regex("""(?m)^\s*runs-on:\s*windows-""").containsMatchIn(journeys)) {
                problems += "ci.yml: the journeys job does not run on Windows"
            }
        }
        if ("journeys" !in gateNeeds) problems += "ci.yml: PR gate does not need journeys"
        if (File(root, ".github/workflows/journeys.yml").exists()) {
            problems += ".github/workflows/journeys.yml still exists; journeys moved into ci.yml"
        }
        // The whole unit tier on Windows and macOS on every pull request, not only after the merge
        val unitTier = ":desktop:test :extension-jvm:test :domain:desktopTest :android-compat:test :backup:desktopTest " +
            ":data:desktopTest :core:common:desktopTest :source-api:desktopTest"
        val filesOs = job(ci, "files-os")
        if (unitTier !in filesOs || "macos-" !in filesOs || "windows-" !in filesOs) {
            problems += "ci.yml: files-os does not run the whole unit tier on Windows and macOS"
        }
        if ("--tests" in filesOs || "github.event_name" in filesOs || "\$EVENT" in filesOs) {
            problems += "ci.yml: files-os runs a different set of tests depending on the event, so pull requests do not run the whole tier"
        }
        if ("ZOUKAN_KEYCHAIN_TESTS: 'true'" !in filesOs) {
            problems += "ci.yml: files-os does not turn on the Keychain tests on its throwaway runner"
        }
        val unit = job(ci, "unit")
        if (unitTier !in unit) problems += "ci.yml: the unit job does not run the whole unit tier on Linux"
        val tierReports = unitTier.split(' ').map { it.removePrefix(":").substringBeforeLast(':').replace(':', '/') + "/build/reports/tests/" }
        for ((name, job) in listOf("unit" to unit, "files-os" to filesOs)) {
            tierReports.filter { it !in job }.forEach { problems += "ci.yml: the $name job does not keep $it" }
        }
        // The gating run is never instrumented: the same on pull requests as on master
        val unitCode = unit.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n").lowercase()
        for (marker in listOf("zoukan.coverage", "kover")) {
            if (marker in unitCode) problems += "ci.yml: the gating unit job runs with coverage ($marker)"
        }
        for (workflow in workflowFiles()) {
            if (Regex("""&&\s*(0|false|''|"")\s*\|\|""").containsMatchIn(workflow.readText())) {
                problems += "${workflow.name}: `cond && <falsy> || x` always gives x"
            }
        }
        if (":app:testDebugUnitTest" !in job(ci, "android-compile")) problems += "ci.yml does not run the Android app's unit tests"
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
    fun `every job runs on a pinned runner image`() {
        val runsOn = Regex("""^\s*runs-on:\s*(\S+)""")
        val matrixOs = Regex("""^\s*os:\s*\[([^\]]+)]""")
        val problems = mutableListOf<String>()
        for (file in workflowFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val images = listOfNotNull(runsOn.find(line)?.groupValues?.get(1)) +
                    matrixOs.find(line)?.groupValues?.get(1)?.split(",")?.map { it.trim() }.orEmpty()
                for (image in images) {
                    if (image.endsWith("-latest")) {
                        problems += "${file.name}:${index + 1} $image runs on a moving image; pin a version"
                    }
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

    @Test
    fun `Renovate is self-hosted with checksum refresh and replaces Dependabot`() {
        val problems = mutableListOf<String>()

        if (File(root, ".github/dependabot.yml").exists()) {
            problems += ".github/dependabot.yml still exists alongside Renovate"
        }

        val renovateJson = runCatching {
            Json.parseToJsonElement(text("renovate.json")).jsonObject
        }.getOrElse { error ->
            problems += "renovate.json is not valid JSON: ${error.message}"
            null
        }
        if (renovateJson != null) {
            val rules = renovateJson["packageRules"]?.jsonArray.orEmpty().map { it.jsonObject }
            val disablesActions = rules.any { rule ->
                val managers = rule["matchManagers"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                "github-actions" in managers && rule["enabled"]?.jsonPrimitive?.booleanOrNull == false
            }
            if (disablesActions) problems += "renovate.json disables github-actions"

            val refreshesChecksums = rules.any { rule ->
                val managers = rule["matchManagers"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                val post = rule["postUpgradeTasks"]?.jsonObject
                val commands = post?.get("commands")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                val filters = post?.get("fileFilters")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                "gradle" in managers &&
                    "gradle-wrapper" in managers &&
                    "bash .github/scripts/refresh-verification-metadata.sh" in commands &&
                    "gradle/verification-metadata.xml" in filters
            }
            if (!refreshesChecksums) {
                problems += "renovate.json does not refresh gradle/verification-metadata.xml in postUpgradeTasks"
            }

            val disablesAndroidJar = rules.any { rule ->
                val packages = rule["matchPackageNames"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                "com.github.Suwayomi:android-jar" in packages && rule["enabled"]?.jsonPrimitive?.booleanOrNull == false
            }
            if (!disablesAndroidJar) problems += "renovate.json does not disable com.github.Suwayomi:android-jar"

            val gatesJcef = rules.any { rule ->
                val packages = rule["matchPackageNames"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                packages.any { """me\.friwi:jcef""" in it || "me.friwi:jcef" in it } &&
                    rule["dependencyDashboardApproval"]?.jsonPrimitive?.booleanOrNull == true
            }
            if (!gatesJcef) problems += "renovate.json does not gate me.friwi:jcef behind dependencyDashboardApproval"

            val dashboard = renovateJson["dependencyDashboard"] as? JsonObject
            if ((dashboard?.get("enabled") as? JsonPrimitive)?.booleanOrNull != true) {
                problems += "renovate.json does not set dependencyDashboard.enabled to true"
            }

            val statusCheckWhen = renovateJson["statusCheckWhen"] as? JsonObject
            if ((statusCheckWhen?.get("artifactError") as? JsonPrimitive)?.contentOrNull != "never") {
                problems += "renovate.json does not set statusCheckWhen.artifactError to \"never\""
            }
            if ((statusCheckWhen?.get("minimumReleaseAge") as? JsonPrimitive)?.contentOrNull != "never") {
                problems += "renovate.json does not set statusCheckWhen.minimumReleaseAge to \"never\""
            }
        }

        val workflowFile = File(root, ".github/workflows/renovate.yml")
        if (!workflowFile.isFile) {
            problems += ".github/workflows/renovate.yml is missing"
        } else {
            val workflow = workflowFile.readText()
            if ("github.event_name != 'schedule' || !github.event.repository.fork" !in job(workflow, "renovate")) {
                problems += "renovate.yml: the renovate job runs on the schedule in forks too"
            }
            if ("platformCommit: \"disabled\"" !in workflow) {
                problems += "renovate.yml does not set platformCommit: \"disabled\""
            }
            if (!Regex("""gitAuthor:\s*"[^"]*users\.noreply\.github\.com>?"""").containsMatchIn(workflow)) {
                problems += "renovate.yml does not set gitAuthor to a users.noreply.github.com address"
            }
            if ("exposeAllEnv" in workflow) {
                problems += "renovate.yml sets exposeAllEnv, which would expose secrets to postUpgradeTasks"
            }
            val allowedCommandsBlock = Regex("""allowedCommands:\s*\[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL)
                .find(workflow)?.groupValues?.get(1)
            val allowedCommands = Regex(""""([^"]+)"""")
                .findAll(allowedCommandsBlock.orEmpty())
                .map { it.groupValues[1] }
                .toList()
            val expectedAllowed = listOf("""^bash \\.github/scripts/refresh-verification-metadata\\.sh$""")
            if (allowedCommands != expectedAllowed) {
                problems += "renovate.yml allowedCommands must be $expectedAllowed, found $allowedCommands"
            }
        }

        val scriptFile = File(root, ".github/scripts/refresh-verification-metadata.sh")
        if (!scriptFile.isFile) {
            problems += ".github/scripts/refresh-verification-metadata.sh is missing"
        } else {
            if (!scriptFile.canExecute()) problems += ".github/scripts/refresh-verification-metadata.sh is not executable"
            val script = scriptFile.readText()
            for (platform in listOf("linux-x64", "windows-x64", "macos-arm64")) {
                if (platform !in script) problems += "refresh-verification-metadata.sh does not resolve $platform"
            }
            if (":desktop:resolveDesktopClasspaths" !in script) {
                problems += "refresh-verification-metadata.sh does not run :desktop:resolveDesktopClasspaths"
            }
            if ("verify-metadata" !in script || "len(hashes) > 1" !in script) {
                problems += "refresh-verification-metadata.sh does not verify XML single-hash integrity"
            }
            if ("""PIPESTATUS[@]""" !in script) {
                problems += "refresh-verification-metadata.sh does not snapshot PIPESTATUS[@] before indexing"
            }
        }

        problems.shouldBeEmpty()
    }
}
