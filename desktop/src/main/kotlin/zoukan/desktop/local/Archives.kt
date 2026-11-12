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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        raw.deleteRecursively()
        try {
            unpack(file, raw)
            val files = raw.walkTopDown().filter { it.isFile }.toList()
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
            raw.deleteRecursively()
        }
        return target
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
                        ?.let { header -> rar.getInputStream(header).use { it.readBytes() } }
                }
                Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    sz.entries.filter { !it.isDirectory && it.hasStream() && it.name.isPage() }
                        .minWithOrNull(compareBy(FileNames.naturalOrder) { it.name })
                        ?.let { entry -> sz.getInputStream(entry).use { it.readBytes() } }
                }
                // One pass, keeping the bytes of the first page so far
                Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                    var best: Pair<String, ByteArray>? = null
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile || !entry.name.isPage()) continue
                        if (best == null || FileNames.naturalOrder.compare(entry.name, best.first) < 0) best = entry.name to tar.readBytes()
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
        try {
            when (kind(file)) {
                Kind.RAR -> Archive(file).use { rar ->
                    if (rar.isEncrypted) error("${file.name} is password protected")
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { header ->
                        write(into, header.entryName()) { out -> rar.extractFile(header, out) }
                    }
                }
                Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    while (true) {
                        val entry = sz.nextEntry ?: break
                        if (entry.isDirectory || !entry.hasStream()) continue
                        write(into, entry.name) { out -> copy(sz::read, out) }
                    }
                }
                Kind.TAR -> TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile) continue
                        write(into, entry.name) { out -> tar.copyTo(out) }
                    }
                }
            }
        } catch (e: Throwable) {
            // RAR 5, or a method the Java libraries lack: the system's libarchive may read it
            into.deleteRecursively()
            into.mkdirs()
            if (!systemTar(file, into)) {
                into.deleteRecursively()
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

    /** Writes an entry under [root], refusing names that would land outside it. */
    private inline fun write(root: File, name: String, block: (java.io.OutputStream) -> Unit) {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || name.startsWith("/") || parts.first().contains(':')) return
        val out = File(root, parts.joinToString(File.separator))
        out.parentFile.mkdirs()
        out.outputStream().buffered().use(block)
    }

    private fun systemTar(file: File, into: File): Boolean = try {
        val process = ProcessBuilder("tar", "-xf", file.absolutePath, "-C", into.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.outputWithin(5, TimeUnit.MINUTES)
        if (output == null) {
            logcat(LogPriority.WARN) { "tar took too long to read ${file.name}" }
            false
        } else {
            if (output.exitCode != 0) logcat(LogPriority.WARN) { "tar could not read ${file.name}: ${output.text}" }
            output.exitCode == 0 && into.walkTopDown().any { it.isFile }
        }
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
                deleted += entry.walkBottomUp().count { it.isFile }
                entry.deleteRecursively()
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
