// ZOUKAN -->
// Zoukan, the desktop app: the Compose UI and reader (Phase 4 of the desktop port) on top of the
// runtime (the Android compatibility layer, Injekt wiring, the encrypted database, preferences,
// networking, the extension runtime, the built-in browser) and the command-line tools.
//
//   ./gradlew :desktop:run                              # the app
//   ./gradlew :desktop:run --args="harness --store <url>"
//   ./gradlew :desktop:run --args="browser https://example.org"
//   ./gradlew :desktop:packageMsi                       # Windows installer (Phase 7, see RELEASING.md)
//   ./gradlew :desktop:packageDmg                       # macOS disk image (notarizeDmg to notarize)
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

plugins {
    id("zoukan.jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("mihon.code.lint")
    id("zoukan.kover")
    id("zoukan.test-home")
    application
}

/** The floor from gradle.properties; the release workflow reads the same entry. */
val zoukanVersionFloor: String = providers.gradleProperty("zoukan.versionFloor").orNull?.trim()
    ?.takeIf { Regex("""[1-9]\d*\.\d+\.\d+""").matches(it) }
    ?: throw GradleException("gradle.properties must set zoukan.versionFloor=MAJOR.MINOR.PATCH")

/**
 * Zoukan's own version, which the installers, the update check and crash logs use. Releases are
 * tagged desktop-v<version>. It is separate from the Android app's version, which extensions see.
 * MAJOR.MINOR.PATCH, MAJOR at least 1 (macOS) and at most 255 (MSI).
 *
 * The release workflow (.github/workflows/desktop-build.yml) passes the release's version as
 * -Pzoukan.version; the floor it bumps from is zoukan.versionFloor in gradle.properties, so raise
 * it there for a new MINOR or MAJOR. Local builds without the property use the floor as it is.
 */
val zoukanVersion: String = providers.gradleProperty("zoukan.version").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also {
        require(Regex("""[1-9]\d*\.\d+\.\d+""").matches(it)) { "zoukan.version must be MAJOR.MINOR.PATCH: $it" }
    }
    ?: zoukanVersionFloor

kotlin {
    compilerOptions {
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
// zoukan.targetPlatform (windows-x64, macos-arm64, linux-x64) only exists to generate the dependency
// checksums of another OS (gradle/verification-metadata.xml); it does not build another OS's app
val targetPlatformOverride: String? = providers.gradleProperty("zoukan.targetPlatform").orNull
val composeDesktopRuntime: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    val platform = targetPlatformOverride ?: when {
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
    when (targetPlatformOverride) {
        "windows-x64" -> "windows-x86_64"
        "macos-arm64" -> "macosx-arm64"
        "linux-x64" -> "linux-x86_64"
        else -> when {
            os.contains("win") -> "windows-x86_64"
            os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
            else -> if (arm) "linux-arm64" else "linux-x86_64"
        }
    }
}

// Android app code the desktop app compiles as it is (Phase 6): trackers, E-Hentai and merged
// manga. The files stay in app/ so upstream merges keep working; desktop supplies what they need
// from Android (R, BuildConfig, toasts) in src/main/kotlin/zoukan/desktop/compat. The list is in
// app-shared.txt, one Ant pattern per line, relative to app/src/main/java.
val appSharedPatterns: List<String> = file("app-shared.txt").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
// Text rewrites applied to some of those files, from app-shared-rewrites.txt: path | search | replacement
class AppSharedRewrite(val path: String, val from: String, val to: String) : java.io.Serializable
val appSharedRewrites: List<AppSharedRewrite> = file("app-shared-rewrites.txt").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        // limit 3 keeps an empty replacement (the line then ends after the second bar)
        val parts = line.split("|", limit = 3).map { it.trim() }
        require(parts.size == 3) { "app-shared-rewrites.txt: expected 'path | search | replacement', got '$line'" }
        AppSharedRewrite(parts[0], parts[1], parts[2])
    }
val syncAppShared by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        appSharedPatterns.filterNot { it.startsWith("!") }.forEach { include(it) }
        appSharedPatterns.filter { it.startsWith("!") }.forEach { exclude(it.removePrefix("!")) }
    }
    into(layout.buildDirectory.dir("generated/app-shared"))
    inputs.file("app-shared.txt")
    // The rewrites (for example android.util.SparseArray, which is only a stub on desktop) are in
    // app-shared-rewrites.txt. That file is an input so a change to it reruns the sync; filter
    // lambdas are not.
    inputs.file("app-shared-rewrites.txt")
    appSharedRewrites.forEach { rewrite ->
        val from = rewrite.from
        val to = rewrite.to
        filesMatching(rewrite.path) {
            filter { it.replace(from, to) }
        }
    }
    // Fail the build when a rewrite did not apply, so a changed upstream line cannot ship unrewritten.
    val rewrites = appSharedRewrites
    val outputDir = layout.buildDirectory.dir("generated/app-shared")
    doLast {
        val root = outputDir.get().asFile
        rewrites.forEach { rewrite ->
            val file = File(root, rewrite.path)
            val text = if (file.isFile) file.readText() else null
            if (text == null || rewrite.from in text || rewrite.to !in text) {
                throw GradleException(
                    "syncAppShared: '${rewrite.from}' no longer occurs in ${rewrite.path}; update app-shared-rewrites.txt",
                )
            }
        }
        // android.util.SparseArray is only a stub on desktop: no synced file may still use it
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .firstOrNull { "android.util.SparseArray" in it.readText() }
            ?.let {
                throw GradleException(
                    "syncAppShared: ${it.relativeTo(root).invariantSeparatorsPath} uses android.util.SparseArray, " +
                        "which is only a stub on desktop; add a rewrite to app-shared-rewrites.txt",
                )
            }
    }
}
sourceSets.main {
    kotlin.srcDir(syncAppShared)
}

