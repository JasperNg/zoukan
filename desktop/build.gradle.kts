// ZOUKAN -->
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Zoukan, the desktop app: the Compose UI and reader (Phase 4 of the desktop port) on top of the
// runtime (the Android compatibility layer, Injekt wiring, the encrypted database, preferences,
// networking, the extension runtime, the built-in browser) and the command-line tools.
//
//   ./gradlew :desktop:run                              # the app
//   ./gradlew :desktop:run --args="harness"
//   ./gradlew :desktop:run --args="browser https://example.org"
//   ./gradlew :desktop:packageMsi                       # Windows installer (Phase 7, see RELEASING.md)
//   ./gradlew :desktop:packageDmg                       # macOS disk image (notarizeDmg to notarize)
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("mihon.code.lint")
    application
}

/**
 * Zoukan's own version, which the installers, the update check and crash logs use. Releases are
 * tagged desktop-v<version>. It is separate from the Android app's version, which extensions see.
 * MAJOR.MINOR.PATCH, MAJOR at least 1 (macOS) and at most 255 (MSI).
 *
 * The release workflow (.github/workflows/desktop-build.yml) passes the release's version as
 * -Pzoukan.version; the value here is the floor it bumps from, so raise it here for a new MINOR or
 * MAJOR. Local builds without the property use it as it is.
 */
val zoukanVersion: String = providers.gradleProperty("zoukan.version").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also {
        require(Regex("""[1-9]\d*\.\d+\.\d+""").matches(it)) { "zoukan.version must be MAJOR.MINOR.PATCH: $it" }
    }
    ?: "1.0.2"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

// Compose's desktop runtime (Skia) for the OS and CPU that builds the app: Windows x64 and macOS
// arm64 are the targets, Linux x64 is for development
val composeDesktopRuntime: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    val platform = when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
    "org.jetbrains.compose.desktop:desktop-jvm-$platform:${zoukanlibs.versions.compose.multiplatform.get()}"
}

// JavaCPP's name for the same OS and CPU, for FFmpeg's natives
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

// Android app code the desktop app compiles as it is (Phase 6): trackers, E-Hentai and merged
// manga. The files stay in app/ so upstream merges keep working; desktop supplies what they need
// from Android (R, BuildConfig, toasts) in src/main/kotlin/zoukan/desktop/compat. The list is in
// app-shared.txt, one Ant pattern per line, relative to app/src/main/java.
val appSharedPatterns: List<String> = file("app-shared.txt").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
val syncAppShared by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        appSharedPatterns.filterNot { it.startsWith("!") }.forEach { include(it) }
        appSharedPatterns.filter { it.startsWith("!") }.forEach { exclude(it.removePrefix("!")) }
    }
    into(layout.buildDirectory.dir("generated/app-shared"))
    inputs.file("app-shared.txt")
    // android.util.SparseArray is only a stub on desktop. The replacement is an input so a change
    // to it reruns the sync; filter lambdas are not.
    val sparseArrayImport = "import zoukan.desktop.compat.SparseArray"
    inputs.property("sparseArrayImport", sparseArrayImport)
    filesMatching("exh/eh/MemAutoFlushingLookupTable.kt") {
        filter { it.replace("import android.util.SparseArray", sparseArrayImport) }
    }
    // moko-resources' JVM StringResource has a key, not Android's resource id
    filesMatching("exh/recs/sources/RecommendationPagingSource.kt") {
        filter { it.replace("it.category.resourceId", "it.category.key") }
    }
}
sourceSets.main {
    kotlin.srcDir(syncAppShared)
}

