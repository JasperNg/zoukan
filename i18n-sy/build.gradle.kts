plugins {
    id("mihon.library.kmp")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n.sy"

        androidResources {
            enable = true
        }

        lint {
            disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }
}

multiplatformResources {
    resourcesClassName.set("SYMR")
    resourcesPackage.set("tachiyomi.i18n.sy")
}