// The version and the repository releases come from, for the update check and crash logs
val zoukanSignedRelease = objects.property<Boolean>().convention(false)
val zoukanBuildInfo by tasks.registering {
    val output = layout.buildDirectory.dir("generated/zoukan-build-info")
    val version = zoukanVersion
    val repo = providers.gradleProperty("zoukan.releaseRepo").orElse("JasperNg/zoukan")
    // Whether this OS's packaging signs the app (packaging.gradle.kts, applied below, decides);
    // the update check reads it to tell an unsigned release from a signed one that lost its
    // signature. packaging.gradle.kts sets it later in the configuration than this registration,
    // on the project's extras: it is copied into this property right after that script is applied
    // (below), and the task itself never reads the project.
    val signedRelease = zoukanSignedRelease
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

// The Android dispatcher is declared for Android targets only. Its MainDispatcherFactory outranks
// Swing's and would make Dispatchers.Main the emulated Android main looper; the UI needs the AWT
// event thread (see also Main.kt). This exclude guards against a new dependency bringing it back,
// for the app and for every test task (they all use the test runtime classpath). AndroidCompat
// keeps its own HandlerDispatcher for Handler.asCoroutineDispatcher().
configurations.matching { it.name == "runtimeClasspath" || it.name == "testRuntimeClasspath" }.configureEach {
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

    // One BOM version for every coroutines artifact, kotlinx-coroutines-swing included
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.protobuf)
    implementation(libs.okhttp.core)
    implementation(libs.logcat)
    implementation(libs.injekt)

    // What extensions expect from the host besides extensions-lib
    runtimeOnly(libs.okhttp.zstd)
    implementation(zoukanlibs.bouncycastle)

    implementation(zoukanlibs.sqlite.jdbc.crypt)
    implementation(zoukanlibs.sqldelight.jvm.driver) {
        version { require(libs.versions.sqldelight.get()) }
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
    // ContextStubsTest builds the emulated Context's files config in a temporary folder
    testImplementation(zoukanlibs.typesafe.config)
    // Property-based tests (zoukan.desktop.property), run as ordinary JUnit tests
    testImplementation(libs.kotest.property)
}

val jvmFlags = listOf(
    "-Xmx2g",
    // JCEF needs these on recent JDKs
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    // HTTP goes through the proxy set in the OS, as the built-in browser's does (the JDK reads
    // this once, when its default ProxySelector first loads). Not for test JVMs, which must not
    // depend on the machine's proxy.
    "-Djava.net.useSystemProxies=true",
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
zoukanSignedRelease.set(extra["zoukanSignedRelease"] == true)

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
    // Pass -Pzoukan.cefDebug=true or -Pzoukan.cefArgs="..." through to the app
    listOf("zoukan.cefDebug", "zoukan.cefArgs", "zoukan.dataDir").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

// Each Test task gets its own home and data folder under build/test-home/<task> (zoukan.test-home)

/** The test classes [wiringTest] must run; a rename needs this one list changed. */
val expectedWiringClasses = listOf("InjektRegistrationsTest", "ScreenSmokeTest", "ScreenSmokeSmallWindowTest", "AppLinkageTest", "ZoukanRuntimeStartTest")

/** The tests [wiringTest] runs instead of `test`. */
val wiringTests = expectedWiringClasses.map { "*$it" }

/** The offline user journeys [journeyTest] runs instead of `test`, in zoukan.desktop.e2e and its subpackages. */
val journeyTests = listOf("zoukan.desktop.e2e.*JourneyTest")

/** The button crawl [buttonCrawlTest] runs instead of `test`. */
val buttonCrawlTests = listOf("*ButtonCrawlTest")

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
    // RestoreSpeedTest's size, work folder and JFR file, for timing a larger restore by hand
    listOf("zoukan.restoreBench", "zoukan.restoreBench.dir", "zoukan.restoreBench.jfr").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // The property tests' seed and iteration count, to repeat a failure or run longer by hand
    listOf("zoukan.property.seed", "zoukan.property.iterations").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    // The UI text comes from string resources in the system language; the tests check the English text
    systemProperty("user.language", "en")
    // The build-settings and release tests read these files (the workflows, the packaging script),
    // so a change to one reruns them
    inputs.files(
        rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
        rootProject.file("gradle/verification-metadata.xml"),
        rootProject.file("gradle.properties"),
        rootProject.file("renovate.json"),
        rootProject.file("desktop/UPSTREAM-MERGES.md"),
        rootProject.file("desktop/RELEASING.md"),
        rootProject.file("extension-jvm/build.gradle.kts"),
        rootProject.file("android-compat/build.gradle.kts"),
        rootProject.fileTree("gradle") { include("*.versions.toml") },
        rootProject.fileTree(".github/workflows"),
        file("build.gradle.kts"),
        file("packaging.gradle.kts"),
        file("packaging/check/nightly-windows.ps1"),
        file("packaging/check/install-check-windows.ps1"),
    ).withPropertyName("releaseFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // The wiring tests start the runtime, once per JVM, so wiringTest runs them in their own JVMs
    if (!providers.gradleProperty("zoukan.e2e").isPresent) {
        filter { (wiringTests + journeyTests + buttonCrawlTests).forEach { excludeTestsMatching(it) } }
    }
}

// The wiring tests catch what compiles but crashes at run time: a lookup with no Injekt
// registration, a screen or dialog that throws when it opens. They need no network, so `check`
// (and so `build`) runs them on every change:
/**
 * Whether a task on the command line runs only some of its tests: Gradle takes the filter as
 * `--tests Foo` and as `--tests=Foo`. A narrowed run must not vouch for the whole check (the long-check
 * records, wiringTest's list of classes). BuildSettingsTest checks every such check uses this.
 */
fun narrowsTests(args: List<String>): Boolean = args.any { it == "--tests" || it.startsWith("--tests=") }
val testsNarrowedOnCommandLine = gradle.startParameter.taskRequests.any { narrowsTests(it.args) }

//   ./gradlew :desktop:wiringTest
val wiringTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks every Injekt lookup has a registration and every screen and dialog opens"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        wiringTests.forEach { includeTestsMatching(it) }
    }
    // A class renamed out of the patterns would otherwise drop out of the wiring tests silently,
    // unless the developer narrowed the run with --tests on purpose
    val narrowedOnCommandLine = testsNarrowedOnCommandLine
    val wiringResults = reports.junitXml.outputLocation
    // A local copy: the action must not reference the build script (configuration cache)
    val expected = expectedWiringClasses
    doLast {
        if (!narrowedOnCommandLine) {
            val ran = wiringResults.get().asFile.listFiles().orEmpty().map { it.name }
            val missing = expected.filter { name ->
                ran.none { it.startsWith("TEST-") && it.endsWith(".$name.xml") }
            }
            check(missing.isEmpty()) { "wiringTest did not run ${missing.joinToString()}: was a class renamed or moved?" }
        }
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

// The button crawl clicks every button of every screen, tab, Settings page and dialog, and fails
// on a click that throws, logs an error, hangs the window or does nothing, and on a button that
// appeared or went since src/test/resources/button-inventory.txt. It takes long, so `check` does
// not run it; longChecks runs it whole on Windows (desktop/TESTING.md, 'The button crawl'):
//   ./gradlew :desktop:buttonCrawlTest
//   ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.only="settings:Library" -Pzoukan.shard=1/4
//   ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true
val buttonCrawlTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Clicks every button of the app and checks each one does something without an error"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { buttonCrawlTests.forEach { includeTestsMatching(it) } }
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    systemProperty("user.language", "en")
    // Offline on every machine: HTTP goes to a closed local port, so a click that would reach a
    // server fails at once and the same way everywhere (the fixture servers on localhost are not used)
    listOf("http", "https").forEach { scheme ->
        systemProperty("$scheme.proxyHost", "127.0.0.1")
        systemProperty("$scheme.proxyPort", "9")
    }
    // -Pzoukan.crawl.only=<regex> crawls only the checks whose name or key matches
    providers.gradleProperty("zoukan.crawl.only").orNull?.let { systemProperty("zoukan.crawl.only", it) }
    providers.gradleProperty("zoukan.crawl.debug").orNull?.let { systemProperty("zoukan.crawl.debug", it) }
    // -Pzoukan.shard=i/n crawls every n-th check key, from the i-th (to debug a part; the baseline
    // and longChecks use one unsharded run, since the split can change a signature)
    providers.gradleProperty("zoukan.shard").orNull?.let { value ->
        value.split('/').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == 2 && value.count { c -> c == '/' } == 1 && it[1] >= 1 && it[0] in 1..it[1] }
            ?: error("zoukan.shard must be i/n with 1 <= i <= n, not \"$value\"")
        systemProperty("zoukan.shard", value)
    }
    // -Pzoukan.crawl.updateBaseline=true rewrites src/test/resources/button-inventory.txt with the
    // buttons this run found (only the checks it crawled, in a filtered or sharded run)
    providers.gradleProperty("zoukan.crawl.updateBaseline").orNull?.let { systemProperty("zoukan.crawl.updateBaseline", it) }
    // The crawl checks the app as it is now, and writes its report each time
    outputs.upToDateWhen { false }
    testLogging { events("passed", "skipped", "failed") }
    shouldRunAfter(tasks.test, wiringTest)
}

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
            appendLine("A new finding of the first three rules fails the smoke test; Tab traversal is only reported.")
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

