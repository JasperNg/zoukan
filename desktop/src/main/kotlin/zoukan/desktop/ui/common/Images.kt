package zoukan.desktop.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.ImageType
import zoukan.desktop.local.LocalSource
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.network.SendOnlyCookieJar
import zoukan.desktop.secrets.EncryptedFileSystem
import zoukan.desktop.secrets.EncryptedFiles
import zoukan.desktop.security.Incognito
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
            val file = CustomCovers.file(mangaId).takeIf { it.isFile } ?: return null
            return Cover(file.toURI().toString(), -1, version)
        }
    }
}

/**
 * The app's Coil image loader for covers and icons, with covers cached on disk.
 *
 * ZOUKAN: what is kept on disk (source covers, custom covers, Coil's cache of images fetched by
 * address) is encrypted with the file key, as the cookies and preference files are. Without the key
 * nothing is cached on disk: covers and images are then kept in memory only. While incognito is on,
 * nothing new is cached on disk either (source covers and images fetched by address are kept in
 * memory only); what is already cached is still read.
 */
object Images {
    private lateinit var coverDir: File
    private lateinit var diskCacheDir: File

    @Volatile
    private var codec: SettingsFileCodec? = null

    /** Whether incognito is on, for a source or (null) for the whole app; then nothing new is cached on disk. */
    @Volatile
    private var incognito: (sourceId: Long?) -> Boolean = ::incognitoFromSettings

