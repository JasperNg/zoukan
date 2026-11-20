package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@CacheableTask
abstract class PrepareShortcutsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shortcutFile: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(shortcutFile.get().asFile)

        val packageName = applicationId.get()
        val shortcuts = document.getElementsByTagName("shortcut")
        for (i in 0 until shortcuts.length) {
            val shortcut = shortcuts.item(i) as? Element ?: continue
            val intents = shortcut.getElementsByTagName("intent")
            for (j in 0 until intents.length) {
                val intent = intents.item(j) as? Element ?: continue
                intent.setAttribute("android:targetPackage", packageName)
            }
        }

        val outFile = outputDir.get().asFile.resolve("xml/shortcuts.xml")
        outFile.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            transform(DOMSource(document), StreamResult(outFile))
        }
    }
}
