package zoukan.desktop.local

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import logcat.LogPriority
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import zoukan.desktop.download.ChapterDownloader
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.download.FileNames
import zoukan.desktop.download.ImageType
import zoukan.desktop.secrets.EncryptedFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import kotlin.concurrent.thread

/**
 * RAR, 7z and TAR chapters, which the Android app reads with libarchive.
 *
 * RAR 1.5 to 4 goes through junrar, 7z and TAR through Commons Compress. RAR 5, and anything those
 * libraries cannot read (a 7z with an unusual filter, say), falls back to the system's `tar`
 * command, which is bsdtar (libarchive) on macOS and on Windows 10 and later.
 *
 * Those formats have no cheap random access (a solid 7z unpacks everything before a page), so a
 * chapter is unpacked once into a cache folder and read from there like a folder chapter. The cache
 * keeps the last [KEEP] chapters, and never removes one that is open ([open] leases it).
 *
 * ZOUKAN: each cached page (and ComicInfo.xml) is encrypted with the file key, as the cover cache
 * is; entries are unpacked into memory one at a time and written encrypted, so no page is ever
 * plain on disk, the system's tar included (it streams the entries to the app as a TAR). A cached
 * chapter that cannot be decrypted (another key) is unpacked again. Without the file key nothing
 * is cached: an open chapter's pages are then kept in memory only, up to [Limits.memoryBytes]
 * (an eighth of the heap), and a larger chapter is refused with [TooLargeException].
 */
object Archives {
    val EXTENSIONS = setOf("rar", "cbr", "7z", "cb7", "tar", "cbt")

    private const val KEEP = 8
    private const val DONE = ".done"

    // What [DONE] holds, encrypted: a folder whose marker decrypts to it was written whole with this key
    private val DONE_TOKEN = "zoukan-archive-cache-1".toByteArray(Charsets.US_ASCII)

    // What the file key's encryption adds to a page (a header, a nonce and a tag), with room to spare
    private const val ENCRYPTION_OVERHEAD = 1024L

    // What one chapter may unpack to. Far above any real chapter (a few hundred MB at most), and
    // low enough that a small crafted archive cannot fill the disk.
    private const val MAX_ENTRY_BYTES = 1L shl 30
    private const val MAX_TOTAL_BYTES = 4L shl 30
    private const val MAX_ENTRIES = 10_000

    // What one page or cover may be when it is read into memory. Far above any real page, and low
    // enough that a crafted archive cannot exhaust the heap.
    private const val MAX_PAGE_BYTES = 64L shl 20

    // What a chapter's pages may come to when they are held in memory (no file key, so no cache):
    // an eighth of the heap, since the reader may hold two chapters at once with room to spare.
    private fun defaultMemoryBytes(): Long = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(MAX_TOTAL_BYTES)

    internal data class Limits(
        val entryBytes: Long = MAX_ENTRY_BYTES,
        val totalBytes: Long = MAX_TOTAL_BYTES,
        val entries: Int = MAX_ENTRIES,
        val pageBytes: Long = MAX_PAGE_BYTES,
        val memoryBytes: Long = defaultMemoryBytes(),
    )

    /** The caps on unpacking; only tests change them. */
    @Volatile
    internal var limits = Limits()

    /** An archive that would unpack to more than [limits] allows. */
    class TooLargeException(message: String) : IllegalStateException(message)

    /** Counts what one archive unpacks to, and fails once it passes [limits]. */
    private class Budget(private val archive: String, private val limits: Limits) {
        private var entries = 0
        private var total = 0L

        /** The first failure, kept because a library may wrap what a stream throws. */
        var failure: TooLargeException? = null
            private set

        private fun fail(what: String): Nothing =
            throw TooLargeException("$archive unpacks to $what, which is more than a chapter may").also { failure = it }

