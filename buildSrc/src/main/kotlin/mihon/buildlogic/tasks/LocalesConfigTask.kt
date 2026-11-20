package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

@CacheableTask
abstract class LocalesConfigTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = resourcesDir.asFileTree
            .matching { include("**/strings.xml") }
            .filterNot { it.readText().contains(emptyResourcesElement) }
            .map {
                it.parentFile.name
                    .replace("base", "en")
                    .replace("-r", "-")
                    .replace("+", "-")
            }
            .sorted()
            .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

        val content = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
        $locales
        |</locale-config>
        """.trimMargin()

        outputDir.get().asFile.resolve("xml/locales_config.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
