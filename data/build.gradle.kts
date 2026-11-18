plugins {
    // ZOUKAN -->
    id("mihon.library.kmp")
    id("zoukan.kover")
    id("zoukan.test-home")
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
        // The module's own tests run on the desktop target, on the SQLite build the desktop app
        // uses (SQLite3MultipleCiphers through SQLDelight's JDBC driver):
        //   ./gradlew :data:desktopTest
        getByName("desktopTest") {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation(zoukanlibs.sqlite.jdbc.crypt)
                // As in the desktop app: SQLDelight's version, without the stock sqlite-jdbc
                implementation("${zoukanlibs.sqldelight.jvm.driver.get().module}:${libs.versions.sqldelight.get()}") {
                    exclude(group = "org.xerial", module = "sqlite-jdbc")
                }
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
    // ZOUKAN <--
}
