package zoukan.extension

import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What an extension's AndroidManifest.xml says, read with the same keys and rules as Komikku's
 * ExtensionLoader on Android.
 */
data class ExtensionManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String?,
    val features: List<String>,
    val metaData: Map<String, String>,
) {
    val name: String
        get() = metaData[METADATA_NAME] ?: label?.substringAfter("Tachiyomi: ") ?: packageName

    val isNsfw: Boolean
        get() = (metaData[METADATA_CONTENT_WARNING]?.toIntOrNull() ?: 0) > 0 || metaData[METADATA_NSFW] == "1"

    val factory: String?
        get() = metaData[METADATA_SOURCE_FACTORY]

    /** The source classes, with a leading '.' expanded to the package name. */
    val sourceClasses: List<String>
        get() = metaData[METADATA_SOURCE_CLASS].orEmpty()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) packageName + it else it }

    /**
     * The extensions-lib version, from tachiyomix.extensionLib or else the version name, as on
     * Android. Throws when the package is not an extension or its lib version is not supported.
     */
    fun validate(): Double {
        if (EXTENSION_FEATURE !in features) {
            throw ExtensionException(ExtensionException.Stage.MANIFEST, "$packageName is not an extension")
        }
        if (versionName.isEmpty()) {
            throw ExtensionException(ExtensionException.Stage.MANIFEST, "$packageName has no version name")
        }
        if (sourceClasses.isEmpty()) {
            throw ExtensionException(ExtensionException.Stage.MANIFEST, "$packageName has no $METADATA_SOURCE_CLASS")
        }
        val libVersion = metaData[METADATA_EXTENSION_LIB]?.toDoubleOrNull()?.takeUnless { it == 0.0 }
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "Lib version is $libVersion, while only version(s) ${SUPPORTED_LIB_VERSIONS.joinToString()} " +
                    "are supported",
            )
        }
        return libVersion
    }

    companion object {
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
        const val METADATA_NSFW = "tachiyomi.extension.nsfw"
        const val METADATA_NAME = "tachiyomix.name"
        const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
        const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

        // Same as ExtensionLoader.SUPPORTED_LIB_VERSIONS on Android
        val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

        /** Reads the text AndroidManifest.xml a Keiyoushi jar (or a jar converted by Zoukan) carries. */
        fun fromJar(jar: File): ExtensionManifest = ZipFile(jar).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw ExtensionException(ExtensionException.Stage.MANIFEST, "No AndroidManifest.xml in ${jar.name}")
            parse(zip.getInputStream(entry).use { it.readAllBytes() }.decodeToString())
        }

        /** Reads the text AndroidManifest.xml from a jar's [contents]; [jarName] is for messages. */
        fun fromContents(contents: JarContents, jarName: String): ExtensionManifest {
            val bytes = contents["AndroidManifest.xml"]
                ?: throw ExtensionException(ExtensionException.Stage.MANIFEST, "No AndroidManifest.xml in $jarName")
            return parse(bytes.decodeToString())
        }

        /** Decodes the binary AndroidManifest.xml of an APK. */
        fun fromApk(apk: File): ExtensionManifest = ApkFile(apk).use { parse(it.manifestXml) }

        /** The text of an APK's manifest, to store in a converted jar. */
        fun manifestXmlOfApk(apk: File): String = ApkFile(apk).use { it.manifestXml }

        fun parse(xml: String): ExtensionManifest {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // The manifest is untrusted input: no DTDs or external entities
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val root = doc.documentElement
            fun Element.android(name: String) = getAttribute("android:$name")
            val features = doc.getElementsByTagName("uses-feature").let { nodes ->
                (0..<nodes.length).mapNotNull { (nodes.item(it) as Element).android("name").ifEmpty { null } }
            }
            val metaData = doc.getElementsByTagName("meta-data").let { nodes ->
                (0..<nodes.length).associate {
                    val e = nodes.item(it) as Element
                    e.android("name") to e.android("value")
                }
            }
            val label = (doc.getElementsByTagName("application").item(0) as? Element)
                ?.android("label")
                ?.ifEmpty { null }
            return ExtensionManifest(
                packageName = root.getAttribute("package"),
                versionCode = root.android("versionCode").toLongOrNull()
                    ?: throw ExtensionException(ExtensionException.Stage.MANIFEST, "Missing versionCode"),
                versionName = root.android("versionName"),
                label = label,
                features = features,
                metaData = metaData,
            )
        }
    }
}

class ExtensionException(
    val stage: Stage,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Stage { DOWNLOAD, SIGNATURE, MANIFEST, CONVERT, LOAD }
}