// The version and the repository releases come from, for the update check and crash logs
val zoukanBuildInfo by tasks.registering {
    val output = layout.buildDirectory.dir("generated/zoukan-build-info")
    val version = zoukanVersion
    val repo = providers.gradleProperty("zoukan.releaseRepo").orElse("JasperNg/zoukan")
    // Whether this OS's packaging signs the app (packaging.gradle.kts, applied below, decides);
    // the update check reads it to tell an unsigned release from a signed one that lost its
    // signature. Read through a provider because packaging.gradle.kts sets it later in the
    // configuration than this registration, on the project's extras, not this task's.
    val signedRelease = provider { project.extra["zoukanSignedRelease"] == true }
    inputs.property("version", version)
    inputs.property("repo", repo)
    inputs.property("signedRelease", signedRelease)
    outputs.dir(output)
    doLast {
        output.get().file("zoukan-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$version\nreleaseRepo=${repo.get()}\nsignedRelease=${signedRelease.get()}\n")
        }
    }
}
sourceSets.main {
    resources.srcDir(zoukanBuildInfo)
}

// KMK's language flags (flagkit and the app's globe, UN and Esperanto icons), Android vector
// drawables that Compose loads as they are, for the library's language badge and the flag pickers;
// and the MangaDex DMCA list behind the library's "mangadex-dmca" search
val syncAndroidResources by tasks.registering(Sync::class) {
    from(rootProject.file("flagkit/src/main/res/drawable")) { into("flags") }
    from(rootProject.file("app/src/main/res/drawable")) {
        include("globe.xml", "ic_flag_un.xml", "ic_flag_esperanto.xml", "ic_flag_lfn.xml")
        into("flags")
    }
    from(rootProject.file("app/src/main/res/raw/mangadex_dmca_uuids.txt"))
    into(layout.buildDirectory.dir("generated/android-resources"))
}

// The app icon's vector master, which the window, tray and More header draw at their exact size
val appIconSvg = file("packaging/icons/zoukan.svg")
val syncAppIcon by tasks.registering(Sync::class) {
    doFirst { check(appIconSvg.isFile) { "The app icon $appIconSvg is missing" } }
    from(appIconSvg) { rename { "zoukan-icon.svg" } }
    into(layout.buildDirectory.dir("generated/app-icon"))
}
sourceSets.main {
    resources.srcDir(syncAndroidResources)
    resources.srcDir(syncAppIcon)
}

// Komikku's own app tests that cover code the desktop app compiles (see app-shared.txt) run in
// :desktop:test as they are. The others are Android-only: MigratorTest covers the upgrade
// migrations of Android installs, ScreenSerializationTest the Android screens (its desktop port is
// zoukan.desktop.ui.ScreenSerializationTest).
val syncAppSharedTests by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/test/kotlin")) { include("Tester.kt") }
    into(layout.buildDirectory.dir("generated/app-shared-tests"))
}
sourceSets.test {
    kotlin.srcDir(syncAppSharedTests)
}

configurations.runtimeClasspath {
    // Its MainDispatcherFactory outranks Swing's and would make Dispatchers.Main the emulated
    // Android main looper; the UI needs the AWT event thread (see also Main.kt). AndroidCompat
    // keeps its own HandlerDispatcher for Handler.asCoroutineDispatcher().
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
}

