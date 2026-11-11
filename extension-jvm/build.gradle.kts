// ZOUKAN -->
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop extension runtime: installs extensions from extension stores (Keiyoushi's signed JVM
// jars, or an APK converted with dex2jar when a store has no jar), checks their signatures, and
// loads their sources in a child-first class loader.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("mihon.code.lint")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(projects.sourceApi)
    api(projects.domain)
    implementation(projects.data)
    implementation(projects.core.common)
    implementation(projects.androidCompat)

    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.protobuf)
    implementation(libs.okhttp.core)
    implementation(libs.logcat)

    implementation(zoukanlibs.apk.parser)
    implementation(zoukanlibs.apksig)
    implementation(zoukanlibs.dex2jar.translator)
    implementation(zoukanlibs.dex2jar.tools)
    implementation(zoukanlibs.asm)
    implementation(zoukanlibs.asm.util)
    implementation(zoukanlibs.asm.analysis)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// AppInfo reports the Android app's version to extensions
val appInfo by tasks.registering {
    val appBuild = rootProject.file("app/build.gradle.kts")
    val output = layout.buildDirectory.dir("generated/appInfo")
    inputs.file(appBuild)
    outputs.dir(output)
    doLast {
        val text = appBuild.readText()
        val code = Regex("versionCode = (\\d+)").find(text)?.groupValues?.get(1) ?: "0"
        val name = Regex("versionName = \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "0"
        output.get().file("zoukan-app-info.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("versionCode=$code\nversionName=$name\n")
        }
    }
}
sourceSets.main {
    resources.srcDir(appInfo)
}
// ZOUKAN <--
