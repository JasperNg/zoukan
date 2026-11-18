package zoukan.desktop.storage

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * A storage folder as the Android app (Komikku) leaves it, written into [root] for the storage tests.
 *
 * Every name here is written out by hand from the Android code, not computed with the desktop's
 * code, so a desktop naming rule that drifts from Android's is caught:
 *
 * - `downloads/` (StorageManager, with the `.nomedia` DiskUtil makes there), then
 *   `<source name> (<LANG>)/<entry title>/<chapter>` (DownloadProvider): names through
 *   DiskUtil.buildValidFilename, which turns `"*:/<>?\|` and control characters into `_` and trims
 *   dots and spaces at the ends; a chapter is `[<scanlator>_]<name>`, `Chapter` for a blank name, with
 *   `_<first 6 of the URL's MD5>` only when "include chapter URL hash" was on.
 * - A chapter folder holds `001.png`, ... (Downloader: page number padded to the page count's
 *   digits, at least 3), the `ComicInfo.xml` it writes and a `.nomedia`; a CBZ holds the pages and
 *   the `ComicInfo.xml`. An interrupted download is left as `<chapter>_tmp/`.
 * - `local/<entry>/` (LocalSource): `cover.jpg`, a series `ComicInfo.xml`, chapters as folders and CBZs.
 * - `autobackup/app.komikku_<yyyy-MM-dd_HH-mm>.tachibk` (BackupCreator.getFilename), copies of the real
 *   exports in the test resources.
 *
 * All titles, sources and images are invented; the images are a few pixels.
 */
internal class AndroidStorageFolder(val root: File) {

    data class Source(val id: Long, val name: String, val lang: String, val dirName: String)
    data class Entry(val id: Long, val source: Source, val title: String, val dirName: String)
    data class Chapter(
        val id: Long,
        val entry: Entry,
        val name: String,
        val scanlator: String?,
        val url: String,
        /** The download's name in the entry's folder, or null when it is not downloaded. */
        val downloadName: String?,
    )

    val northwind = Source(1001, "Northwind Comics", "en", "Northwind Comics (EN)")
    val unicode = Source(1002, "Ünïcode: Reader", "ja", "Ünïcode_ Reader (JA)")
    val lantern = Source(1003, "Paper Lantern", "pt-BR", "Paper Lantern (PT-BR)")
    val sources = listOf(northwind, unicode, lantern)

    val harbor = Entry(1, northwind, "Harbor Lights", "Harbor Lights")
    val whatIf = Entry(2, northwind, "What If? / Part 2", "What If_ _ Part 2")
    val sea = Entry(3, unicode, "海の灯", "海の灯")
    val dots = Entry(4, unicode, "...Trailing dots...", "Trailing dots")
    val lanternDays = Entry(5, lantern, "Lantern Days", "Lantern Days")
    val entries = listOf(harbor, whatIf, sea, dots, lanternDays)

    val chapters = listOf(
        // A folder of images, ':' made '_'
        Chapter(101, harbor, "Ch. 1: Arrival", null, "/c/101", "Ch. 1_ Arrival"),
        // A CBZ with the scanlator in front
        Chapter(102, harbor, "Ch. 2 - Storm", "Moonlit Scans", "/c/102", "Moonlit Scans_Ch. 2 - Storm.cbz"),
        // Not downloaded: only its unfinished download is there
        Chapter(103, harbor, "Ch. 3", null, "/c/103", null),
        Chapter(104, harbor, "Ch. 4 <Finale>?", null, "/c/104", "Ch. 4 _Finale__.cbz"),
        // Downloaded while "include chapter URL hash" was on
        Chapter(201, whatIf, "Chapter 1", null, "/w/201", "Chapter 1_${md5("/w/201").take(6)}.cbz"),
        // A blank name
        Chapter(202, whatIf, "  ", null, "/w/202", "Chapter"),
        Chapter(301, sea, "第1話", null, "/s/301", "第1話"),
        Chapter(401, dots, "Vol.1 Ch.1", null, "/d/401", "Vol.1 Ch.1.cbz"),
        Chapter(501, lanternDays, "Capítulo 1", "Grupo: Luz", "/l/501", "Grupo_ Luz_Capítulo 1.cbz"),
    )

    /** The local source entry with a cover and a series ComicInfo.xml, and one with neither. */
    val localEntry = "Harbor Atlas"
    val localBareEntry = "Sketchbook"

