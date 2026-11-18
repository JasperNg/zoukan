// ZOUKAN -->
package eu.kanade.tachiyomi.network

import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * A file system held only in memory, for [okhttp3.Cache]: nothing it is given ever reaches the
 * disk, and all of it is gone when the app quits.
 *
 * Memory is bounded: a write fails with an [IOException] when it would make one file larger than
 * [maxFileBytes], or all files together larger than [maxTotalBytes] (files for which [uncapped]
 * is true, such as the cache's small journal, count towards the total but are never refused for
 * it). OkHttp's cache treats such a failure as "do not store this response"; the response itself
 * still reaches its caller.
 *
 * Thread-safe. A file being read keeps the contents it had when it was opened, and a file deleted
 * while open can still be read through that source (as on Unix), so the cache sees a "civilized"
 * file system. Relative paths are taken from the root. Symbolic links and file handles are not
 * supported.
 */
class MemoryFileSystem(
    private val maxTotalBytes: Long,
    private val maxFileBytes: Long,
    private val uncapped: (Path) -> Boolean = { false },
) : FileSystem() {
    private class Node(var path: Path) {
        val data = Buffer()
        var live = true
    }

    private val lock = Any()
    private val files = HashMap<Path, Node>()
    private val dirs = hashSetOf(ROOT)
    private var totalBytes = 0L

    /** The bytes all files hold together. */
    val size: Long get() = synchronized(lock) { totalBytes }

    private fun absolute(path: Path): Path = if (path.isAbsolute) path.normalized() else ROOT.resolve(path, normalize = true)

    override fun canonicalize(path: Path): Path {
        val absolute = absolute(path)
        synchronized(lock) {
            if (absolute !in files && absolute !in dirs) throw FileNotFoundException("no such file: $path")
        }
        return absolute
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        val absolute = absolute(path)
        synchronized(lock) {
            files[absolute]?.let { return FileMetadata(isRegularFile = true, size = it.data.size) }
            if (absolute in dirs) return FileMetadata(isDirectory = true)
        }
        return null
    }

    override fun list(dir: Path): List<Path> = listOrNull(dir) ?: throw FileNotFoundException("not a directory: $dir")

    override fun listOrNull(dir: Path): List<Path>? {
        val absolute = absolute(dir)
        synchronized(lock) {
            if (absolute !in dirs) return null
            return (files.keys.asSequence() + dirs.asSequence())
                .filter { it.parent == absolute && it != absolute }
                .sorted()
                .toList()
        }
    }

    override fun openReadOnly(file: Path): FileHandle = throw IOException("file handles are not supported")

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        throw IOException("file handles are not supported")

    override fun source(file: Path): Source {
        val absolute = absolute(file)
        synchronized(lock) {
            val node = files[absolute] ?: throw FileNotFoundException("no such file: $file")
            // Shares the bytes (okio copies segments only when they are written)
            return node.data.copy()
        }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val absolute = absolute(file)
        synchronized(lock) {
            val existing = files[absolute]
            if (existing != null) {
                if (mustCreate) throw IOException("$file already exists")
                totalBytes -= existing.data.size
                existing.data.clear()
                return NodeSink(existing)
            }
            return NodeSink(create(absolute))
        }
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val absolute = absolute(file)
        synchronized(lock) {
            val existing = files[absolute]
            if (existing != null) return NodeSink(existing)
            if (mustExist) throw FileNotFoundException("no such file: $file")
            return NodeSink(create(absolute))
        }
    }

    /** A new empty file at [path]; under [lock]. */
    private fun create(path: Path): Node {
        if (path in dirs) throw IOException("$path is a directory")
        val parent = path.parent
        if (parent == null || parent !in dirs) throw FileNotFoundException("no such directory: $parent")
        return Node(path).also { files[path] = it }
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        val absolute = absolute(dir)
        synchronized(lock) {
            if (absolute in dirs) {
                if (mustCreate) throw IOException("$dir already exists")
                return
            }
            if (absolute in files) throw IOException("$dir is a file")
            val parent = absolute.parent
            if (parent == null || parent !in dirs) throw IOException("no such directory: $parent")
            dirs += absolute
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        val from = absolute(source)
        val to = absolute(target)
        synchronized(lock) {
            val node = files[from]
                ?: if (from in dirs) throw IOException("moving directories is not supported") else throw FileNotFoundException("no such file: $source")
            if (from == to) return
            if (to in dirs) throw IOException("$target is a directory")
            val parent = to.parent
            if (parent == null || parent !in dirs) throw IOException("no such directory: $parent")
            files.remove(to)?.let(::discard)
            files.remove(from)
            node.path = to
            files[to] = node
        }
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val absolute = absolute(path)
        synchronized(lock) {
            files.remove(absolute)?.let {
                discard(it)
                return
            }
            if (absolute in dirs) {
                if (absolute == ROOT) throw IOException("the root cannot be deleted")
                val hasChildren = files.keys.any { it.parent == absolute } || dirs.any { it.parent == absolute }
                if (hasChildren) throw IOException("$path is not empty")
                dirs -= absolute
                return
            }
            if (mustExist) throw FileNotFoundException("no such file: $path")
        }
    }

    /** Frees a file that is no longer in [files]; under [lock]. Sources already open keep their bytes. */
    private fun discard(node: Node) {
        node.live = false
        totalBytes -= node.data.size
        node.data.clear()
    }

    override fun createSymlink(source: Path, target: Path): Unit = throw IOException("symbolic links are not supported")

    override fun toString(): String = "MemoryFileSystem"

    private inner class NodeSink(private val node: Node) : Sink {
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            synchronized(lock) {
                if (closed) throw IOException("closed")
                // Written after a delete: no one can read it, so it is dropped
                if (!node.live) {
                    source.skip(byteCount)
                    return
                }
                if (node.data.size + byteCount > maxFileBytes) {
                    throw IOException("${node.path} would be larger than $maxFileBytes bytes")
                }
                if (totalBytes + byteCount > maxTotalBytes && !uncapped(node.path)) {
                    throw IOException("the files would be larger than $maxTotalBytes bytes")
                }
                node.data.write(source, byteCount)
                totalBytes += byteCount
            }
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            synchronized(lock) { closed = true }
        }
    }

    private companion object {
        val ROOT = "/".toPath()
    }
}
// ZOUKAN <--
