// ZOUKAN -->
// Zoukan, the desktop app: the Compose UI and reader (Phase 4 of the desktop port) on top of the
// runtime (the Android compatibility layer, Injekt wiring, the encrypted database, preferences,
// networking, the extension runtime, the built-in browser) and the command-line tools.
//
//   ./gradlew :desktop:run                              # the app
//   ./gradlew :desktop:runTools --args="harness --store <url>"
//   ./gradlew :desktop:run --args="browser https://example.org"
//   ./gradlew :desktop:packageMsi                       # Windows installer (Phase 7, see RELEASING.md)
//   ./gradlew :desktop:packageDmg                       # macOS disk image (notarizeDmg to notarize)

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
val syncAppShared = tasks.register<Sync>("syncAppShared") {
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
val zoukanBuildInfo = tasks.register("zoukanBuildInfo") {
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
val syncAndroidResources = tasks.register<Sync>("syncAndroidResources") {
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
val syncAppIcon = tasks.register<Sync>("syncAppIcon") {
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
val syncAppSharedTests = tasks.register<Sync>("syncAppSharedTests") {
    from(rootProject.file("app/src/test/kotlin")) { include("Tester.kt") }
    into(layout.buildDirectory.dir("generated/app-shared-tests"))
}
sourceSets.test {
    kotlin.srcDir(syncAppSharedTests)
}
val testStandins = sourceSets.create("testStandins") {
    compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
}
val tools = sourceSets.create("tools") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
kotlin {
    target.compilations.getByName("tools").associateWith(target.compilations.getByName("main"))
    target.compilations.getByName("test").associateWith(target.compilations.getByName("tools"))
}
sourceSets.test {
    compileClasspath += testStandins.output + tools.output + tools.runtimeClasspath
    runtimeClasspath += testStandins.output + tools.output + tools.runtimeClasspath
}

// The Android dispatcher is declared for Android targets only. Its MainDispatcherFactory outranks
// Swing's and would make Dispatchers.Main the emulated Android main looper; the UI needs the AWT
// event thread (see also Main.kt). This exclude guards against a new dependency bringing it back,
// for the app, tools, and every test task (they all use the test runtime classpath). AndroidCompat
// keeps its own HandlerDispatcher for Handler.asCoroutineDispatcher().
configurations.matching {
    it.name == "runtimeClasspath" || it.name == "testRuntimeClasspath" || it.name == "toolsRuntimeClasspath"
}.configureEach {
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
    "toolsImplementation"(zoukanlibs.asm)

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
    testRuntimeOnly(libs.junit.platform.launcher)
    // ContextStubsTest builds the emulated Context's files config in a temporary folder
    testImplementation(zoukanlibs.typesafe.config)
    // Property-based tests (zoukan.desktop.property), run as ordinary JUnit tests
    testImplementation(libs.kotest.property)
    // ExtensionFilePropertyTest signs its own fixture APK (the app only verifies with it)
    testImplementation(zoukanlibs.apksig)
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

val runTools = tasks.register<JavaExec>("runTools") {
    group = "application"
    description = "Runs the desktop developer tools (harness, backup check/compare/synthetic)"
    classpath = tools.runtimeClasspath
    mainClass.set("zoukan.desktop.ToolsMainKt")
    jvmArgs = jvmFlags
    workingDir = rootDir
    standardInput = System.`in`
    listOf("zoukan.cefDebug", "zoukan.cefArgs", "zoukan.dataDir").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

// Each Test task gets its own home and data folder under build/test-home/<task> (zoukan.test-home)

/** The test classes [wiringTest] must run; a rename needs this one list changed. */
val expectedWiringClasses = listOf("InjektRegistrationsTest", "ScreenSmokeTest", "ZoukanRuntimeStartTest")

/** The tests [wiringTest] runs instead of `test`. */
val wiringTests = expectedWiringClasses.map { "*$it" }

/** The offline user journeys [journeyTest] runs instead of `test`, in zoukan.desktop.e2e and its subpackages. */
val journeyTests = listOf("zoukan.desktop.e2e.*JourneyTest")

tasks.test {
    useJUnitPlatform()
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
        file("packaging/check/install-check-windows.ps1"),
    ).withPropertyName("releaseFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // The wiring tests start the runtime, once per JVM, so wiringTest runs them in their own JVMs
    filter { (wiringTests + journeyTests).forEach { excludeTestsMatching(it) } }
}

// The wiring tests catch what compiles but crashes at run time: a lookup with no Injekt
// registration, a screen or dialog that throws when it opens. They need no network, so `check`
// (and so `build`) runs them on every change:
/**
 * Whether a task on the command line runs only some of its tests: Gradle takes the filter as
 * `--tests Foo` and as `--tests=Foo`. A narrowed run must not vouch for the whole check
 * (wiringTest's list of classes). BuildSettingsTest checks every such check uses this.
 */
fun narrowsTests(args: List<String>): Boolean = args.any { it == "--tests" || it.startsWith("--tests=") }
val testsNarrowedOnCommandLine = gradle.startParameter.taskRequests.any { narrowsTests(it.args) }

//   ./gradlew :desktop:wiringTest
val wiringTest = tasks.register<Test>("wiringTest") {
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
    // classes it scans to the forks in turn
    include(wiringTests.map { "**/${it.removePrefix("*")}.class" })
    forkEvery = 1
    maxParallelForks = 2
    systemProperty("kotlinx.coroutines.fast.service.loader", "false")
    systemProperty("user.language", "en")
    // Runs only the smoke checks whose name matches, to debug one: -Pzoukan.smoke.only="dialog new category"
    providers.gradleProperty("zoukan.smoke.only").orNull?.let { systemProperty("zoukan.smoke.only", it) }
    shouldRunAfter(tasks.test)
}
tasks.named("check") { dependsOn(wiringTest) }

// Installs every extension of each sample store and checks it is wired up on the desktop: no
// class, method or field its bytecode uses is missing or a stub, its Injekt lookups resolve, its
// sources, client, headers and settings work (ExtensionWiringCheck). The stores are
// SampleStores.ALL, which only the tests define (the app names no store): Keiyoushi, and yuzono's
// "Cursed" store, which the app does not add or trust. HarnessRunsTest checks this list matches. Each store runs in a Test task and JVM of its own
// (extensionWiringTest<Store>), one after the other: Keiyoushi alone needs a 4 GB heap. It downloads
// every extension, so it is not part of `check`; the nightly workflow runs it. The reports go to
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
        classpath = sourceSets.test.get().runtimeClasspath.minus(testStandins.output)
        useJUnitPlatform()
        filter { includeTestsMatching("*ExtensionWiringTest") }
        systemProperty("zoukan.e2e", "true")
        systemProperty("zoukan.e2e.store", store)
        systemProperty("zoukan.fileSecrets", "true")
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
val extensionWiringTest = tasks.register("extensionWiringTest") {
    group = "verification"
    description = "Checks every extension of each sample store is wired up on the desktop (-Pzoukan.e2e.stores to pick)"
    dependsOn(extensionWiringStoreTests.filterIndexed { i, _ -> extensionWiringStores[i] in selectedWiringStores })
}

// The user journeys drive the real UI against a local fixture server (src/test/kotlin/zoukan/
// desktop/e2e): browse a source, read every image format and check the pages drew, change a
// source's settings, download chapters and read them offline, back up and restore. They need no
// network but take a few minutes, so they run on request and in ci.yml, not on every build:
//   ./gradlew :desktop:journeyTest
// Screenshots go to desktop/build/reports/journeys/.
val journeyTest = tasks.register<Test>("journeyTest") {
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
    // dots), so a journey must run here
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
    // Each journey that ran, as it ends
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
// ZOUKAN <--
