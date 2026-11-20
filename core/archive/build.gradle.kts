plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
    // ZOUKAN <--
    kotlin("plugin.serialization")
}

// ZOUKAN -->
kotlin {
    android {
        namespace = "mihon.core.archive"
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.jsoup)
                // KMK -->
                implementation(projects.core.common)
                implementation(libs.injekt)
                // KMK <--
            }
        }
        androidMain {
            dependencies {
                implementation(libs.libarchive)
                implementation(libs.unifile)
            }
        }
    }
}
// ZOUKAN <--
