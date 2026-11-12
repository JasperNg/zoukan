package zoukan.desktop.local

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.download.FileNames
import zoukan.desktop.download.ImageType
import java.io.File

/**
 * The local source: manga in `<storage>/local/<title>/`, each chapter a folder of images, a ZIP or
 * CBZ (encrypted ones with the download password), or an EPUB. Like the Android LocalSource
 * (source id 0, chapter URLs `<title>/<file>`), it reads `ComicInfo.xml` and the legacy
 * `details.json` for details and `cover.*` for the cover, so a local folder works on both apps.
 */
class LocalSource(
    private val folder: () -> File,
    private val password: () -> CharArray? = { null },
    private val allowHiddenFolders: () -> Boolean = { false },
) : CatalogueSource, UnmeteredSource {

    override val id: Long = ID
    override val name: String = "Local source"
    override val lang: String = "other"
    override val supportsLatest: Boolean = true

    override fun toString() = name

    private class OrderBy(selection: Selection) : Filter.Sort("Order by", arrayOf("Title", "Date"), selection)

    private val popularFilters get() = FilterList(OrderBy(Filter.Sort.Selection(0, true)))
    private val latestFilters get() = FilterList(OrderBy(Filter.Sort.Selection(1, false)))

    override fun getFilterList() = FilterList(OrderBy(Filter.Sort.Selection(0, true)))

    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", popularFilters)

    override suspend fun getLatestUpdates(page: Int) = search("", latestFilters, latest = true)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = search(query, filters, latest = false)

    private suspend fun search(query: String, filters: FilterList, latest: Boolean) = withContext(Dispatchers.IO) {
        val since = if (latest) System.currentTimeMillis() - LATEST_THRESHOLD else 0L
        var dirs = folder().listFiles().orEmpty()
            .filter { it.isDirectory && (!it.name.startsWith('.') || allowHiddenFolders()) }
            .filter { if (latest) it.lastModified() >= since else query.isBlank() || it.name.contains(query, ignoreCase = true) }
        val order = filters.filterIsInstance<OrderBy>().firstOrNull()?.state
        dirs = when {
            order?.index == 1 && order.ascending -> dirs.sortedBy { it.lastModified() }
            order?.index == 1 -> dirs.sortedByDescending { it.lastModified() }
            order?.ascending == false -> dirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> dirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        MangasPage(
            dirs.map { dir ->
                SManga.create().apply {
                    title = dir.name
                    url = dir.name
                    findCover(dir)?.let { thumbnail_url = it.toURI().toString() }
                }
            },
            false,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val details = if (fetchDetails) getMangaDetails(manga) else manga
        val list = if (fetchChapters) getChapterList(details) else chapters
        SMangaUpdate(details, list)
    }

    fun mangaDir(url: String): File? = File(folder(), url).takeIf { it.isDirectory && it.isInsideLocalFolder() }

    /** The chapter's file or folder; its URL is `<manga folder>/<chapter file>`. */
    fun chapterFile(chapterUrl: String): File {
        val (mangaDirName, chapterName) = chapterUrl.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val file = File(File(folder(), mangaDirName), chapterName)
        // URLs come from the DB and are restored verbatim from backups, so a crafted one must not
        // leave the local folder: `..` escapes it, and an absolute child makes File discard the parent
        if (!file.isInsideLocalFolder()) error("Invalid chapter path: $chapterUrl")
        if (!file.exists()) error("Chapter not found: $chapterUrl")
        return file
    }

    /** True when the canonical [file] is strictly inside the local folder: the common prefix must end at a
     * separator, so sibling folders sharing only the folder's name (e.g. `../<local>x`) are rejected too. */
    private fun File.isInsideLocalFolder(): Boolean {
        val root = folder().canonicalFile.path
        val prefix = if (root.endsWith(File.separator)) root else root + File.separator
        // A path Windows cannot canonicalize (an embedded drive, like `<folder>\C:\...`) is not inside
        return runCatching { canonicalFile.path }.getOrNull()?.startsWith(prefix) == true
    }

    private fun getMangaDetails(manga: SManga): SManga {
        val dir = mangaDir(manga.url) ?: return manga
        findCover(dir)?.let { manga.thumbnail_url = it.toURI().toString() }
        try {
            val files = dir.listFiles().orEmpty()
            val comicInfo = files.firstOrNull { it.name == ComicInfoXml.FILE }
            val comicInfoArchive = files.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            val legacyJson = files.firstOrNull { it.extension == "json" }
            val noXml = files.firstOrNull { it.name == ".noxml" }
            when {
                comicInfo != null -> comicInfo.inputStream().use { ComicInfoXml.read(it) }.copyTo(manga)
                comicInfoArchive != null -> CbzReader(comicInfoArchive, password).use { reader ->
                    reader.find(ComicInfoXml.FILE)?.let { reader.open(it).use { s -> ComicInfoXml.read(s) }.copyTo(manga) }
                }
                legacyJson != null -> readLegacyJson(legacyJson, manga)
                noXml == null -> {
                    // Take ComicInfo.xml from the first chapter archive that has one, as Android does
                    val fromChapter = files.filter { it.isFile && it.extension.lowercase() in CHAPTER_ARCHIVES }
                        .sortedWith(compareBy(FileNames.naturalOrder) { it.name })
                        .firstNotNullOfOrNull { archive -> runCatching { comicInfoOf(archive) }.getOrNull() }
                    if (fromChapter != null) {
                        // Only the series' fields, not the chapter's
                        val series = ComicInfoXml(fromChapter.values - setOf("Title", "Number", "Translator", "PaddingTachiyomiSY"))
                        File(dir, ComicInfoXml.FILE).writeText(series.write())
                        series.copyTo(manga)
                    } else {
                        File(dir, ".noxml").createNewFile()
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Reading the details of local ${manga.title} failed" }
        }
        return manga
    }

    private fun readLegacyJson(file: File, manga: SManga) {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject ?: return
        fun str(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        str("title")?.let { manga.title = it }
        str("author")?.let { manga.author = it }
        str("artist")?.let { manga.artist = it }
        str("description")?.let { manga.description = it }
        (json["genre"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.let { manga.genre = it.joinToString() }
        str("status")?.toIntOrNull()?.let { manga.status = it }
    }

    private fun getChapterList(manga: SManga): List<SChapter> {
        val dir = mangaDir(manga.url) ?: return emptyList()
        val chapters = dir.listFiles().orEmpty()
            .filterNot { it.name.startsWith('.') }
            .filter { LocalFormat.isSupported(it) }
            .filterNot { it.name == COMIC_INFO_ARCHIVE || it.name == COVER_ARCHIVE }
            .map { file ->
                SChapter.create().apply {
                    url = "${manga.url}/${file.name}"
                    name = if (file.isDirectory) file.name else file.nameWithoutExtension
                    date_upload = file.lastModified()
                    chapter_number = ChapterRecognition.parseChapterNumber(manga.title, name, chapter_number.toDouble()).toFloat()
                    runCatching { comicInfoOf(file) }.getOrNull()?.copyTo(this)
                }
            }
            .sortedWith { a, b -> FileNames.naturalOrder.compare(b.name, a.name) }

        // The cover from the first chapter when there is none
        if (findCover(dir) == null) {
            chapters.lastOrNull()?.let { first ->
                runCatching {
                    val file = chapterFile(first.url)
                    // Archives give their first page without unpacking every chapter into the cache
                    if (Archives.isArchive(file)) Archives.firstImage(file) else LocalFormat.open(file, password).use { it.firstImage() }
                }.getOrNull()?.let { bytes ->
                    val ext = ImageType.fromBytes(bytes)?.extension ?: "jpg"
                    val cover = File(dir, "cover.$ext")
                    cover.writeBytes(bytes)
                    manga.thumbnail_url = cover.toURI().toString()
                }
            }
        }
        return chapters
    }

    private fun comicInfoOf(file: File): ComicInfoXml? = when {
        file.isDirectory -> File(file, ComicInfoXml.FILE).takeIf { it.isFile }?.inputStream()?.use { s -> ComicInfoXml.read(s) }
        file.extension.lowercase() in setOf("cbz", "zip") -> CbzReader(file, password).use { r ->
            r.find(ComicInfoXml.FILE)?.let { r.open(it).use { s -> ComicInfoXml.read(s) } }
        }
        Archives.isArchive(file) -> Archives.comicInfo(file)
        else -> null
    }

    /** Pages are read from the files by the reader, not through the source. */
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        LocalFormat.open(chapterFile(chapter.url), password).use { format -> List(format.pageCount) { Page(it) } }
    }

    companion object {
        const val ID = 0L
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        private const val COVER_ARCHIVE = "cover.cbi"
        private val CHAPTER_ARCHIVES = setOf("cbz", "zip") + Archives.EXTENSIONS
        private const val LATEST_THRESHOLD = 7L * 24 * 60 * 60 * 1000

        fun findCover(dir: File): File? = dir.listFiles().orEmpty()
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageType.isImageName(it.name) }
    }
}