    /** The automatic backups: Android's names, the newest first. */
    val newestBackup = File(root, "autobackup/app.komikku_2026-10-30_21-15.tachibk")
    val olderBackup = File(root, "autobackup/app.komikku_2026-10-29_09-00.tachibk")

    fun chapterFile(chapter: Chapter): File? =
        chapter.downloadName?.let { File(root, "downloads/${chapter.entry.source.dirName}/${chapter.entry.dirName}/$it") }

    fun write(newestBackupBytes: ByteArray, olderBackupBytes: ByteArray) {
        file("downloads/.nomedia", ByteArray(0))
        for (chapter in chapters) {
            val name = chapter.downloadName ?: continue
            val path = "downloads/${chapter.entry.source.dirName}/${chapter.entry.dirName}/$name"
            val comicInfo = chapterComicInfo(chapter)
            if (name.endsWith(".cbz")) {
                cbz(path, listOf("001.png" to png(1), "002.png" to png(2), "ComicInfo.xml" to comicInfo))
            } else {
                file("$path/001.png", png(1))
                file("$path/002.png", png(2))
                file("$path/ComicInfo.xml", comicInfo)
                file("$path/.nomedia", ByteArray(0))
            }
        }
        // An interrupted download of chapter 103
        file("downloads/${northwind.dirName}/${harbor.dirName}/Ch. 3_tmp/001.png.tmp", png(3))

        file("local/$localEntry/cover.jpg", jpg())
        file("local/$localEntry/ComicInfo.xml", seriesComicInfo())
        file("local/$localEntry/Chapter 1/001.png", png(4))
        file("local/$localEntry/Chapter 1/002.png", png(5))
        cbz(
            "local/$localEntry/Chapter 2.cbz",
            listOf("001.png" to png(6), "002.png" to png(7), "003.png" to png(8), "ComicInfo.xml" to localChapterComicInfo()),
        )
        cbz("local/$localBareEntry/Sketch 1.cbz", listOf("001.png" to png(9)))

        file(newestBackup.relativeTo(root).path, newestBackupBytes)
        file(olderBackup.relativeTo(root).path, olderBackupBytes)
    }

    private fun file(relative: String, bytes: ByteArray) = File(root, relative).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    private fun cbz(relative: String, entries: List<Pair<String, ByteArray>>) {
        val out = File(root, relative).apply { parentFile.mkdirs() }
        ZipOutputStream(out.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** What the Android Downloader writes into a chapter (Manga.getComicInfo, through the app's XML settings). */
    private fun chapterComicInfo(chapter: Chapter): ByteArray = comicInfo(
        buildList {
            add("Title" to chapter.name)
            add("Series" to chapter.entry.title)
            add("Number" to "1")
            chapter.scanlator?.let { add("Translator" to it) }
            add("Web" to "https://example.org${chapter.url}")
        },
        listOf("PublishingStatusTachiyomi" to "Ongoing", "SourceMihon" to chapter.entry.source.name),
    )

    private fun seriesComicInfo(): ByteArray = comicInfo(
        listOf(
            "Series" to "Harbor Atlas: Collected",
            "Summary" to "Charts of an invented coast.",
            "Writer" to "Cora Mapmaker",
            "Penciller" to "Dee Inkwell",
            "Genre" to "Adventure, Maps",
        ),
        listOf("PublishingStatusTachiyomi" to "Completed"),
    )

    private fun localChapterComicInfo(): ByteArray = comicInfo(
        listOf("Title" to "The Second Chart", "Number" to "2", "Translator" to "Atlas Team"),
        emptyList(),
    )

    private fun comicInfo(plain: List<Pair<String, String>>, namespaced: List<Pair<String, String>>): ByteArray = buildString {
        append("<?xml version='1.0' encoding='UTF-8'?>\n")
        append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        plain.forEach { (k, v) -> append("  <$k>${escape(v)}</$k>\n") }
        namespaced.forEach { (k, v) ->
            val prefix = if (k == "SourceMihon") "mh" else "ty"
            append("  <$prefix:$k xmlns:$prefix=\"http://www.w3.org/2001/XMLSchema\">${escape(v)}</$prefix:$k>\n")
        }
        append("</ComicInfo>")
    }.toByteArray()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val FIXED_TIME = 1_700_000_000_000L

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** A 2 by 2 PNG whose colour is [n], so each page's bytes differ. */
        fun png(n: Int): ByteArray = image(n, "png")

        fun jpg(): ByteArray = image(42, "jpg")

        private fun image(n: Int, format: String): ByteArray {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, n * 0x010203) }
            return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
        }
    }
}
