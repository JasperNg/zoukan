import mihon.buildlogic.tasks.LocalesConfigTask

plugins {
    id("mihon.library.kmp")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n"

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
    resourcesPackage.set("tachiyomi.i18n")
}

val localesConfigTask = tasks.register<LocalesConfigTask>("generateLocalesConfig") {
    resourcesDir.set(layout.projectDirectory.dir("src/commonMain/moko-resources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(localesConfigTask, LocalesConfigTask::outputDir)
    }
}