        /** Counts an entry about to be written; [declared] is the size its header claims, when known. */
        fun entry(declared: Long = 0) {
            if (++entries > limits.entries) fail("more than ${limits.entries} files")
            if (declared > limits.entryBytes) fail("a file over ${limits.entryBytes} bytes")
            if (declared > limits.totalBytes - total) fail("more than ${limits.totalBytes} bytes")
        }

        /** [out], counting the bytes written to it as one entry's. */
        fun counting(out: OutputStream): OutputStream = object : OutputStream() {
            private var written = 0L

            private fun add(n: Int) {
                written += n
                total += n
                if (written > limits.entryBytes) fail("a file over ${limits.entryBytes} bytes")
                if (total > limits.totalBytes) fail("more than ${limits.totalBytes} bytes")
            }

            override fun write(b: Int) {
                add(1)
                out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                add(len)
                out.write(b, off, len)
            }

            override fun flush() = out.flush()
            override fun close() = out.close()
        }
    }

    @Volatile
    var cacheRoot: File = File(System.getProperty("java.io.tmpdir"), "zoukan-archives")

    /** The file key that encrypts the cache, or null for no cache on disk; only tests change it. */
    @Volatile
    internal var codecSource: () -> SettingsFileCodec? = { SettingsFileCodec.current }

    // The cache folder [prepareCache] has gone through
    @Volatile
    private var prepared: File? = null

    private enum class Kind { RAR, SEVEN_Z, TAR }

    private fun kind(file: File): Kind = when (file.extension.lowercase()) {
        "rar", "cbr" -> Kind.RAR
        "7z", "cb7" -> Kind.SEVEN_Z
        "tar", "cbt" -> Kind.TAR
        else -> error("Not a RAR, 7z or TAR archive: ${file.name}")
    }

    fun isArchive(file: File): Boolean = file.isFile && file.extension.lowercase() in EXTENSIONS

    /**
     * A chapter's unpacked pages: its folder in the cache, kept until closed, or (without the file
     * key) its pages in memory.
     */
    class Lease internal constructor(private val key: String?, private val pages: Pages) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        val pageCount: Int get() = pages.count

        /** The page's bytes; throws [TooLargeException] for a page over [Limits.pageBytes]. */
        fun page(index: Int): ByteArray = pages.read(index)

