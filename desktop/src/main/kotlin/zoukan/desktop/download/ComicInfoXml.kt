package zoukan.desktop.download

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.io.InputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * ComicInfo.xml, read and written with the element names of core-metadata's ComicInfo (which is
 * Android-only), so files made by either app read on the other.
 */
data class ComicInfoXml(val values: Map<String, String>) {

    operator fun get(name: String): String? = values[name]?.takeIf { it.isNotBlank() }

    /** SManga.copyFromComicInfo */
    fun copyTo(manga: SManga) {
        get("Series")?.let { manga.title = it }
        get("Writer")?.let { manga.author = it }
        get("Summary")?.let { manga.description = it }
        listOfNotNull(get("Genre"), get("Tags"), get("Categories"))
            .distinct()
            .joinToString(", ") { it.trim() }
            .takeIf { it.isNotEmpty() }
            ?.let { manga.genre = it }
        listOfNotNull(get("Penciller"), get("Inker"), get("Colorist"), get("Letterer"), get("CoverArtist"))
            .flatMap { it.split(", ") }
            .distinct()
            .joinToString(", ") { it.trim() }
            .takeIf { it.isNotEmpty() }
            ?.let { manga.artist = it }
        manga.status = statusToSManga(get("PublishingStatusTachiyomi"))
    }

    /** What LocalSource takes from a chapter's ComicInfo.xml. */
    fun copyTo(chapter: SChapter) {
        get("Title")?.let { chapter.name = it }
        get("Number")?.toFloatOrNull()?.let { chapter.chapter_number = it }
        get("Translator")?.let { chapter.scanlator = it }
    }

    fun write(): String {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val root = doc.createElement("ComicInfo")
        root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema")
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        doc.appendChild(root)
        ORDER.forEach { name ->
            val value = values[name] ?: return@forEach
            val prefix = PREFIXES[name]
            val element = if (prefix != null) {
                doc.createElementNS(XSD, "$prefix:$name")
            } else {
                doc.createElement(name)
            }
            element.textContent = value
            root.appendChild(element)
        }
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    companion object {
        const val FILE = "ComicInfo.xml"
        private const val XSD = "http://www.w3.org/2001/XMLSchema"

        private val ORDER = listOf(
            "Title", "Series", "Number", "Summary", "Writer", "Penciller", "Inker", "Colorist", "Letterer",
            "CoverArtist", "Translator", "Genre", "Tags", "Web", "PublishingStatusTachiyomi", "Categories",
            "SourceMihon", "PaddingTachiyomiSY",
        )
        private val PREFIXES = mapOf(
            "PublishingStatusTachiyomi" to "ty",
            "Categories" to "ty",
            "SourceMihon" to "mh",
            "PaddingTachiyomiSY" to "tysy",
        )

        private val STATUS = listOf(
            "Ongoing" to SManga.ONGOING,
            "Completed" to SManga.COMPLETED,
            "Licensed" to SManga.LICENSED,
            "Publishing finished" to SManga.PUBLISHING_FINISHED,
            "Cancelled" to SManga.CANCELLED,
            "On hiatus" to SManga.ON_HIATUS,
            "Unknown" to SManga.UNKNOWN,
        )

        fun statusToComicInfo(status: Long): String = STATUS.firstOrNull { it.second == status.toInt() }?.first ?: "Unknown"

        fun statusToSManga(value: String?): Int = STATUS.firstOrNull { it.first == value }?.second ?: SManga.UNKNOWN

        fun read(stream: InputStream): ComicInfoXml {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = stream.use { factory.newDocumentBuilder().parse(it) }
            val values = mutableMapOf<String, String>()
            val children = doc.documentElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                val name = node.localName ?: node.tagName.substringAfter(':')
                values[name] = textOf(node)
            }
            return ComicInfoXml(values)
        }

        /**
         * The same text as [Node.getTextContent], walked with a loop: the DOM's own method recurses
         * once per nesting level, so a file nested many thousands of elements deep would end in a
         * [StackOverflowError] instead of values or a parse error.
         */
        private fun textOf(element: Element): String {
            val text = StringBuilder()
            var node: Node = element.firstChild ?: return ""
            while (true) {
                when (node.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                        if (!(node as Text).isElementContentWhitespace) text.append(node.data)
                    Node.ELEMENT_NODE, Node.ENTITY_REFERENCE_NODE -> {
                        val child = node.firstChild
                        if (child != null) {
                            node = child
                            continue
                        }
                    }
                }
                // On to the next sibling of this node or of its nearest ancestor below the value
                while (node.nextSibling == null) {
                    node = node.parentNode
                    if (node === element) return text.toString()
                }
                node = node.nextSibling
            }
        }

        /** SManga.getComicInfo, for a local source entry's details. */
        fun of(manga: SManga) = ComicInfoXml(
            buildMap {
                put("Series", manga.title)
                manga.description?.let { put("Summary", it) }
                manga.author?.let { put("Writer", it) }
                manga.artist?.let { put("Penciller", it) }
                manga.genre?.let { put("Genre", it) }
                put("PublishingStatusTachiyomi", statusToComicInfo(manga.status.toLong()))
            },
        )
    }
}
