package zoukan.desktop.local

import eu.kanade.tachiyomi.core.security.SecurityPreferences
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
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.download.FileNames
import zoukan.desktop.download.ImageType
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.the_cover_is_encrypted_set_the_download_password
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The local source: manga in `<storage>/local/<title>/`, each chapter a folder of images, a ZIP or
 * CBZ (encrypted ones with the download password), or an EPUB. Like the Android LocalSource
 * (source id 0, chapter URLs `<title>/<file>`), it reads `ComicInfo.xml` and the legacy
 * `details.json` for details and `cover.*` for the cover, so a local folder works on both apps.
 * An entry whose chapters are encrypted keeps its cover encrypted too, in `cover.cbi`, as Komikku
 * does; [encryptionType] is the scheme a new one is written with (Android's preferred one).
 */
class LocalSource(
    private val folder: () -> File,
    private val password: () -> CharArray? = { null },
    private val allowHiddenFolders: () -> Boolean = { false },
    private val encryptionType: () -> SecurityPreferences.EncryptionType = { SecurityPreferences.EncryptionType.AES_256 },
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
            e.rethrowIfFatal()
            logcat(LogPriority.ERROR, e) { "Reading the details of a local entry failed" }
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
                    val bytes = if (Archives.isArchive(file)) Archives.firstImage(file) else LocalFormat.open(file, password).use { it.firstImage() }
                    // The cover of an encrypted chapter is written encrypted (cover.cbi), as Android does
                    bytes?.let { writeCover(dir, it, encrypted = isEncryptedArchive(file)) }
                }.onFailure { e ->
                    logcat(LogPriority.WARN, e) { "Could not take a local entry's cover from its first chapter" }
                }.getOrNull()?.let { cover ->
                    manga.thumbnail_url = cover.toURI().toString()
                }
            }
        }
        return chapters
    }

    /**
     * Writes [bytes] as the cover of the entry in [dir] and returns the file. [encrypted]: as
     * `cover.cbi`, a ZIP with one `cover.jpg` encrypted with the download password, as Android's
     * LocalCoverManager writes it; it fails (and writes nothing) without the password, so an
     * encrypted entry's cover is never written in plain. Otherwise as `cover.<type>`, named after
     * the image's type. The file is written beside its target and then moved over it, so a failed
     * write leaves the old cover as it was.
     */
    fun writeCover(dir: File, bytes: ByteArray, encrypted: Boolean): File {
        val encryption = if (encrypted) {
            val pw = password() ?: throw IllegalStateException(localized(DMR.strings.the_cover_is_encrypted_set_the_download_password))
            CbzEncryption(pw, encryptionType())
        } else {
            null
        }
        val cover = if (encryption != null) {
            File(dir, COVER_ARCHIVE)
        } else {
            val ext = ImageType.fromBytes(bytes)?.extension ?: "jpg"
            File(dir, "cover.$ext")
        }
        removeStaleCoverTemps(dir)
        val tmp = File.createTempFile(".cover.", ".tmp", dir)
        try {
            if (encryption != null) {
                CbzWriter(tmp, encryption).use { it.write(COVER_ARCHIVE_ENTRY, bytes) }
            } else {
                FileOutputStream(tmp).use { it.write(bytes) }
            }
            Files.move(tmp.toPath(), cover.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        return cover
    }

    /**
     * Deletes the temporary files of cover writes that were stopped half way (the app was killed)
     * from [dir]: only names [writeCover] gives them (`.cover.<digits>.tmp`), only regular files,
     * never a link, and only when older than [STALE_COVER_TEMP_MS], so a write going on now keeps
     * its file. Anything that cannot be checked or deleted stays.
     */
    private fun removeStaleCoverTemps(dir: File) {
        val names = dir.list() ?: return
        val cutoff = System.currentTimeMillis() - STALE_COVER_TEMP_MS
        for (name in names) {
            if (!COVER_TEMP_NAME.matches(name)) continue
            val path = File(dir, name).toPath()
            try {
                val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (attrs.isRegularFile && !attrs.isSymbolicLink && attrs.lastModifiedTime().toMillis() < cutoff) Files.deleteIfExists(path)
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not remove the stale cover file $name" }
            }
        }
    }

    /**
     * Whether a cover written into the entry's [dir] must be encrypted (`cover.cbi`): its cover is
     * `cover.cbi` already, or its details (`ComicInfo.cbm`) or a chapter archive are encrypted, as
     * [getChapterList] decides for a cover taken from the first chapter. Android writes a plain
     * cover for such an entry when its cover is set by hand; the desktop never does.
     */
    fun coverMustBeEncrypted(dir: File): Boolean =
        findCover(dir)?.let(::isCoverArchive) == true ||
            File(dir, COMIC_INFO_ARCHIVE).isFile ||
            dir.listFiles().orEmpty().any { !it.name.startsWith('.') && isEncryptedArchive(it) }

    /** Whether [file] is a ZIP or CBZ whose entries are encrypted. */
    private fun isEncryptedArchive(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf("cbz", "zip") &&
            runCatching { net.lingala.zip4j.ZipFile(file).use { it.isEncrypted } }.getOrDefault(false)

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
        const val COVER_ARCHIVE = "cover.cbi"

        /** What File.createTempFile names [writeCover]'s temporary file: the prefix, a number, the suffix. */
        private val COVER_TEMP_NAME = Regex("""\.cover\.\d+\.tmp""")

        /** How old a cover's temporary file must be to be taken for a stopped write's. */
        private const val STALE_COVER_TEMP_MS = 60L * 60 * 1000

        /** The one entry of [COVER_ARCHIVE], as Android names it whatever the image is. */
        private const val COVER_ARCHIVE_ENTRY = "cover.jpg"
        private val CHAPTER_ARCHIVES = setOf("cbz", "zip") + Archives.EXTENSIONS
        private const val LATEST_THRESHOLD = 7L * 24 * 60 * 60 * 1000

        /**
         * The entry's cover in [dir]: a `cover.*` image, or the encrypted `cover.cbi`, the first
         * one listed, as Android's LocalCoverManager.find takes it.
         */
        fun findCover(dir: File): File? = dir.listFiles().orEmpty()
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageType.isImageName(it.name) || isCoverArchive(it) }

        /** Whether the cover [file] is an encrypted cover archive (`cover.cbi`), as Android's ImageUtil.isImage tells it. */
        fun isCoverArchive(file: File): Boolean = file.extension.equals("cbi", ignoreCase = true)

        /**
         * The image in the encrypted cover archive [file], read into memory with the download
         * [password]. Fails without the password, with a wrong one or on a broken archive.
         */
        fun readCoverArchive(file: File, password: () -> CharArray?): ByteArray = CbzReader(file, password).use { reader ->
            val entry = reader.images.firstOrNull() ?: reader.entries.firstOrNull() ?: throw IOException("${file.name} holds no image")
            reader.read(entry)
        }
    }
}
