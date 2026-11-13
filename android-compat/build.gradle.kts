// ZOUKAN -->
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

/**
 * Android compatibility layer for the desktop app: Suwayomi-Server's AndroidCompat (MPL-2.0),
 * vendored from Suwayomi-Server at d5215280 (see README.md). It implements the Android classes
 * extensions use (Context, SharedPreferences, WebView, Bitmap, QuickJs, ...) on the JVM.
 *
 * Zoukan changes are marked with ZOUKAN comments. The vendored code keeps Suwayomi's formatting, so
 * this module is not formatted by Spotless.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// The desktop app runs on JDK 21 (JetBrains Runtime); AndroidCompat needs Java 21 language features
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
}

sourceSets {
    main {
        // Kotlin and Java sources share one folder, as in Suwayomi
        java.srcDir("src/main/java")
        kotlin.srcDir("src/main/java")
    }
}

dependencies {
    // The android.* classes AndroidCompat does not replace. AndroidCompat's own classes come first
    // on the classpath, so they win over the stubs in this jar.
    api(zoukanlibs.android.jar)
    compileOnly(zoukanlibs.xmlpull)
    compileOnly(zoukanlibs.androidx.annotation)

    api(libs.injekt)
    implementation(kotlinx.reflect)
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.json.okio)
    implementation(libs.rxjava)
    implementation(libs.jsoup)

    implementation(zoukanlibs.slf4j.api)
    implementation(zoukanlibs.logback.classic)
    implementation(zoukanlibs.kotlin.logging)
    implementation(zoukanlibs.appdirs)
    implementation(zoukanlibs.typesafe.config)
    implementation(zoukanlibs.config4k)
    implementation(zoukanlibs.jackson.annotations)
    implementation(zoukanlibs.multiplatform.settings.jvm)
    implementation(zoukanlibs.multiplatform.settings.serialization.jvm)

    // Android's SimpleDateFormat and friends
    implementation(zoukanlibs.icu4j)
    // QuickJs and Duktape on GraalJS
    implementation(zoukanlibs.graalvm.polyglot)
    implementation(zoukanlibs.graalvm.js)
    // Bitmap: OpenJDK lacks a JPEG encoder and a WebP decoder
    implementation(zoukanlibs.bundles.twelvemonkeys)
    implementation(zoukanlibs.webp.imageio)

    // WebView on JCEF (Chromium)
    api(zoukanlibs.jcefmaven)

    // Package manager
    implementation(zoukanlibs.apk.parser)
    compileOnly(zoukanlibs.apksig)
    implementation(zoukanlibs.dex2jar.translator)
    implementation(zoukanlibs.dex2jar.tools)
    // BytecodeEditor (extension bytecode rewrites)
    implementation(zoukanlibs.asm)
}

// Suwayomi's android.jar is served from a GitHub repository (pinned to a commit in
// settings.gradle.kts), not from a signed Maven repository, and the build has no project-wide
// dependency verification. Check this one artifact against a known SHA-256 before anything
// compiles against it. To update the jar: change the commit and version, then put the new jar's
// `shasum -a 256` here.
val androidJarSha256 = "d719e8c95a224f970e4d8d5ceabf1a0f90afa9e7b5da62b64bf76f29cad269f5"

val verifyAndroidJar by tasks.registering {
    group = "verification"
    description = "Checks Suwayomi's android.jar against its expected SHA-256."
    val jar: FileCollection = configurations.detachedConfiguration(
        dependencies.create(zoukanlibs.android.jar.get()),
    ).apply { isTransitive = false }
    val expected = androidJarSha256
    val marker = layout.buildDirectory.file("verification/android-jar.sha256")
    inputs.files(jar)
    inputs.property("sha256", expected)
    outputs.file(marker)
    doLast {
        val file = jar.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Suwayomi's android.jar (${file.name}) has SHA-256 $actual, expected $expected. " +
                "The artifact changed upstream or was tampered with; do not build against it."
        }
        marker.get().asFile.writeText("$actual\n")
    }
}

// Everything that uses the jar (this module, android-stubs, the desktop app) compiles this module first
tasks.named("compileKotlin") { dependsOn(verifyAndroidJar) }
tasks.named("compileJava") { dependsOn(verifyAndroidJar) }
tasks.named("check") { dependsOn(verifyAndroidJar) }
// ZOUKAN <--