        override fun close() {
            if (closed.compareAndSet(false, true) && key != null) release(key)
        }
    }

    internal interface Pages {
        val count: Int
        fun read(index: Int): ByteArray
    }

    /** The encrypted pages of a cached folder, in order. */
    private class CachedPages(dir: File, private val codec: SettingsFileCodec) : Pages {
        private val files = ChapterDownloader.imageFiles(dir)
        override val count get() = files.size
        override fun read(index: Int): ByteArray = readCachedPage(files[index], codec)
    }

    /** The pages in memory, in order; one over the cap was kept cut short at the cap plus a byte. */
    private class MemoryPages(private val pages: List<ByteArray>) : Pages {
        override val count get() = pages.size
        override fun read(index: Int): ByteArray {
            val max = limits.pageBytes
            return pages[index].also { if (it.size > max) throw TooLargeException("Page ${index + 1} is over $max bytes, more than a page may be") }
        }
    }

    // Open folders by cache key, guarded by [leaseLock]; prune() never removes these
    private val leases = HashMap<String, Int>()
    private val leaseLock = Any()

    // One lock per archive, so unpacking one chapter never waits for another
    private val keyLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun acquire(key: String) = synchronized(leaseLock) { leases[key] = (leases[key] ?: 0) + 1 }

    private fun release(key: String) = synchronized(leaseLock) {
        val left = (leases[key] ?: 1) - 1
        if (left <= 0) leases.remove(key) else leases[key] = left
    }

    /**
     * Unpacks the chapter's pages (renamed `0001.jpg`, `0002.png`… in the archive's natural order)
     * and its ComicInfo.xml, encrypted, and leases the folder until the lease is closed. Without
     * the file key, the pages are unpacked into memory and nothing is written.
     */
    fun open(file: File): Lease {
        val codec = codecSource()
        prepareCache(codec)
        if (codec == null) return Lease(null, unpackToMemory(file))
        val key = key(file)
        // Leased before unpacking, so another chapter's prune leaves this one alone
        acquire(key)
        try {
            val dir = synchronized(keyLocks.computeIfAbsent(key) { Any() }) { extract(file, key, codec) }
            prune()
            return Lease(key, CachedPages(dir, codec))
        } catch (e: Throwable) {
            release(key)
            throw e
        }
    }

    /**
     * Goes once through the cache folder: removes what is not a whole chapter encrypted with the
     * current key (the plain cache from before encryption, chapters cut short by a crash, the
     * temporary `.raw` folders), and everything when there is no key. Only this cache's own folder.
     */
    fun prepareCache(codec: SettingsFileCodec? = codecSource()) {
        val root = cacheRoot
        if (prepared == root) return
        synchronized(leaseLock) {
            if (prepared == root) return
            root.listFiles().orEmpty()
                .filterNot { it.name in leases || it.name.removeSuffix(".raw") in leases }
                .forEach { entry ->
                    val keep = codec != null && entry.isDirectory && !entry.name.endsWith(".raw") && isComplete(entry, codec)
                    if (!keep) runCatching { deleteTree(entry) }.onFailure { logcat(LogPriority.WARN, it) { "Could not clear ${entry.name} from the chapter cache" } }
                }
            prepared = root
        }
    }

    /** Whether [dir] is a whole unpacked chapter written with [codec]'s key. */
    private fun isComplete(dir: File, codec: SettingsFileCodec): Boolean = runCatching {
        val stored = File(dir, DONE).takeIf { it.isFile && it.length() < 4096 }?.readBytes() ?: return false
        codec.isEncoded(stored) && codec.decode(stored).contentEquals(DONE_TOKEN)
    }.getOrDefault(false)

    /** A cached page, decrypted: no more than [Limits.pageBytes]. */
    private fun readCachedPage(file: File, codec: SettingsFileCodec): ByteArray {
        val max = limits.pageBytes
        if (file.length() > max + ENCRYPTION_OVERHEAD) throw TooLargeException("${file.name} is over $max bytes, more than a page may be")
        val bytes = EncryptedFiles.read(file, codec).bytes
        if (bytes.size > max) throw TooLargeException("${file.name} is over $max bytes, more than a page may be")
        return bytes
    }

    internal fun isLeased(dir: File): Boolean = synchronized(leaseLock) { dir.name in leases }

    private fun extract(file: File, key: String, codec: SettingsFileCodec): File {
        val target = File(cacheRoot, key)
        if (isComplete(target, codec)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        cacheRoot.mkdirs()
        // Half written, from before encryption, or with another key: unpacked again
        deleteTree(target)
        val raw = File(cacheRoot, "$key.raw")
        deleteTree(raw)
        try {
            unpack(file, FolderSink(raw, codec))
            val files = regularFiles(raw)
            val images = files.filter { isPageName(it.name) }
                .map { it.relativeTo(raw).invariantSeparatorsPath to it }
                .sortedWith(compareBy(FileNames.naturalOrder) { it.first })
            if (images.isEmpty()) error("${file.name} has no images")
            target.mkdirs()
            val digits = images.size.toString().length.coerceAtLeast(4)
            images.forEachIndexed { i, (_, image) ->
                val name = "%0${digits}d.%s".format(i + 1, image.extension.lowercase())
                Files.move(image.toPath(), File(target, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            files.firstOrNull { it.name.equals(ComicInfoXml.FILE, ignoreCase = true) }
                ?.let { Files.move(it.toPath(), File(target, ComicInfoXml.FILE).toPath(), StandardCopyOption.REPLACE_EXISTING) }
            File(target, DONE).writeBytes(codec.encode(DONE_TOKEN))
        } catch (e: Throwable) {
            deleteTree(target)
            throw e
        } finally {
            deleteTree(raw)
        }
        return target
    }

    /** The chapter's pages unpacked into memory, in the archive's natural order: no file key, no cache. */
    private fun unpackToMemory(file: File): Pages {
        val sink = MemorySink(file.name, limits.memoryBytes)
        unpack(file, sink)
        val images = sink.entries.filterKeys { isPageName(it.substringAfterLast('/')) }.toList()
            .sortedWith(compareBy(FileNames.naturalOrder) { it.first })
            .map { it.second }
        if (images.isEmpty()) error("${file.name} has no images")
        return MemoryPages(images)
    }

    private fun isPageName(name: String) = ImageType.isImageName(name) && !name.startsWith('.')

    /** Where unpacked entries go, by their path in the archive (see [entryPath]). */
    internal interface Sink {
        /** Whether the entry at [path] is read into memory and [put] here; the others are only counted. */
        fun wants(path: String): Boolean {
            val fileName = path.substringAfterLast(File.separatorChar)
            return isPageName(fileName) || fileName.equals(ComicInfoXml.FILE, ignoreCase = true)
        }

        fun put(path: String, bytes: ByteArray)

        /** Forgets what was put, before another reader tries the archive. */
        fun reset()
    }

    /** Each entry encrypted into its own file under [dir]. */
    private class FolderSink(private val dir: File, private val codec: SettingsFileCodec) : Sink {
        init {
            dir.mkdirs()
        }

        override fun put(path: String, bytes: ByteArray) {
            val out = File(dir, path)
            out.parentFile.mkdirs()
            out.writeBytes(codec.encode(bytes))
        }

        override fun reset() {
            deleteTree(dir)
            dir.mkdirs()
        }
    }

    /**
     * The entries in memory, no more than [max] bytes in all: past that the chapter is refused
     * rather than risk running the app out of memory.
     */
    private class MemorySink(private val archive: String, private val max: Long) : Sink {
        val entries = LinkedHashMap<String, ByteArray>()
        private var held = 0L

        override fun put(path: String, bytes: ByteArray) {
            val key = path.replace(File.separatorChar, '/')
            val replaced = entries[key]?.size ?: 0
            if (held - replaced + bytes.size > max) {
                throw TooLargeException(
                    "$archive cannot be opened without the file key from the system's secret store: its pages would be " +
                        "held in memory, and they come to more than ${max shr 20} MB. Converting it to CBZ, or making " +
                        "the secret store available, lets it open.",
                )
            }
            entries[key] = bytes
            held += bytes.size - replaced
        }

        override fun reset() {
            entries.clear()
            held = 0
        }
    }

    /**
     * Only the first page in natural order: an entry is read into memory only when it sorts before
     * the page kept so far, so a cover holds one page at a time and writes nothing to disk.
     */
    internal class FirstPageSink : Sink {
        var first: Pair<String, ByteArray>? = null
            private set

        override fun wants(path: String): Boolean {
            if (!isPageName(path.substringAfterLast(File.separatorChar))) return false
            val best = first ?: return true
            return FileNames.naturalOrder.compare(path.replace(File.separatorChar, '/'), best.first) < 0
        }

        override fun put(path: String, bytes: ByteArray) {
            if (wants(path)) first = path.replace(File.separatorChar, '/') to bytes
        }

        override fun reset() {
            first = null
        }
    }

    /** Keeps up to [max] + 1 bytes of what is written, and drops the rest: a page over the cap stays marked as such. */
    private class Capture(private val max: Long) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        private val keep = (max + 1).coerceAtMost(Int.MAX_VALUE.toLong() - 16).toInt()

        override fun write(b: Int) {
            if (buffer.size() < keep) buffer.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = keep - buffer.size()
            if (room > 0) buffer.write(b, off, minOf(room, len))
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    /**
     * The real files under [root]. A symbolic link is never one, nor is anything behind a linked
     * folder: the system's tar creates the links an archive holds, and a page that is a link would
     * hand the reader whatever file it points to.
     */
    internal fun regularFiles(root: File): List<File> =
        Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map { it.toFile() }.collect(Collectors.toList())
        }

    /** Deletes [dir] without following links, so a linked folder loses the link and not its files. */
    private fun deleteTree(dir: File) {
        zoukan.desktop.download.SafeDelete.deleteTreeNoFollow(dir.toPath())
    }

    /**
     * The chapter's first page, for a cover, read without unpacking the archive where the format
     * allows (RAR 5 still needs the system's tar, which is read through for its first page alone).
     */
    fun firstImage(file: File): ByteArray? {
        fun String.isPage() = ImageType.isImageName(this) && !substringAfterLast('/').startsWith('.')
        val direct: ByteArray? = try {
            when (kind(file)) {
                Kind.RAR -> Archive(file).use { rar ->
                    rar.fileHeaders.filter { !it.isDirectory && it.entryName().isPage() }
                        .minWithOrNull(compareBy(FileNames.naturalOrder) { it.entryName() })
                        ?.let { header -> rar.getInputStream(header).use { readCapped(file, it) } }
                }
                Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    sz.entries.filter { !it.isDirectory && it.hasStream() && it.name.isPage() }
                        .minWithOrNull(compareBy(FileNames.naturalOrder) { it.name })
                        ?.let { entry -> sz.getInputStream(entry).use { readCapped(file, it) } }
                }
                // One pass, keeping the bytes of the first page so far
                Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                    var best: Pair<String, ByteArray>? = null
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile || entry.isSymbolicLink || entry.isLink || !entry.name.isPage()) continue
                        if (best == null || FileNames.naturalOrder.compare(entry.name, best.first) < 0) best = entry.name to readCapped(file, tar)
                    }
                    best?.second
                }
            }
        } catch (_: UnsupportedRarV5Exception) {
            null
        }
        if (direct != null) return direct
        return firstUnpackedPage(file)
    }

    /**
     * The first page of a chapter the Java readers could not read directly (RAR 5): from the cache
     * when the chapter is already there, or else by reading the archive through and keeping that
     * one page in memory, without unpacking the chapter into the cache or holding all its pages.
     */
    internal fun firstUnpackedPage(file: File): ByteArray? {
        codecSource()?.let { codec ->
            prepareCache(codec)
            val key = key(file)
            acquire(key)
            try {
                val dir = File(cacheRoot, key)
                if (isComplete(dir, codec)) {
                    ChapterDownloader.imageFiles(dir).firstOrNull()?.let { return readCachedPage(it, codec) }
                }
            } finally {
                release(key)
            }
        }
        val sink = FirstPageSink()
        unpack(file, sink)
        val bytes = sink.first?.second ?: return null
        val max = limits.pageBytes
        if (bytes.size > max) throw TooLargeException("${file.name} has a page over $max bytes")
        return bytes
    }

    /** The archive's ComicInfo.xml, read without unpacking the pages where the format allows. */
    fun comicInfo(file: File): ComicInfoXml? {
        codecSource()?.let { codec ->
            prepareCache(codec)
            val dir = File(cacheRoot, key(file))
            if (isComplete(dir, codec)) {
                val info = File(dir, ComicInfoXml.FILE).takeIf { it.isFile } ?: return null
                // One that cannot be decrypted is read from the archive instead
                runCatching { EncryptedFiles.read(info, codec).bytes }.getOrNull()?.let { return ComicInfoXml.read(it.inputStream()) }
            }
        }
        return when (kind(file)) {
            Kind.RAR -> try {
                Archive(file).use { rar ->
                    rar.fileHeaders.firstOrNull { !it.isDirectory && it.entryName().isComicInfo() }
                        ?.let { header -> rar.getInputStream(header).use { ComicInfoXml.read(it) } }
                }
            } catch (_: UnsupportedRarV5Exception) {
                null
            }
            Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                sz.entries.firstOrNull { !it.isDirectory && it.name.isComicInfo() }
                    ?.let { entry -> sz.getInputStream(entry).use { ComicInfoXml.read(it) } }
            }
            Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                generateSequence { tar.nextEntry }
                    .firstOrNull { !it.isDirectory && it.name.isComicInfo() }
                    ?.let { ComicInfoXml.read(tar) }
            }
        }
    }

    private fun String.isComicInfo() = substringAfterLast('/').equals(ComicInfoXml.FILE, ignoreCase = true)

    private fun com.github.junrar.rarfile.FileHeader.entryName(): String =
        (fileName ?: "").replace('\\', '/')

    private fun unpack(file: File, sink: Sink) {
        val limits = limits
        val budget = Budget(file.name, limits)
        try {
            when (kind(file)) {
                Kind.RAR -> Archive(file).use { rar ->
                    if (rar.isEncrypted) error("${file.name} is password protected")
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { header ->
                        unpackEntry(sink, header.entryName(), budget, header.fullUnpackSize) { out -> rar.extractFile(header, out) }
                    }
                }
                Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    while (true) {
                        val entry = sz.nextEntry ?: break
                        if (entry.isDirectory || !entry.hasStream()) continue
                        unpackEntry(sink, entry.name, budget, entry.size) { out -> copy(sz::read, out) }
                    }
                }
                Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar -> readTar(tar, sink, budget) }
            }
        } catch (e: Throwable) {
            // Too large for any reader: the system's tar would only take as long to say so
            (budget.failure ?: e as? TooLargeException)?.let { tooLarge ->
                sink.reset()
                throw tooLarge
            }
            // RAR 5, or a method the Java libraries lack: the system's libarchive may read it
            sink.reset()
            if (!systemTar(file, sink, Budget(file.name, limits))) {
                sink.reset()
                val reason = if (e is UnsupportedRarV5Exception) "RAR 5 archives need the system's tar command, which could not read it" else e.message
                throw IllegalStateException("Could not open ${file.name}: $reason. Converting it to CBZ works everywhere.", e)
            }
            logcat(LogPriority.INFO) { "Unpacked ${file.name} with the system's tar (${e::class.simpleName})" }
        }
    }

    /** The TAR's files into [sink]; a link's entry counts as a file to Commons Compress, and is not a page. */
    private fun readTar(tar: TarArchiveInputStream, sink: Sink, budget: Budget) {
        while (true) {
            val entry = tar.nextEntry ?: break
            if (!entry.isFile || entry.isSymbolicLink || entry.isLink) continue
            unpackEntry(sink, entry.name, budget, entry.size) { out -> tar.copyTo(out) }
        }
    }

    private inline fun copy(read: (ByteArray) -> Int, out: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }

    /** One entry's bytes, for a cover: no more than a page may unpack to. */
    private fun readCapped(file: File, input: InputStream): ByteArray {
        val max = minOf(limits.entryBytes, limits.pageBytes)
        val bytes = input.readNBytes((max + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (bytes.size > max) throw TooLargeException("${file.name} has a page over $max bytes")
        return bytes
    }

    /** One unpacked page's bytes, read into memory: no more than [Limits.pageBytes]. */
    internal fun readPage(file: File): ByteArray {
        val max = limits.pageBytes
        if (file.length() > max) throw TooLargeException("${file.name} is over $max bytes, more than a page may be")
        return file.readBytes()
    }

    /**
     * An entry's path under the folder it unpacks to, or null for a name that would land outside
     * it: an absolute path, one with `..`, or one with a `:` (a drive, or an NTFS stream).
     */
    internal fun entryPath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || name.startsWith("/") || parts.any { it == ".." || it.contains(':') }) return null
        return parts.joinToString(File.separator)
    }

    /**
     * Reads an entry, refusing names that would land outside the chapter's folder, and puts it in
     * [sink] when the sink wants it (a page or ComicInfo.xml): read into memory up to
     * [Limits.pageBytes] and a byte, so one over the cap is still refused when it is read. Other
     * files are only counted.
     */
    private inline fun unpackEntry(sink: Sink, name: String, budget: Budget, declared: Long, block: (OutputStream) -> Unit) {
        val path = entryPath(name) ?: return
        budget.entry(declared)
        val keep = sink.wants(path)
        val capture = if (keep) Capture(limits.pageBytes) else null
        budget.counting(capture ?: OutputStream.nullOutputStream()).use(block)
        if (capture != null) sink.put(path, capture.bytes())
    }

    /**
     * Reads [file] with the system's tar (bsdtar, which is libarchive), which writes its entries as
     * a TAR to the app (`tar -cf - @archive`) instead of unpacking them onto the disk, so that they
     * go through [sink] (encrypted) and [budget] like any other reader's. False when there is no
     * such tar, or it could not read the archive.
     */
    private fun systemTar(file: File, sink: Sink, budget: Budget): Boolean = try {
        val process = ProcessBuilder("tar", "-cf", "-", "@${file.absolutePath}").start()
        process.outputStream.close()
        // What tar says when it fails, for the log; read aside so a full pipe never stops it
        val errors = StringBuffer()
        val errorReader = thread(isDaemon = true, name = "archive-tar-errors") {
            runCatching {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { if (errors.length < 4096) errors.appendLine(it) }
                }
            }
        }
        val timedOut = AtomicBoolean(false)
        val watch = thread(isDaemon = true, name = "archive-tar-watch") {
            try {
                if (!process.waitFor(5, TimeUnit.MINUTES)) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }
        try {
            val input = process.inputStream
            val read = runCatching { readTar(TarArchiveInputStream(input.buffered()), sink, budget) }
            budget.failure?.let { throw it }
            (read.exceptionOrNull() as? TooLargeException)?.let { throw it }
            // The padding after the last entry, so tar finishes writing and exits on its own
            runCatching { input.transferTo(OutputStream.nullOutputStream()) }
            val exit = process.waitFor()
            errorReader.join(5_000)
            when {
                timedOut.get() -> {
                    logcat(LogPriority.WARN) { "tar took too long to read ${file.name}" }
                    false
                }
                exit != 0 || read.isFailure -> {
                    logcat(LogPriority.WARN, read.exceptionOrNull()) { "tar could not read ${file.name}: $errors" }
                    false
                }
                else -> true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            watch.interrupt()
            runCatching { process.inputStream.close() }
        }
    } catch (e: TooLargeException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "No system tar to read ${file.name}" }
        false
    }

    private fun key(file: File): String {
        val id = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** The unpacked chapters on disk, in bytes: Settings > Data and storage's chapter cache. */
    fun cacheSize(): Long = cacheRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Deletes every unpacked chapter not open in the reader; returns how many files went. */
    fun clearCache(): Int = synchronized(leaseLock) {
        var deleted = 0
        cacheRoot.listFiles().orEmpty()
            .filterNot { it.name in leases || it.name.removeSuffix(".raw") in leases }
            .forEach { entry ->
                deleted += if (entry.isDirectory) regularFiles(entry).size else 1
                deleteTree(entry)
            }
        deleted
    }

    private fun prune() {
        synchronized(leaseLock) {
            cacheRoot.listFiles { f -> f.isDirectory && !f.name.endsWith(".raw") }
                .orEmpty()
                .filterNot { it.name in leases }
                .sortedByDescending { it.lastModified() }
                .drop((KEEP - leases.size).coerceAtLeast(0))
                .forEach { it.deleteRecursively() }
        }
    }
}
