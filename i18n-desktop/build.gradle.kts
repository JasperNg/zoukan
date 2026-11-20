plugins {
    id("mihon.library.kmp")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    android {
        namespace = "zoukan.i18n.desktop"

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
    resourcesClassName.set("DMR")
    resourcesPackage.set("zoukan.i18n.desktop")
}