// Installs every extension of each sample store and checks it is wired up on the desktop: no
// class, method or field its bytecode uses is missing or a stub, its Injekt lookups resolve, its
// sources, client, headers and settings work (ExtensionWiringCheck). The stores are
// SampleStores.ALL, which only the tests define (the app names no store): Keiyoushi, and yuzono's
// "Cursed" store, which the app does not add or trust. HarnessRunsTest checks this list matches. Each store runs in a Test task and JVM of its own
// (extensionWiringTest<Store>), one after the other: Keiyoushi alone needs a 4 GB heap. It downloads
// every extension, so it is not part of `check`; the nightly check runs it. The reports go to
// build/reports/e2e/extensions-wiring-<store>.md. -Pzoukan.e2e.stores=yuzono picks the stores and
// -Pzoukan.e2e.package=en.mangapill,all.mangadex the extensions (each store checks those it lists):
//   ./gradlew :desktop:extensionWiringTest
val extensionWiringStores = listOf("keiyoushi", "yuzono")
val selectedWiringStores = providers.gradleProperty("zoukan.e2e.stores").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?.onEach { require(it in extensionWiringStores) { "zoukan.e2e.stores: unknown store \"$it\", the stores are $extensionWiringStores" } }
    ?: extensionWiringStores
val extensionWiringStoreTests = extensionWiringStores.map { store ->
    tasks.register<Test>("extensionWiringTest${store.replaceFirstChar(Char::uppercaseChar)}") {
        group = "verification"
        description = "Installs every extension of the $store store and checks it is wired up on the desktop"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching("*ExtensionWiringTest") }
        systemProperty("zoukan.e2e", "true")
        systemProperty("zoukan.e2e.store", store)
        providers.gradleProperty("zoukan.e2e.package").orNull?.let { systemProperty("zoukan.e2e.package", it) }
        systemProperty("kotlinx.coroutines.fast.service.loader", "false")
        systemProperty("user.language", "en")
        // Over a thousand extensions load into one JVM, one class loader each
        maxHeapSize = "4g"
        // Live data: never up to date
        outputs.upToDateWhen { false }
    }
}
// One store at a time: two 4 GB test JVMs at once need more memory than many machines have
extensionWiringStoreTests.zipWithNext { a, b -> b.configure { mustRunAfter(a) } }
val extensionWiringTest by tasks.registering {
    group = "verification"
    description = "Checks every extension of each sample store is wired up on the desktop (-Pzoukan.e2e.stores to pick)"
    dependsOn(extensionWiringStoreTests.filterIndexed { i, _ -> extensionWiringStores[i] in selectedWiringStores })
}