    private val loaderDelegate = lazy {
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
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient(Injekt.get<NetworkHelper>().client) }))
                // ZOUKAN <--
                // ZOUKAN: nothing new in the disk cache while incognito is on
                add(IncognitoDiskCacheInterceptor { incognito(null) })
                add(CoverKeyer())
                // ZOUKAN: an image that declares an absurd size fails before any decoder allocates it
                add(zoukan.desktop.image.DeclaredSizeCheck())
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
            // would otherwise keep them in the system's temporary folder, outside the data folder.
            // Encrypted, and none at all without the file key.
            .diskCache { codec?.let { encryptedDiskCache(diskCacheDir, it) } }
            // ZOUKAN <--
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }
    val loader: ImageLoader by loaderDelegate

    /**
     * [codec] encrypts what is cached on disk; without one, nothing is. While [incognito] is on for a
     * cover's source (or, given null, for the whole app), nothing new is cached on disk.
     *
     * Called once at start, before any cover is read or written: it also deletes the temporary files
     * that cover writes cut short left behind (the app was closed or crashed mid-write).
     */
    fun init(
        coverDir: File,
        diskCacheDir: File = File(coverDir.parentFile, "images"),
        codec: SettingsFileCodec? = SettingsFileCodec.current,
        incognito: (sourceId: Long?) -> Boolean = ::incognitoFromSettings,
        coverPassword: () -> CharArray? = ::coverPasswordFromSecretStore,
    ) {
        this.coverDir = coverDir.apply { mkdirs() }
        CustomCovers.coverDir = this.coverDir
        CustomCovers.codec = codec
        this.diskCacheDir = diskCacheDir
        this.codec = codec
        this.incognito = incognito
        this.coverPassword = coverPassword
        // ZOUKAN -->
        EncryptedFiles.deleteTemporaryFiles(this.coverDir, CACHED_COVER_NAME)
        CustomCovers.deleteTemporaryFiles()
        // ZOUKAN <--
    }

    /** The download password, for a local entry's encrypted cover (`cover.cbi`); null when there is none. */
    @Volatile
    private var coverPassword: () -> CharArray? = ::coverPasswordFromSecretStore

    /** The download password from the OS secret store; null when it is not set or cannot be read. */
    private fun coverPasswordFromSecretStore(): CharArray? = sessionCoverPassword.get()

    private val sessionCoverPassword = CoverPasswordCache { Injekt.get<CbzPassword>() }

    // ZOUKAN -->
    /**
     * Each time the app locks, forgets what the lock hides: the download password kept for encrypted
     * covers and every decoded image in memory (decrypted covers and page previews of encrypted
     * chapters cannot be told apart from the others there). They are read again after the unlock.
     * A failure is logged and the next lock tries again. Call once at start.
     */
    fun followAppLock(locked: StateFlow<Boolean>, scope: CoroutineScope, forget: () -> Unit = ::forgetDecrypted) {
        locked.filter { it }.onEach {
            try {
                forget()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not forget the decrypted images on lock" }
            }
        }.launchIn(scope)
    }

    /** Forgets the cover password and empties the image memory cache, if the loader was made. */
    private fun forgetDecrypted() {
        forgetDecrypted(if (loaderDelegate.isInitialized()) loader.memoryCache else null, sessionCoverPassword)
    }

    internal fun forgetDecrypted(memory: MemoryCache?, passwords: CoverPasswordCache) {
        passwords.clear()
        memory?.clear()
    }
    // ZOUKAN <--

    /** The names of the source covers kept in the covers folder: [hash] of the source and address. */
    private val CACHED_COVER_NAME = Regex("[0-9a-f]{64}")

    /**
     * Incognito mode's setting (Incognito.isOn). When it cannot be read, incognito counts as on, so
     * that nothing new is cached on disk: that only costs a download.
     */
    private fun incognitoFromSettings(sourceId: Long?): Boolean = try {
        Injekt.get<Incognito>().isOn(sourceId)
    } catch (e: Exception) {
        true
    }

    /**
     * The client for images fetched by address: the app's (its DNS, Cloudflare solver and
     * connections), sending the cookies it has but keeping none the image hosts set.
     */
    internal fun imageClient(client: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
        client.newBuilder().cookieJar(SendOnlyCookieJar(client.cookieJar)).build()

    /**
     * Coil's disk cache in [dir], each file encrypted with [codec]. A cache from before encryption
     * (its journal is plain) is deleted first: it is only a cache, and it would not be read anyway.
     */
    internal fun encryptedDiskCache(dir: File, codec: SettingsFileCodec): DiskCache {
        val journal = File(dir, "journal")
        val plain = runCatching {
            journal.isFile && !EncryptedFileSystem.isEncrypted(journal.inputStream().use { it.readNBytes(64) }, codec)
        }.getOrDefault(false)
        if (plain && !zoukan.desktop.CacheFolders.deleteTree(dir.toPath())) {
            logcat(LogPriority.WARN) { "Could not delete the image cache from before encryption" }
        }
        val fileSystem = EncryptedFileSystem(FileSystem.SYSTEM, codec)
        // Temporary files of writes cut short (the app was closed or crashed mid-write)
        fileSystem.deleteTemporaryFiles(dir.toOkioPath())
        return DiskCache.Builder()
            .directory(dir.toOkioPath())
            .fileSystem(fileSystem)
            .build()
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

    /**
     * The cover's image: a custom or local cover from its file, a source cover from the disk cache,
     * downloaded first when it is not there yet.
     */
    suspend fun coverBytes(cover: Cover): ByteArray = coverBytes(cover, coverDir)

    internal suspend fun coverBytes(cover: Cover, dir: File): ByteArray = withContext(Dispatchers.IO) {
        // Custom covers and the local source's covers are files
        if (cover.url.startsWith("file:")) {
            val local = File(java.net.URI(cover.url))
            if (!local.isFile) throw IOException(localized(DMR.strings.is_missing, local.name))
            // A local source's cover is the user's file in the storage folder: read, never rewritten.
            // An encrypted one (cover.cbi) is decrypted in memory with the download password; it
            // fails without the password, and the cover then shows as broken
            return@withContext when {
                CustomCovers.isCustomCover(local) -> CustomCovers.read(local)
                LocalSource.isCoverArchive(local) -> LocalSource.readCoverArchive(local, coverPassword)
                else -> local.readBytes()
            }
        }
        val file = File(dir, hash("${cover.sourceId}:${cover.url}"))
        cachedCover(file)?.let { return@withContext it }
        val source = Injekt.get<SourceManager>().get(cover.sourceId) as? HttpSource
        val client = source?.client ?: Injekt.get<NetworkHelper>().client
        val request = if (source != null) GET(cover.url, source.headers) else GET(cover.url)
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${cover.url}")
            // ZOUKAN -->
            // An HTML or JSON body sent with status 200 is no cover: it is not cached
            val write: (OutputStream) -> Unit = { out -> response.body.byteStream().copyTo(out) }
            val codec = codec
            // While incognito is on for the source, the cover is kept in memory only
            if (codec != null && !incognito(cover.sourceId)) {
                writeCoverFile(file, codec, validate = ::requireImage, write = write)
            } else {
                // Without the file key (or in incognito) nothing is cached on disk
                ByteArrayOutputStream().also(write).toByteArray().also(::requireImage)
            }
            // ZOUKAN <--
        }
    }

    /**
     * A source cover kept in the disk cache, decrypted, or null when there is none or it cannot be
     * read now. One saved before encryption is rewritten encrypted; one that cannot be decrypted is
     * deleted (it downloads again). One that cannot be read for another reason (on Windows, a file
     * held open by another program, such as a virus scanner) is kept: for a source that is gone it
     * is the only copy.
     */
    internal fun cachedCover(file: File): ByteArray? {
        val codec = codec ?: return null
        if (!file.isFile || file.length() == 0L) return null
        val contents = try {
            EncryptedFiles.read(file, codec)
        } catch (e: EncryptedFiles.UnreadableFileException) {
            logcat(LogPriority.WARN, e) { "Could not decrypt the cached cover ${file.name}; it downloads again" }
            file.delete()
            return null
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Could not read the cached cover ${file.name}; it is kept" }
            return null
        }
        if (!contents.encrypted) {
            try {
                EncryptedFiles.write(file, contents.bytes, codec)
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not encrypt the cached cover ${file.name}" }
            }
        }
        return contents.bytes
    }

    /**
     * Writes [target] encrypted through a temporary file of its own, so two downloads of the same
     * cover at once never share (and corrupt) one file, then moves it into place, and returns the
     * image. When the move fails, a complete copy that another download put there first is kept;
     * otherwise it is an error.
     */
    internal fun writeCoverFile(
        target: File,
        codec: SettingsFileCodec,
        validate: (ByteArray) -> Unit = {},
        write: (OutputStream) -> Unit,
    ): ByteArray {
        val plain = ByteArrayOutputStream().also(write).toByteArray()
        validate(plain)
        try {
            EncryptedFiles.write(target, plain, codec)
        } catch (e: IOException) {
            // On Windows the target can be open for reading; another download's copy then stays
            if (!target.isFile || target.length() == 0L) throw IOException("Could not save the cover as ${target.name}", e)
        }
        return plain
    }

    /** Fails a downloaded cover that is no known image and looks like text (an error or login page). */
    internal fun requireImage(image: ByteArray) {
        val header = image.copyOf(minOf(image.size, 512))
        if (ImageType.fromBytes(header) == null && ImageType.looksLikeText(header)) {
            throw IOException("The cover is not an image (the server sent text or an HTML page)")
        }
    }

    internal fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Keeps what is loaded out of Coil's disk cache while [incognito] is on: the request still reads
 * what is cached there, but writes nothing new (images fetched by address stay in memory only).
 */
internal class IncognitoDiskCacheInterceptor(private val incognito: () -> Boolean) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val policy = chain.request.diskCachePolicy
        if (!policy.writeEnabled || !incognito()) return chain.proceed()
        val readOnly = if (policy.readEnabled) CachePolicy.READ_ONLY else CachePolicy.DISABLED
        return chain.withRequest(chain.request.newBuilder().diskCachePolicy(readOnly).build()).proceed()
    }
}