dependencies {
    // AndroidCompat first: its android.* classes must come before the stubs in android-jar
    implementation(projects.androidCompat)
    implementation(projects.extensionJvm)
    implementation(projects.sourceApi)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(projects.data)
    implementation(projects.backup)
    implementation(projects.i18n)
    implementation(projects.i18nSy)
    implementation(projects.i18nKmk)
    implementation(projects.i18nDesktop)

    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.protobuf)
    implementation(libs.okhttp.core)
    implementation(libs.logcat)
    implementation(libs.injekt)

    // What extensions expect from the host besides extensions-lib
    runtimeOnly(zoukanlibs.okhttp.zstd)
    implementation(zoukanlibs.bouncycastle)

    implementation(zoukanlibs.sqlite.jdbc.crypt)
    implementation(zoukanlibs.sqldelight.jvm.driver) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    implementation(zoukanlibs.jna)
    implementation(zoukanlibs.jna.platform)
    implementation(zoukanlibs.kotlin.logging)
    implementation(zoukanlibs.slf4j.api)
    // The log file and crash logs set up the file appender in code
    implementation(zoukanlibs.logback.classic)
    implementation(zoukanlibs.appdirs)
    implementation(zoukanlibs.zip4j)
    implementation(zoukanlibs.commons.compress)
    implementation(zoukanlibs.xz)
    implementation(zoukanlibs.junrar)
    // AVIF and HEIF images (Atsumaru serves AVIF covers and pages), with the natives for this OS only
    implementation(zoukanlibs.javacpp)
    implementation(zoukanlibs.ffmpeg)
    implementation(variantOf(zoukanlibs.javacpp) { classifier(javacppPlatform) })
    implementation(variantOf(zoukanlibs.ffmpeg) { classifier(javacppPlatform) })
    // The bulk migration's smart search, as the Android app
    implementation(libs.stringSimilarity)
    // The extension wiring check reads extension bytecode
    implementation(zoukanlibs.asm)

    // UI
    implementation(composeDesktopRuntime)
    implementation(zoukanlibs.compose.runtime)
    implementation(zoukanlibs.compose.foundation)
    implementation(zoukanlibs.compose.ui)
    implementation(zoukanlibs.compose.ui.util)
    implementation(zoukanlibs.compose.material3)
    implementation(zoukanlibs.compose.material.icons)
    implementation(zoukanlibs.coroutines.swing)
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.transitions)
    implementation(platform(libs.coil.bom))
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // The Android custom color scheme (Settings > Appearance)
    implementation(libs.materialKolor)
    implementation(kotlinx.immutables)
    implementation(libs.reorderable)

    testImplementation(libs.bundles.test)
    testImplementation(zoukanlibs.compose.ui.test)
    // The smoke test's usability check presses Escape as the window does, as Back (Usability.kt)
    testImplementation(zoukanlibs.compose.navigationevent)
    // On the compile classpath too: JourneyShardFilter is a launcher PostDiscoveryFilter
    testImplementation(libs.junit.platform.launcher)
}

val jvmFlags = listOf(
    "-Xmx2g",
    // JCEF needs these on recent JDKs
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
)

application {
    mainClass.set("zoukan.desktop.MainKt")
    applicationDefaultJvmArgs = jvmFlags
}

// The installers (Phase 7): packaging.gradle.kts builds the bundled runtime with jlink and the
// app, MSI and DMG with jpackage, and signs and notarizes them. (Compose's own Gradle plugin
// cannot be used here: its "compose" extension clashes with the upstream "compose" catalog.)
extra["zoukanVersion"] = zoukanVersion
extra["zoukanJvmFlags"] = jvmFlags
apply(from = "packaging.gradle.kts")

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
    // Pass -Pzoukan.cefDebug=true or -Pzoukan.cefArgs="..." through to the app
    listOf("zoukan.cefDebug", "zoukan.cefArgs", "zoukan.dataDir").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

/** The tests [wiringTest] runs instead of `test`. */
val wiringTests = listOf("*InjektRegistrationsTest", "*ScreenSmokeTest", "*ScreenSmokeSmallWindowTest")

/** The offline user journeys [journeyTest] runs instead of `test`, in zoukan.desktop.e2e and its subpackages. */
val journeyTests = listOf("zoukan.desktop.e2e.*JourneyTest")

