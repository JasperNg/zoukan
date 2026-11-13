// ZOUKAN -->
/**
 * Compile-only Android API for code shared with the desktop app.
 *
 * The extension API (`source-api`) and the network layer expose Android types such as
 * `Context`, `SharedPreferences` and `androidx.preference.PreferenceScreen`. Shared code
 * compiles against this jar: the `android.*` classes of the desktop's Android layer
 * (android-compat, then Suwayomi's android.jar), plus small stand-ins for the AndroidX classes
 * in that API.
 *
 * It is never shipped. Android supplies the real classes; the desktop app supplies them
 * at runtime through its Android compatibility layer.
 */
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The API is always what the desktop app runs: android-compat's classes first, then Suwayomi's
// android.jar for the rest. It is deliberately never the Android SDK's android.jar, even when an
// SDK is installed: then the shared code could call Android APIs the desktop does not have, the
// build would pass on that machine only, and the desktop app would fail at runtime with
// NoSuchMethodError. The Android target is unaffected: the Android Gradle plugin puts the SDK's
// android.jar on its boot classpath, ahead of this jar.
val androidJars: FileCollection = configurations.detachedConfiguration(
    dependencies.create(project(":android-compat")),
    dependencies.create(zoukanlibs.android.jar.get()),
).apply {
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    // Lets the AndroidX stand-ins extend Android classes.
    compileOnly(files(androidJars))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ androidJars.map { zipTree(it) } }) {
        include("android/**")
        exclude("META-INF/**")
    }
}
// ZOUKAN <--
