package zoukan.desktop.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
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
import java.io.File
import java.io.IOException
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

    val loader: ImageLoader by lazy {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(CoverKeyer())
                add(CoverFetcher.Factory(coverDir))
                // ZOUKAN -->
                add(zoukan.desktop.ui.manga.PagePreviewKeyer())
                add(zoukan.desktop.ui.manga.PagePreviewFetcher.Factory())
                // ZOUKAN <--
            }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }

    fun init(coverDir: File) {
        this.coverDir = coverDir.apply { mkdirs() }
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
    override suspend fun fetch(): FetchResult {
        // The local source's covers are files
        if (cover.url.startsWith("file:")) {
            val local = File(java.net.URI(cover.url))
            if (!local.isFile) throw IOException("${local.name} is missing")
            return SourceFetchResult(
                source = ImageSource(local.toOkioPath(), FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
        val file = File(dir, Images.hash("${cover.sourceId}:${cover.url}"))
        if (!file.exists() || file.length() == 0L) {
            val source = Injekt.get<SourceManager>().get(cover.sourceId) as? HttpSource
            val client = source?.client ?: Injekt.get<NetworkHelper>().client
            val request = if (source != null) GET(cover.url, source.headers) else GET(cover.url)
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${cover.url}")
                val tmp = File(dir, "${file.name}.tmp")
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                tmp.renameTo(file)
            }
        }
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val dir: File) : Fetcher.Factory<Cover> {
        override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, dir)
    }
}