tasks.test {
    useJUnitPlatform()
    // The end-to-end UI test reads a manga from an online source; it runs only when asked:
    //   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndReadTest"
    //   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndDownloadTest"
    providers.gradleProperty("zoukan.e2e").orNull?.let {
        systemProperty("zoukan.e2e", it)
        // Each end-to-end test starts the runtime with its own data folder, once per JVM
        forkEvery = 1
    }
    providers.gradleProperty("zoukan.e2e.package").orNull?.let { systemProperty("zoukan.e2e.package", it) }
    providers.gradleProperty("zoukan.e2e.query").orNull?.let { systemProperty("zoukan.e2e.query", it) }
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    // The UI text comes from string resources in the system language; the tests check the English text
    systemProperty("user.language", "en")
    // The release tests read the workflow files and the packaging script
    inputs.files(
        rootProject.fileTree(".github/workflows"),
        file("packaging.gradle.kts"),
    ).withPropertyName("releaseFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // The wiring tests start the runtime, once per JVM, so wiringTest runs them in their own JVMs
    if (!providers.gradleProperty("zoukan.e2e").isPresent) {
        filter { (wiringTests + journeyTests).forEach { excludeTestsMatching(it) } }
    }
}

// The wiring tests catch what compiles but crashes at run time: a lookup with no Injekt
// registration, a screen or dialog that throws when it opens. They need no network, so `check`
// (and so `build`) runs them on every change:
//   ./gradlew :desktop:wiringTest
val wiringTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks every Injekt lookup has a registration and every screen and dialog opens"
    val test = tasks.test.get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    useJUnitPlatform()
    filter {
        wiringTests.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = false
    }
    // Only the test classes themselves, not their nested and lambda classes: Gradle hands the
    // classes it scans to the forks in turn, which would queue both smoke runs in one fork
    include(wiringTests.map { "**/${it.removePrefix("*")}.class" })
    forkEvery = 1
    // The default and the small-window smoke runs, side by side
    maxParallelForks = 2
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    systemProperty("user.language", "en")
    // Runs only the smoke checks whose name matches, to debug one: -Pzoukan.smoke.only="dialog new category"
    providers.gradleProperty("zoukan.smoke.only").orNull?.let { systemProperty("zoukan.smoke.only", it) }
    // Fails the smoke test on usability findings that are not allowlisted (off until they are fixed)
    providers.gradleProperty("zoukan.usability.enforce").orNull?.let { systemProperty("zoukan.usability.enforce", it) }
    // Screenshot comparison (nightly, Linux): -Pzoukan.screenshots=verify compares with
    // src/test/screenshots/linux; =record rewrites them after an intended visual change.
    // Unset, no screenshot is taken and the goldens are not a task input, so normal runs stay cacheable.
    providers.gradleProperty("zoukan.screenshots").orNull?.let { mode ->
        systemProperty("zoukan.screenshots", mode)
        inputs.files(fileTree("src/test/screenshots")).withPropertyName("screenshotGoldens").withPathSensitivity(PathSensitivity.RELATIVE)
        // Every run takes and compares its own screenshots, so a second run is never skipped as up to date
        outputs.upToDateWhen { false }
        val reports = layout.buildDirectory.dir("reports/screenshots").get().asFile
        doFirst { reports.deleteRecursively() }
    }
    shouldRunAfter(tasks.test)
    // Each run starts without the findings of an earlier one, so usabilityReport merges only this run's
    val usabilityDir = layout.buildDirectory.dir("reports/usability").get().asFile
    doFirst { usabilityDir.deleteRecursively() }
    // Written even when a check fails
    finalizedBy("usabilityReport")
}
tasks.named("check") { dependsOn(wiringTest) }

/**
 * Merges the usability findings each smoke JVM wrote (build/reports/usability/<size>.tsv) into
 * build/reports/usability.md: a summary, the new findings, the allowlisted ones with their
 * reasons, and the allowlist entries that matched nothing.
 */
