plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
    // ZOUKAN <--
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                // ZOUKAN -->
                schemaOutputDirectory.set(project.file("./src/commonMain/sqldelight"))
                // ZOUKAN <--
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    // ZOUKAN -->
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.sourceApi)
                implementation(projects.domain)
                implementation(projects.core.common)

                implementation(kotlinx.serialization.json)
                implementation(kotlinx.serialization.json.okio)
                implementation(kotlinx.serialization.protobuf)

                api(libs.bundles.sqldelight)

                // Android types in shared code (Context, Build, SuppressLint); see android-stubs.
                compileOnly(projects.androidStubs)
            }
        }
    }
    // ZOUKAN <--
}
