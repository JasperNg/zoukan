package zoukan.desktop.local

import org.w3c.dom.Element
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.ChapterDownloader
import zoukan.desktop.download.ImageType
import java.io.File
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A chapter on disk, from the local source or a download: a folder of images, a ZIP or CBZ
 * (encrypted or not), an EPUB, or a RAR, 7z or TAR archive ([Archives]). The pages are read in order, one at a time.
 */
sealed interface LocalFormat : AutoCloseable {
    val pageCount: Int

    fun page(index: Int): ByteArray

    /** The first image, for covers. */
    fun firstImage(): ByteArray? = if (pageCount > 0) page(0) else null

    override fun close() {}

    class Directory(dir: File) : LocalFormat {
        private val files = ChapterDownloader.imageFiles(dir)
        override val pageCount get() = files.size
        override fun page(index: Int): ByteArray = files[index].readBytes()
    }

    /** A RAR, 7z or TAR chapter, read from its unpacked copy, which stays cached until closed. */
    class Unpacked private constructor(private val lease: Archives.Lease) : LocalFormat by Directory(lease.dir) {
        constructor(file: File) : this(Archives.open(file))

        override fun close() = lease.close()
    }

    class Zip(file: File, password: () -> CharArray?) : LocalFormat {
        private val reader = CbzReader(file, password)
        private val images = reader.images
        override val pageCount get() = images.size
        override fun page(index: Int): ByteArray = synchronized(reader) { reader.read(images[index]) }
        override fun close() = synchronized(reader) { reader.close() }
    }

    /** An EPUB's images in the order of its spine, as the Android app's EpubReader reads them. */
    class Epub(file: File) : LocalFormat {
        private val reader = CbzReader(file)
        private val images: List<String> = readImagePaths()

        override val pageCount get() = images.size

        override fun page(index: Int): ByteArray =
            synchronized(reader) { reader.read(reader.find(images[index]) ?: error("${images[index]} is missing")) }

        override fun close() = synchronized(reader) { reader.close() }

        private fun xml(path: String): org.w3c.dom.Document? {
            val header = reader.find(path) ?: return null
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                // An EPUB is untrusted input and XHTML has a DOCTYPE, so the DOCTYPE stays allowed
                // but nothing outside the file is fetched: no external entities (general or
                // parameter), DTDs or schemas, which could read local files and send them out
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            return reader.open(header).use { factory.newDocumentBuilder().parse(it) }
        }

        private fun org.w3c.dom.Document.elements(name: String): List<Element> {
            val nodes = getElementsByTagNameNS("*", name)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }

        private fun resolve(base: String, href: String): String {
            val parts = (base.substringBeforeLast('/', "").split('/') + URLDecoder.decode(href.substringBefore('#'), "UTF-8").split('/'))
                .filter { it.isNotEmpty() && it != "." }
            val out = ArrayDeque<String>()
            parts.forEach { if (it == "..") out.removeLastOrNull() else out.addLast(it) }
            return out.joinToString("/")
        }

        private fun readImagePaths(): List<String> {
            val fallback = reader.images.map { it.fileName }
            val opfPath = xml("META-INF/container.xml")?.elements("rootfile")?.firstOrNull()?.getAttribute("full-path")
                ?: return fallback
            val opf = xml(opfPath) ?: return fallback
            val manifest = opf.elements("item").associate { it.getAttribute("id") to it.getAttribute("href") }
            val pages = opf.elements("itemref").mapNotNull { manifest[it.getAttribute("idref")] }.map { resolve(opfPath, it) }
            val images = pages.flatMap { page ->
                if (ImageType.isImageName(page)) return@flatMap listOf(page)
                val doc = runCatching { xml(page) }.getOrNull() ?: return@flatMap emptyList()
                (
                    doc.elements("img").map { it.getAttribute("src") } + doc.elements("image").map { el ->
                        el.getAttributeNS("http://www.w3.org/1999/xlink", "href").ifEmpty { el.getAttribute("href") }
                    }
                    ).filter { it.isNotBlank() }.map { resolve(page, it) }
            }.distinct()
            return images.ifEmpty { fallback }
        }
    }

    companion object {
        private val ARCHIVES = setOf("zip", "cbz")

        fun isSupported(file: File): Boolean =
            file.isDirectory || file.extension.lowercase() in ARCHIVES || file.extension.equals("epub", ignoreCase = true) ||
                file.extension.lowercase() in Archives.EXTENSIONS

        fun open(file: File, password: () -> CharArray? = { null }): LocalFormat = when {
            file.isDirectory -> Directory(file)
            file.extension.equals("epub", ignoreCase = true) -> Epub(file)
            file.extension.lowercase() in ARCHIVES -> Zip(file, password)
            file.extension.lowercase() in Archives.EXTENSIONS -> Unpacked(file)
            else -> error("Unknown chapter format: ${file.name}")
        }
    }
}