abstract class UsabilityReport : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val findings: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    private class Finding(val variant: String, val key: String, val rule: String, val node: String, val detail: String, val line: Int)

    @TaskAction
    fun merge() {
        val rows = findings.files.sortedBy { it.name }.flatMap { file ->
            file.readLines().filter { it.isNotBlank() }.map {
                val c = it.split("\t")
                Finding(c[0], c[1], c[2], c[3], c[4], c[5].toInt())
            }
        }
        // line number -> (key | rule | node, reason)
        val entries = allowlist.get().asFile.readLines().withIndex().mapNotNull { (i, raw) ->
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("#")) return@mapNotNull null
            val p = text.split("|", limit = 4).map { it.trim() }
            (i + 1) to (p.take(3).joinToString(" | ") to p.getOrElse(3) { "" })
        }.toMap()
        fun cell(s: String) = s.replace("|", "\\|")
        val variants = rows.map { it.variant }.distinct()
        val rules = listOf("UNLABELLED_CLICKABLE", "TEXT_OVERFLOW", "ESCAPE_NOT_DISMISSING", "TAB_TRAVERSAL")
        val text = buildString {
            appendLine("# Usability report")
            appendLine()
            appendLine("${rows.size} findings: ${rows.count { it.line == 0 }} new, ${rows.count { it.line != 0 }} allowlisted.")
            appendLine("Report only; `-Pzoukan.usability.enforce=true` fails the first three rules for new findings.")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Variant | Rule | New | Allowlisted |")
            appendLine("|---|---|---|---|")
            for (variant in variants) {
                for (rule in rules) {
                    val of = rows.filter { it.variant == variant && it.rule == rule }
                    appendLine("| $variant | $rule | ${of.count { it.line == 0 }} | ${of.count { it.line != 0 }} |")
                }
            }
            appendLine()
            appendLine("## New findings")
            appendLine()
            appendLine("| Variant | Key | Rule | Node | Detail |")
            appendLine("|---|---|---|---|---|")
            rows.filter { it.line == 0 }.forEach { appendLine("| ${it.variant} | ${cell(it.key)} | ${it.rule} | ${cell(it.node)} | ${cell(it.detail)} |") }
            appendLine()
            appendLine("## Allowlisted findings")
            appendLine()
            appendLine("| Variant | Key | Rule | Node | Reason |")
            appendLine("|---|---|---|---|---|")
            rows.filter { it.line != 0 }.forEach {
                appendLine("| ${it.variant} | ${cell(it.key)} | ${it.rule} | ${cell(it.node)} | ${cell(entries[it.line]?.second ?: "?")} |")
            }
            appendLine()
            appendLine("## Stale allowlist entries")
            appendLine()
            appendLine("Entries that matched nothing (only meaningful after a full run, not with `-Pzoukan.smoke.only`).")
            appendLine()
            val used = rows.map { it.line }.toSet()
            val stale = entries.filterKeys { it !in used }
            if (stale.isEmpty()) appendLine("None.") else stale.forEach { (line, e) -> appendLine("- line $line: ${e.first}") }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

tasks.register<UsabilityReport>("usabilityReport") {
    group = "verification"
    description = "Merges the smoke test's usability findings into build/reports/usability.md"
    findings.from(layout.buildDirectory.dir("reports/usability").map { dir -> dir.asFileTree.matching { include("*.tsv") } })
    allowlist.set(layout.projectDirectory.file("src/test/resources/usability-allowlist.txt"))
    report.set(layout.buildDirectory.file("reports/usability.md"))
    onlyIf { !findings.isEmpty }
}

// Installs every extension of the Keiyoushi store and checks it is wired up on the desktop: no
// class, method or field its bytecode uses is missing or a stub, its Injekt lookups resolve, its
// sources, client, headers and settings work (ExtensionWiringCheck). It downloads every
// extension, so it is not part of `check`; the nightly check runs it. The report goes to
// build/reports/e2e/extensions-wiring.md. -Pzoukan.e2e.package=en.mangapill,all.mangadex limits it:
//   ./gradlew :desktop:extensionWiringTest
val extensionWiringTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Installs every Keiyoushi extension and checks it is wired up on the desktop"
    val test = tasks.test.get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    useJUnitPlatform()
    filter { includeTestsMatching("*ExtensionWiringTest") }
    systemProperty("zoukan.e2e", "true")
    providers.gradleProperty("zoukan.e2e.package").orNull?.let { systemProperty("zoukan.e2e.package", it) }
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    systemProperty("user.language", "en")
    // Over a thousand extensions load into one JVM, one class loader each
    maxHeapSize = "4g"
    // Live data: never up to date
    outputs.upToDateWhen { false }
}