// The user journeys drive the real UI against a local fixture server (src/test/kotlin/zoukan/
// desktop/e2e): browse a source, read every image format and check the pages drew, change a
// source's settings, download chapters and read them offline, back up and restore. They need no
// network but take a few minutes, so they run on request and in longChecks, not on every build:
//   ./gradlew :desktop:journeyTest
// Screenshots go to desktop/build/reports/journeys/.
val journeyTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the offline user journeys against the fixture source"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
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
    // -Pzoukan.shard=2/3 runs the second third of the journeys (to split them over machines). Which
    // journeys a shard keeps is decided only by JourneyShardFilter in the test JVMs (JourneyShard.includes);
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
    // A journey that hangs (a deadlock waits forever, so no waitUntil timeout fires) fails after 10
    // minutes with its stack, instead of holding its fork for the rest of the run. The longest
    // journeys take about 2 minutes. -Pzoukan.journey.timeout=30m raises it to debug a slow machine
    systemProperty("junit.jupiter.execution.timeout.default", providers.gradleProperty("zoukan.journey.timeout").getOrElse("10m"))
    shouldRunAfter(tasks.test, wiringTest)
}

/**
 * The extension wiring check that installs every extension from the stores and starts each
 * source; the nightly run runs it by this name.
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
        if (!hasWiring.get()) logger.warn("nightlyCheck: no $extensionWiringTaskName task, so only the journeys ran")
    }
}

/** The three long checks; verifyLongChecks accepts a longChecks record or a record of each of these. */
val longCheckParts = listOf("journeyTest", "buttonCrawlTest", "extensionWiringTest")

