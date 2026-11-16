// ZOUKAN -->
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The desktop's JVM modules (desktop, extension-jvm, android-compat): Java 21, the JetBrains Runtime the app ships. */
plugins {
    id("org.jetbrains.kotlin.jvm")
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
// ZOUKAN <--