// The user journeys drive the real UI against a local fixture server (src/test/kotlin/zoukan/
// desktop/e2e): browse a source, read every image format and check the pages drew, change a
// source's settings, download chapters and read them offline, back up and restore. They need no
// network but take a few minutes, so they run on request and nightly, not on every build:
//   ./gradlew :desktop:journeyTest
// Screenshots go to desktop/build/reports/journeys/.
val journeyTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the offline user journeys against the fixture source"
    val test = tasks.test.get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    useJUnitPlatform()
    filter { journeyTests.forEach { includeTestsMatching(it) } }
    // Only the journey classes themselves, not their nested and lambda classes: Gradle hands the
    // classes it scans to the forks in turn, so empty forks would leave journeys queued behind
    // each other in one fork while the other forks have nothing to do. Subpackages too: `test`
    // leaves out every zoukan.desktop.e2e.*JourneyTest, subpackages included (Gradle's * matches
    // dots), so a journey must run here (JourneyShardTest checks the naming)
    include("zoukan/desktop/e2e/**/*JourneyTest.class")
    // Each journey starts the runtime with its own data folder, once per JVM
    forkEvery = 1
    // Journeys share nothing but this machine (each has its own data folder and a free port), so
    // their JVMs run side by side. Each runs a full runtime, Skia and FFmpeg: half the cores, at
    // most 4. -Pzoukan.journey.forks=1 runs them one at a time (to time or debug them).
    maxParallelForks = providers.gradleProperty("zoukan.journey.forks").orNull
        ?.let { forks -> forks.toIntOrNull()?.takeIf { it >= 1 } ?: error("zoukan.journey.forks must be a positive number, not \"$forks\"") }
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    // FFmpeg's natives (AVIF) unpack once for all forks instead of into each journey's data folder;
    // ZoukanRuntime keeps a cachedir that is already set. AvifJourneyTest clears it to keep the
    // app's own path (FFmpeg in the data folder) under test
    systemProperty("org.bytedeco.javacpp.cachedir", layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath)
    // CI splits the journeys over jobs: -Pzoukan.shard=2/3 runs the second third. Which journeys a
    // shard keeps is decided only by JourneyShardFilter in the test JVMs (JourneyShard.includes);
    // the build checks only the form of the value, so a typo fails here and not in every fork
    providers.gradleProperty("zoukan.shard").orNull?.let { value ->
        value.split('/').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == 2 && value.count { c -> c == '/' } == 1 && it[1] >= 1 && it[0] in 1..it[1] }
            ?: error("zoukan.shard must be i/n with 1 <= i <= n, not \"$value\"")
        systemProperty("zoukan.shard", value)
        // With few journeys and many shards, a shard can hold none: that job has nothing to do and
        // passes (Gradle would fail it with "No tests found for given includes")
        filter.isFailOnNoMatchingTests = false
    }
    // Each journey that ran (so each journey a shard kept), as it ends
    testLogging { events("passed", "skipped", "failed") }
    // The journeys check this machine as much as the code (JDK, display, decoders): never skip
    // them as up to date, and start each run without the last run's screenshots
    outputs.upToDateWhen { false }
    val screenshots = layout.projectDirectory.dir("build/reports/journeys")
    doFirst { screenshots.asFile.deleteRecursively() }
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    systemProperty("user.language", "en")
    shouldRunAfter(tasks.test, wiringTest)
}

/**
 * The extension wiring check that installs every extension from the stores and starts each
 * source. The nightly run picks it up by this name once it exists; until then it runs the
 * journeys alone.
 */
val extensionWiringTaskName = "extensionWiringTest"

// The nightly run (desktop/packaging/check/nightly-windows.ps1): the offline journeys, then the
// extension wiring check. Run it by hand with:
//   ./gradlew :desktop:nightlyCheck --continue
val nightlyCheck by tasks.registering {
    group = "verification"
    description = "Runs the offline user journeys and the extension wiring check"
    dependsOn(journeyTest)
    dependsOn(tasks.matching { it.name == extensionWiringTaskName })
    val hasWiring = provider { tasks.names.contains(extensionWiringTaskName) }
    doLast {
        if (!hasWiring.get()) logger.warn("nightlyCheck: there is no $extensionWiringTaskName task yet, so only the journeys ran")
    }
}
// ZOUKAN <--