/** Where the long checks leave their records: build/long-checks/<check>.properties. */
val longCheckRecordsDir: File = layout.buildDirectory.dir("long-checks").get().asFile

/** The checkout the records name the commit of. */
val repositoryRoot: File = rootProject.projectDir

// The long checks GitHub CI leaves out: the journeys, the whole button crawl and the extension
// wiring check. They must pass on Windows before each merge to master (desktop/RELEASING.md,
// "Before merging to master"):
//   gradlew.bat :desktop:longChecks --continue
// One task after another (the journeys already run up to 4 JVMs side by side), and the crawl in one
// unsharded run: button-inventory.txt is recorded the same way, so a shard split never changes a
// signature.
// When every part passed, it leaves the record build/long-checks/longChecks.properties (see
// LongCheckRecords below), which verifyLongChecks reads before a push to master.
val longChecks by tasks.registering {
    group = "verification"
    description = "Runs the journeys, the whole button crawl and the extension wiring check (before a merge to master)"
    dependsOn(nightlyCheck, buttonCrawlTest)
    // Local copies: the action must not reference the build script (configuration cache)
    val records = longCheckRecordsDir
    val repo = repositoryRoot
    val parts = longCheckParts
    doLast {
        LongCheckRecords.combine(records, repo, "longChecks", parts, parts.map { ":desktop:$it" }, logger)
    }
}
buttonCrawlTest.configure { mustRunAfter(journeyTest) }
extensionWiringStoreTests.forEach { it.configure { mustRunAfter(buttonCrawlTest) } }
if (gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "longChecks" } && providers.gradleProperty("zoukan.shard").isPresent) {
    error("longChecks runs every journey and the whole button crawl: leave out -Pzoukan.shard")
}

