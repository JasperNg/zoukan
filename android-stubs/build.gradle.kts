// ZOUKAN -->
import java.util.Properties

/**
 * Compile-only Android API for code shared with the desktop app.
 *
 * The extension API (`source-api`) and the network layer expose Android types such as
 * `Context`, `SharedPreferences` and `androidx.preference.PreferenceScreen`. Shared code
 * compiles against this jar: the `android.*` classes of the Android SDK's API jar, plus
 * small stand-ins for the AndroidX classes in that API.
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

// The Android SDK's API jar when an SDK is installed (sdk.dir in local.properties, or ANDROID_HOME).
val androidSdkJar: File? = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.exists()) {
        Properties().apply { localProperties.inputStream().use(::load) }.getProperty("sdk.dir")
    } else {
        null
    }
    val path = fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    path?.takeIf { it.isNotBlank() }?.let {
        file(it).resolve("platforms/android-${mihon.buildlogic.AndroidConfig.COMPILE_SDK}/android.jar")
    }?.takeIf { it.exists() }
}

// Desktop-only machines have no Android SDK. There the API comes from the desktop's own Android
// layer: android-compat's classes first, then Suwayomi's android.jar for the rest.
val androidJars: FileCollection = if (androidSdkJar != null) {
    files(androidSdkJar)
} else {
    logger.info("android-stubs: no Android SDK, using android-compat and Suwayomi's android.jar")
    configurations.detachedConfiguration(
        dependencies.create(project(":android-compat")),
        dependencies.create(zoukanlibs.android.jar.get()),
    ).apply {
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
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
