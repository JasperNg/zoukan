package zoukan.desktop.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.download.ImageType
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A cover image, fetched with its source's client and headers (Referer and the like). [version]
 * changes when the image behind the same address changes (a custom cover was set).
 */
data class Cover(val url: String, val sourceId: Long, val version: Long = 0) {
    companion object {
        fun of(manga: Manga): Cover? = custom(manga.id, manga.favorite, manga.coverLastModified)
            ?: manga.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { Cover(it, manga.source, manga.coverLastModified) }

        fun of(cover: MangaCover): Cover? = custom(cover.mangaId, cover.isMangaFavorite, cover.lastModified)
            ?: cover.url?.takeIf { it.isNotBlank() }?.let { Cover(it, cover.sourceId, cover.lastModified) }

        /** A library entry's custom cover, as Android keeps them: covers/custom/<md5 of the id>. */
        private fun custom(mangaId: Long, favorite: Boolean, version: Long): Cover? {
            if (!favorite) return null
            val file = Images.customCoverFile(mangaId).takeIf { it.isFile } ?: return null
            return Cover(file.toURI().toString(), -1, version)
        }
    }
}

/** The app's Coil image loader for covers and icons, with covers cached on disk. */
object Images {
    private lateinit var coverDir: File
    private lateinit var diskCacheDir: File

    val loader: ImageLoader by lazy {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            // ZOUKAN -->
            // Coil would otherwise find coil-network-okhttp itself and fetch addresses (extension
            // icons, tracker covers, description images) with an OkHttpClient() of its own, which
            // resolves names through the system's DNS even with DNS over HTTPS on. Komikku's loader
            // uses the app's client the same way.
            .serviceLoaderEnabled(false)
            // ZOUKAN <--
            .components {
                // ZOUKAN -->
                add(OkHttpNetworkFetcherFactory(callFactory = { Injekt.get<NetworkHelper>().client }))
                // ZOUKAN <--
                add(CoverKeyer())
                add(CoverFetcher.Factory(coverDir))
                // ZOUKAN -->
                add(zoukan.desktop.ui.manga.PagePreviewKeyer())
                add(zoukan.desktop.ui.manga.PagePreviewFetcher.Factory())
                // AVIF and HEIF covers (Atsumaru's), which Coil's Skia decoder cannot read
                add(zoukan.desktop.image.FfmpegCoilDecoder.Factory())
                // ZOUKAN <--
            }
            // ZOUKAN -->
            // Images fetched by address (extension icons, tracker covers, description images); Coil
            // would otherwise keep them in the system's temporary folder, outside the data folder
            .diskCache { DiskCache.Builder().directory(diskCacheDir.toOkioPath()).build() }
            // ZOUKAN <--
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }

    fun init(coverDir: File, diskCacheDir: File = File(coverDir.parentFile, "images")) {
        this.coverDir = coverDir.apply { mkdirs() }
        this.diskCacheDir = diskCacheDir
    }

    /** Empties Coil's disk cache (Data and storage > Clear cover cache). */
    fun clearDiskCache() {
        loader.diskCache?.clear()
    }

    /** Forgets a source cover kept on disk, so it downloads again (Advanced > Refresh library covers). */
    fun forget(manga: Manga) {
        val url = manga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
        File(coverDir, hash("${manga.source}:$url")).delete()
    }

    /** Where a library entry's custom cover is kept, as Android's CoverCache. */
    fun customCoverFile(mangaId: Long): File = File(File(coverDir, "custom"), md5(mangaId.toString()))

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The cover's image file: a custom or local cover as it is, a source cover from the disk
     * cache, downloaded first when it is not there yet.
     */
    suspend fun coverFile(cover: Cover): File = coverFile(cover, coverDir)

    internal suspend fun coverFile(cover: Cover, dir: File): File {
        // Custom covers and the local source's covers are files
        if (cover.url.startsWith("file:")) {
            val local = File(java.net.URI(cover.url))
            if (!local.isFile) throw IOException(localized(DMR.strings.is_missing, local.name))
            return local
        }
        val file = File(dir, hash("${cover.sourceId}:${cover.url}"))
        if (!file.exists() || file.length() == 0L) {
            val source = Injekt.get<SourceManager>().get(cover.sourceId) as? HttpSource
            val client = source?.client ?: Injekt.get<NetworkHelper>().client
            val request = if (source != null) GET(cover.url, source.headers) else GET(cover.url)
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${cover.url}")
                // ZOUKAN -->
                // An HTML or JSON body sent with status 200 is no cover: it is not cached
                writeCoverFile(file, validate = ::requireImage) { out -> response.body.byteStream().copyTo(out) }
                // ZOUKAN <--
            }
        }
        return file
    }

    /**
     * Writes [target] through a temporary file of its own, so two downloads of the same cover at
     * once never share (and corrupt) one file, then moves it into place. When the move fails, a
     * complete copy that another download put there first is kept; otherwise it is an error.
     */
    internal fun writeCoverFile(target: File, validate: (File) -> Unit = {}, write: (OutputStream) -> Unit) {
        val tmp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            tmp.outputStream().use(write)
            validate(tmp)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // On Windows the target can be open for reading; another download's copy then stays
                if (!target.isFile || target.length() == 0L) throw IOException("Could not save the cover as ${target.name}", e)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Fails a downloaded cover that is no known image and looks like text (an error or login page). */
    internal fun requireImage(file: File) {
        val header = file.inputStream().use { it.readNBytes(512) }
        if (ImageType.fromBytes(header) == null && ImageType.looksLikeText(header)) {
            throw IOException("The cover is not an image (the server sent text or an HTML page)")
        }
    }

    internal fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

private class CoverKeyer : Keyer<Cover> {
    override fun key(data: Cover, options: Options) = "${data.sourceId}:${data.url}:${data.version}"
}

private class CoverFetcher(
    private val cover: Cover,
    private val dir: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(Images.coverFile(cover, dir).toOkioPath(), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory(private val dir: File) : Fetcher.Factory<Cover> {
        override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, dir)
    }
}
