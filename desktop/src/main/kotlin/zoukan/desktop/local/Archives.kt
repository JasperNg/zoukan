package zoukan.desktop.local

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import logcat.LogPriority
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.download.FileNames
import zoukan.desktop.download.ImageType
import zoukan.desktop.outputWithin
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
 */
object Archives {
    val EXTENSIONS = setOf("rar", "cbr", "7z", "cb7", "tar", "cbt")

    private const val KEEP = 8
    private const val DONE = ".done"

    // What one chapter may unpack to. Far above any real chapter (a few hundred MB at most), and
    // low enough that a small crafted archive cannot fill the disk.
    private const val MAX_ENTRY_BYTES = 1L shl 30
    private const val MAX_TOTAL_BYTES = 4L shl 30
    private const val MAX_ENTRIES = 10_000

    internal data class Limits(
        val entryBytes: Long = MAX_ENTRY_BYTES,
        val totalBytes: Long = MAX_TOTAL_BYTES,
        val entries: Int = MAX_ENTRIES,
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

    private enum class Kind { RAR, SEVEN_Z, TAR }

    private fun kind(file: File): Kind = when (file.extension.lowercase()) {
        "rar", "cbr" -> Kind.RAR
        "7z", "cb7" -> Kind.SEVEN_Z
        "tar", "cbt" -> Kind.TAR
        else -> error("Not a RAR, 7z or TAR archive: ${file.name}")
    }

    fun isArchive(file: File): Boolean = file.isFile && file.extension.lowercase() in EXTENSIONS

    /** A chapter's unpacked folder, kept in the cache until closed. */
    class Lease internal constructor(private val key: String, val dir: File) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release(key)
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
     * and its ComicInfo.xml, and leases the folder until the lease is closed.
     */
    fun open(file: File): Lease {
        val key = key(file)
        // Leased before unpacking, so another chapter's prune leaves this one alone
        acquire(key)
        try {
            val dir = synchronized(keyLocks.computeIfAbsent(key) { Any() }) { extract(file, key) }
            prune()
            return Lease(key, dir)
        } catch (e: Throwable) {
            release(key)
            throw e
        }
    }

    internal fun isLeased(dir: File): Boolean = synchronized(leaseLock) { dir.name in leases }

    private fun extract(file: File, key: String): File {
        val target = File(cacheRoot, key)
        if (File(target, DONE).exists()) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        cacheRoot.mkdirs()
        target.deleteRecursively()
        val raw = File(cacheRoot, "$key.raw")
        deleteTree(raw)
        try {
            unpack(file, raw)
            val files = regularFiles(raw)
            val images = files.filter { ImageType.isImageName(it.name) && !it.name.startsWith('.') }
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
            File(target, DONE).createNewFile()
        } catch (e: Throwable) {
            target.deleteRecursively()
            throw e
        } finally {
            deleteTree(raw)
        }
        return target
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
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walkFileTree(
                dir.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, e: IOException) = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                        runCatching { Files.delete(d) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    /**
     * The chapter's first page, for a cover, read without unpacking the archive where the format
     * allows (RAR 5 still needs the system's tar, so it is unpacked).
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
        return open(file).use { lease -> zoukan.desktop.download.ChapterDownloader.imageFiles(lease.dir).firstOrNull()?.readBytes() }
    }

    /** The archive's ComicInfo.xml, read without unpacking the pages where the format allows. */
    fun comicInfo(file: File): ComicInfoXml? {
        File(cacheRoot, key(file)).takeIf { File(it, DONE).exists() }?.let { dir ->
            return File(dir, ComicInfoXml.FILE).takeIf { it.isFile }?.inputStream()?.use { ComicInfoXml.read(it) }
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

    private fun unpack(file: File, into: File) {
        into.mkdirs()
        val limits = limits
        val budget = Budget(file.name, limits)
        try {
            when (kind(file)) {
                Kind.RAR -> Archive(file).use { rar ->
                    if (rar.isEncrypted) error("${file.name} is password protected")
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { header ->
                        write(into, header.entryName(), budget, header.fullUnpackSize) { out -> rar.extractFile(header, out) }
                    }
                }
                Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    while (true) {
                        val entry = sz.nextEntry ?: break
                        if (entry.isDirectory || !entry.hasStream()) continue
                        write(into, entry.name, budget, entry.size) { out -> copy(sz::read, out) }
                    }
                }
                Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        // A link's entry counts as a file to Commons Compress; it is not a page
                        if (!entry.isFile || entry.isSymbolicLink || entry.isLink) continue
                        write(into, entry.name, budget, entry.size) { out -> tar.copyTo(out) }
                    }
                }
            }
        } catch (e: Throwable) {
            // Too large for any reader: the system's tar would only fill the disk instead
            (budget.failure ?: e as? TooLargeException)?.let { tooLarge ->
                deleteTree(into)
                throw tooLarge
            }
            // RAR 5, or a method the Java libraries lack: the system's libarchive may read it
            deleteTree(into)
            into.mkdirs()
            if (!systemTar(file, into, limits)) {
                deleteTree(into)
                val reason = if (e is UnsupportedRarV5Exception) "RAR 5 archives need the system's tar command, which could not read it" else e.message
                throw IllegalStateException("Could not open ${file.name}: $reason. Converting it to CBZ works everywhere.", e)
            }
            logcat(LogPriority.INFO) { "Unpacked ${file.name} with the system's tar (${e::class.simpleName})" }
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
        val max = limits.entryBytes
        val bytes = input.readNBytes((max + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (bytes.size > max) throw TooLargeException("${file.name} has a page over $max bytes")
        return bytes
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

    /** Writes an entry under [root], refusing names that would land outside it. */
    private inline fun write(root: File, name: String, budget: Budget, declared: Long, block: (OutputStream) -> Unit) {
        val out = File(root, entryPath(name) ?: return)
        budget.entry(declared)
        out.parentFile.mkdirs()
        budget.counting(out.outputStream().buffered()).use(block)
    }

    /** Whether what is under [dir] is past [limits], counting links as nothing. */
    internal fun exceeds(dir: File, limits: Limits): Boolean {
        var entries = 0
        var total = 0L
        var over = false
        runCatching {
            Files.walkFileTree(
                dir.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        entries++
                        total += attrs.size()
                        over = entries > limits.entries || attrs.size() > limits.entryBytes || total > limits.totalBytes
                        return if (over) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    // tar is still writing: a file may be gone or locked for a moment
                    override fun visitFileFailed(file: Path, e: IOException) = FileVisitResult.CONTINUE
                },
            )
        }
        return over
    }

    private fun systemTar(file: File, into: File, limits: Limits): Boolean = try {
        val process = ProcessBuilder("tar", "-xf", file.absolutePath, "-C", into.absolutePath)
            .redirectErrorStream(true)
            .start()
        // tar has no cap of its own, so the folder is measured while it runs
        val tooLarge = AtomicBoolean(false)
        val watch = thread(isDaemon = true, name = "archive-size-watch") {
            try {
                while (process.isAlive) {
                    Thread.sleep(500)
                    if (exceeds(into, limits)) {
                        tooLarge.set(true)
                        process.destroyForcibly()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
        val output = try {
            process.outputWithin(5, TimeUnit.MINUTES)
        } finally {
            watch.interrupt()
            watch.join(5_000)
        }
        if (tooLarge.get() || exceeds(into, limits)) {
            throw TooLargeException("${file.name} unpacks to more than a chapter may")
        }
        if (output == null) {
            logcat(LogPriority.WARN) { "tar took too long to read ${file.name}" }
            false
        } else {
            if (output.exitCode != 0) logcat(LogPriority.WARN) { "tar could not read ${file.name}: ${output.text}" }
            output.exitCode == 0 && regularFiles(into).isNotEmpty()
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