/**
 * A record of a long check that passed: which check, the commit (`git rev-parse HEAD`), whether
 * the working tree had changes (`git status --porcelain`, ignored files left out), the date and
 * the tasks run. A check writes its record only when it ran whole (no --tests, shard or other
 * narrowing) and the commit and the working tree were the same when it started and when it
 * passed; a check that starts deletes its old record, so a failed run leaves none. verifyLongChecks
 * reads the records before a push to master.
 */
object LongCheckRecords {
    class GitState(val commit: String, val status: String) {
        val dirty: Boolean get() = status.isNotBlank()

        /** A digest of the porcelain status, so two states can be compared without storing the file list. */
        val tree: String get() = MessageDigest.getInstance("SHA-256")
            .digest(status.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    class Record(val file: File, val check: String, val commit: String, val dirty: Boolean, val tree: String, val date: String, val tasks: String)

    private fun git(repo: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", *args)).directory(repo)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) out else null
    } catch (e: java.io.IOException) {
        null
    }

    fun gitState(repo: File): GitState {
        val commit = git(repo, "rev-parse", "HEAD")?.trim()?.takeIf { Regex("[0-9a-f]{40,64}").matches(it) }
            ?: throw GradleException("Could not read the commit: `git rev-parse HEAD` failed in $repo")
        val status = git(repo, "status", "--porcelain", "--untracked-files=normal")
            ?: throw GradleException("Could not read the working tree: `git status --porcelain` failed in $repo")
        return GitState(commit, status)
    }

    /** Before a check runs: deletes its old record and notes the commit and the tree it starts on. */
    fun start(dir: File, repo: File, check: String) {
        dir.mkdirs()
        File(dir, "$check.properties").delete()
        val state = gitState(repo)
        File(dir, "$check.start").writeText("${state.commit}\n${state.tree}\n")
    }

    /** After a check passed: writes its record, unless the commit or the tree changed while it ran. */
    fun finish(dir: File, repo: File, check: String, tasks: List<String>, logger: Logger) {
        val startFile = File(dir, "$check.start")
        val started = startFile.takeIf { it.isFile }?.readLines().orEmpty()
        startFile.delete()
        val now = gitState(repo)
        if (started.size < 2 || started[0] != now.commit || started[1] != now.tree) {
            logger.warn("$check passed, but the commit or the working tree changed while it ran, so it left no record")
            return
        }
        write(dir, check, tasks, now, logger)
    }

    /** Writes [check]'s record when every one of [parts] left a record on the current commit and tree. */
    fun combine(dir: File, repo: File, check: String, parts: List<String>, tasks: List<String>, logger: Logger) {
        val now = gitState(repo)
        val missing = parts.filter { part ->
            val record = read(File(dir, "$part.properties"))
            record == null || record.commit != now.commit || record.tree != now.tree
        }
        if (missing.isNotEmpty()) {
            logger.warn("$check: no record of ${missing.joinToString()} on this commit and tree (narrowed, or the tree changed), so it left no record")
            return
        }
        write(dir, check, tasks, now, logger)
    }

    private fun write(dir: File, check: String, tasks: List<String>, state: GitState, logger: Logger) {
        dir.mkdirs()
        val file = File(dir, "$check.properties")
        val props = Properties()
        props["check"] = check
        props["commit"] = state.commit
        props["dirty"] = state.dirty.toString()
        props["tree"] = state.tree
        props["date"] = Instant.now().toString()
        props["tasks"] = tasks.joinToString(",")
        file.outputStream().use { props.store(it, "A long check that passed (verifyLongChecks reads this)") }
        logger.lifecycle("$check passed on ${state.commit}${if (state.dirty) " with uncommitted changes" else ""}: recorded in $file")
    }

    fun read(file: File): Record? {
        if (!file.isFile) return null
        val props = Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            return null
        }
        val check = props.getProperty("check") ?: return null
        val commit = props.getProperty("commit") ?: return null
        return Record(
            file,
            check,
            commit,
            props.getProperty("dirty") != "false",
            props.getProperty("tree").orEmpty(),
            props.getProperty("date").orEmpty(),
            props.getProperty("tasks").orEmpty(),
        )
    }

