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
 */
val zoukanVersion = "1.0.0"

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
    inputs.property("version", version)
    inputs.property("repo", repo)
    outputs.dir(output)
    doLast {
        output.get().file("zoukan-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$version\nreleaseRepo=${repo.get()}\n")
        }
    }
}
sourceSets.main {
    resources.srcDir(zoukanBuildInfo)
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
    // The bulk migration's smart search, as the Android app
    implementation(libs.stringSimilarity)

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
}
// ZOUKAN <--
