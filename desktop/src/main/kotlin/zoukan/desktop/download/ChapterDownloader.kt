package zoukan.desktop.download

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Downloads one chapter into its manga folder, as the Android app's Downloader does: pages go to
 * `<chapter>_tmp/` as `001.jpg`, `002.png`…, which then becomes `<chapter>.cbz` (optionally
 * encrypted) or is renamed to `<chapter>/`. A `_tmp` folder left by an interrupted download is
 * resumed: pages already in it are not fetched again.
 */
class ChapterDownloader(
    private val pageList: suspend () -> List<Page>,
    private val imageUrl: suspend (Page) -> String,
    private val image: suspend (Page) -> ByteArray,
    private val parallelPages: Int,
) {
    data class Options(
        val asCbz: Boolean,
        val encryption: CbzEncryption?,
        val comicInfo: ComicInfoXml?,
        // ZOUKAN -->
        /** Android's "split tall images" ([TallImageSplitter]) */
        val splitTallImages: Boolean = false,
        // ZOUKAN <--
    )

    /**
     * @param onProgress pages done and the page count
     * @return the chapter's CBZ or folder
     */
    suspend fun download(
        mangaDir: File,
        chapterName: String,
        options: Options,
        onProgress: (Int, Int) -> Unit,
    ): File {
        mangaDir.mkdirs()
        val tmp = File(mangaDir, chapterName + DownloadPaths.TMP_SUFFIX).apply { mkdirs() }
        tmp.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }

        val pages = pageList()
            .mapIndexed { i, page -> Page(i, page.url, page.imageUrl, page.uri) }
        if (pages.isEmpty()) error("The source returned no pages")
        val digits = pages.size.toString().length.coerceAtLeast(3)
        var done = 0
        onProgress(0, pages.size)

        val semaphore = Semaphore(parallelPages.coerceIn(1, 10))
        coroutineScope {
            pages.map { page ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        val base = "%0${digits}d".format(Locale.ENGLISH, page.number)
                        // A page from before an interruption counts as done, once a split it was in is redone
                        val existing = TallImageSplitter.settle(tmp, base, options.splitTallImages)
                        if (existing.isEmpty()) {
                            if (page.imageUrl.isNullOrEmpty()) page.imageUrl = imageUrl(page)
                            val bytes = retry(3) { image(page) }
                            if (bytes.isEmpty()) error("Page ${page.number} is empty")
                            val ext = ImageType.fromBytes(bytes)?.extension ?: "jpg"
                            val part = File(tmp, "$base.tmp")
                            part.writeBytes(bytes)
                            val image = File(tmp, "$base.$ext")
                            Files.move(part.toPath(), image.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            if (options.splitTallImages) TallImageSplitter.splitIfTall(image, base)
                        }
                        synchronized(this@ChapterDownloader) { done++ }
                        onProgress(done, pages.size)
                    }
                }
            }.awaitAll()
        }

        // Split pages are several files with the page's number before "__"
        val pagesDone = imageFiles(tmp).map { it.nameWithoutExtension.substringBefore("__") }.distinct().size
        if (pagesDone != pages.size) error("Downloaded $pagesDone of ${pages.size} pages")

        File(tmp, ComicInfoXml.FILE).delete()
        options.comicInfo?.let { File(tmp, ComicInfoXml.FILE).writeText(it.write()) }

        return if (options.asCbz) {
            val target = File(mangaDir, "$chapterName.cbz")
            val part = File(mangaDir, "$chapterName.cbz${DownloadPaths.TMP_SUFFIX}")
            CbzWriter(part, options.encryption).use { writer ->
                tmp.listFiles().orEmpty().filter { it.isFile }.sortedWith(compareBy(FileNames.naturalOrder) { it.name })
                    .forEach { writer.write(it.name, it.readBytes()) }
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            tmp.deleteRecursively()
            target
        } else {
            val target = File(mangaDir, chapterName)
            if (target.exists()) target.deleteRecursively()
            if (!tmp.renameTo(target)) error("Could not rename ${tmp.name}")
            target
        }
    }

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        throw last!!
    }

    companion object {
        /** The page images of a downloaded chapter folder, in order. */
        fun imageFiles(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && ImageType.isImageName(f.name) && !f.name.startsWith('.') }
                .orEmpty()
                .sortedWith(compareBy(FileNames.naturalOrder) { it.name })
    }
}