    /** The records in each of [sources]: a record file, or a folder of them. */
    fun collect(sources: List<File>): List<Record> = sources.flatMap { source ->
        when {
            source.isDirectory -> source.listFiles { f -> f.isFile && f.name.endsWith(".properties") }.orEmpty().sortedBy { it.name }.mapNotNull(::read)
            else -> listOfNotNull(read(source))
        }
    }

    /** Null when the records show the long checks passed on [state]; otherwise why not. */
    fun verify(state: GitState, sources: List<File>, parts: List<String>): String? {
        val records = collect(sources)
        val valid = records.filter { it.commit == state.commit && !it.dirty }.map { it.check }.toSet()
        if (!state.dirty && ("longChecks" in valid || valid.containsAll(parts))) return null
        return buildString {
            appendLine(
                if (state.dirty) {
                    "The working tree has uncommitted changes, so no record can vouch for it."
                } else {
                    "The long checks have no passing record for this commit."
                },
            )
            appendLine("  HEAD: ${state.commit}${if (state.dirty) " (with uncommitted changes: commit or stash them, then run the checks again)" else ""}")
            appendLine("  Looked in: ${sources.joinToString { it.path }}")
            if (records.isEmpty()) appendLine("  Found no records.")
            records.forEach { r ->
                val why = when {
                    r.commit != state.commit -> "another commit (${r.commit.take(12)})"
                    r.dirty -> "ran with uncommitted changes"
                    else -> "ok"
                }
                appendLine("  ${r.check}: $why, ${r.date} (${r.file})")
            }
            val missing = parts.filter { it !in valid }
            if ("longChecks" !in valid && missing.isNotEmpty()) appendLine("  Missing: longChecks, or each of ${missing.joinToString()}")
            appendLine("Run `gradlew :desktop:longChecks --continue` on this commit, or each long check in its own worktree")
            append("and pass their record folders with -Pzoukan.longChecks.records=<folder>,<folder> (desktop/TESTING.md).")
        }
    }
}

// Each long check leaves its own record when it runs whole, so the three can run side by side in
// their own worktrees (desktop/TESTING.md, "Before merging to master")
journeyTest.configure {
    val records = longCheckRecordsDir
    val repo = repositoryRoot
    val whole = !testsNarrowedOnCommandLine && !providers.gradleProperty("zoukan.shard").isPresent
    if (whole) {
        doFirst { LongCheckRecords.start(records, repo, "journeyTest") }
        doLast { LongCheckRecords.finish(records, repo, "journeyTest", listOf(":desktop:journeyTest"), logger) }
    }
}
buttonCrawlTest.configure {
    val records = longCheckRecordsDir
    val repo = repositoryRoot
    val whole = !testsNarrowedOnCommandLine &&
        listOf("zoukan.shard", "zoukan.crawl.only", "zoukan.crawl.updateBaseline").none { providers.gradleProperty(it).isPresent }
    if (whole) {
        doFirst { LongCheckRecords.start(records, repo, "buttonCrawlTest") }
        doLast { LongCheckRecords.finish(records, repo, "buttonCrawlTest", listOf(":desktop:buttonCrawlTest"), logger) }
    }
}
// Each store leaves a record; extensionWiringTest combines them when every store ran on every extension
val wiringWhole = !testsNarrowedOnCommandLine && !providers.gradleProperty("zoukan.e2e.package").isPresent
extensionWiringStoreTests.forEach { storeTask ->
    storeTask.configure {
        val records = longCheckRecordsDir
        val repo = repositoryRoot
        val check = name
        if (wiringWhole) {
            doFirst { LongCheckRecords.start(records, repo, check) }
            doLast { LongCheckRecords.finish(records, repo, check, listOf(":desktop:$check"), logger) }
        }
    }
}
extensionWiringTest.configure {
    val records = longCheckRecordsDir
    val repo = repositoryRoot
    val storeChecks = extensionWiringStoreTests.map { it.name }
    val allStores = selectedWiringStores.containsAll(extensionWiringStores)
    if (wiringWhole && allStores) {
        doLast { LongCheckRecords.combine(records, repo, "extensionWiringTest", storeChecks, storeChecks.map { ":desktop:$it" }, logger) }
    }
}

// Before a push to master: fails unless a record shows the long checks passed on HEAD, on a clean
// tree. It reads build/long-checks/ and, from -Pzoukan.longChecks.records, more record files or
// folders (comma-separated, relative to the repository root), such as the build/long-checks
// folders of the worktrees the three checks ran in:
//   ./gradlew :desktop:verifyLongChecks
//   ./gradlew :desktop:verifyLongChecks -Pzoukan.longChecks.records=../zoukan-crawl/desktop/build/long-checks
tasks.register("verifyLongChecks") {
    group = "verification"
    description = "Fails unless the long checks left a passing record for the current commit (before a push to master)"
    val repo = repositoryRoot
    val sources = listOf(longCheckRecordsDir) + providers.gradleProperty("zoukan.longChecks.records").orNull
        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
        ?.map { path -> File(path).let { if (it.isAbsolute) it else File(repo, path) } }
        .orEmpty()
    val parts = longCheckParts
    // Reads the records and the checkout each time
    outputs.upToDateWhen { false }
    doLast {
        val state = LongCheckRecords.gitState(repo)
        LongCheckRecords.verify(state, sources, parts)?.let { throw GradleException(it) }
        logger.lifecycle("The long checks passed on ${state.commit}.")
    }
}
// ZOUKAN <--