private class CoverKeyer : Keyer<Cover> {
    override fun key(data: Cover, options: Options) = "${data.sourceId}:${data.url}:${data.version}"
}

private class CoverFetcher(
    private val cover: Cover,
    private val dir: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult = SourceFetchResult(
        // ZOUKAN: decrypted in memory; the file on disk is encrypted
        source = ImageSource(Buffer().write(Images.coverBytes(cover, dir)), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory(private val dir: File) : Fetcher.Factory<Cover> {
        override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, dir)
    }
}

/**
 * The download password for encrypted covers (`cover.cbi`), read from the OS secret store once a
 * session and kept, so a grid of encrypted covers asks the store once, not once per cover (a denied
 * Keychain prompt would come back for each). No password (not set, or the store failed: [CbzPassword.get]
 * gives null for both) is kept for [RETRY_MILLIS] only (by a monotonic clock), so covers come back after the store is
 * unlocked without a prompt per cover. Read again after the password is set or removed in settings
 * ([CbzPassword.version]). One read at a time, so covers loading at once wait for that one read.
 * Each caller gets its own copy.
 */
internal class CoverPasswordCache(private val now: () -> Long = ::monotonicMillis, private val password: () -> CbzPassword) {
    private class Read(val version: Int, val password: CharArray?, val at: Long)

    private var last: Read? = null

    @Synchronized
    fun get(): CharArray? {
        val store = try {
            password()
        } catch (e: Exception) {
            return null
        }
        val version = store.version
        val time = now()
        // A clock that went back counts as the minute being over
        last?.takeIf { it.version == version && (it.password != null || time - it.at in 0 until RETRY_MILLIS) }?.let { return it.password?.copyOf() }
        val read = store.get()
        last = Read(version, read?.copyOf(), time)
        return read
    }

    /** Forgets (and wipes) the kept password, so the next [get] reads the store again: on lock. */
    @Synchronized
    fun clear() {
        last?.password?.fill('\u0000')
        last = null
    }

    private companion object {
        const val RETRY_MILLIS = 60_000L

        /** Milliseconds that only go forward: a wall clock set back would keep a failure for as long */
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
    }
}
