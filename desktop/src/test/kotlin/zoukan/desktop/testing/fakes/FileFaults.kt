package zoukan.desktop.testing.fakes

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Real file-system failures for code that uses `java.io.File` or `java.nio.file` directly (the
 * downloads, backups, the update and extension downloads), which no fake file system can intercept:
 *
 * - [fileInTheWay] puts a regular file where a folder is needed, so creating that folder or anything
 *   in it fails. It works on every OS and for every user, root too.
 * - [readOnly] makes a folder read-only for this process. It works only where the OS enforces it:
 *   not on Windows (a folder's read-only flag does not stop new files there) and not for root, so it
 *   returns null there and the test is skipped (`assumeTrue(fault != null)`).
 *
 * What cannot be made real this way, and needs a seam in the code under test instead: a disk that is
 * full (`No space left on device`) and a write that fails part way through a file. Use
 * [FaultyFileSystem] where the code takes an okio file system, or the code's own write hook (as
 * [zoukan.desktop.download.ChapterDownloader]'s `writeEntry`).
 */
object FileFaults {

    /** A folder made read-only by [readOnly]; [close] gives it its permissions back. */
    class ReadOnlyFolder internal constructor(val dir: File, private val before: Set<PosixFilePermission>) : AutoCloseable {
        override fun close() {
            Files.setPosixFilePermissions(dir.toPath(), before)
        }
    }

    /**
     * Makes [dir] (created if missing) read-only, or returns null when this OS or user can still
     * write in it. Close the result to heal.
     */
    fun readOnly(dir: File): ReadOnlyFolder? {
        dir.mkdirs()
        val path = dir.toPath()
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return null
        val before = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"))
        val folder = ReadOnlyFolder(dir, before)
        // Root writes anyway: then the condition cannot be made here
        val probe = try {
            Files.createTempFile(path, "probe", null)
        } catch (e: IOException) {
            null
        }
        if (probe != null) {
            Files.delete(probe)
            folder.close()
            return null
        }
        return folder
    }

    /**
     * Puts a regular file at [path] (making its parent folders), so making [path] a folder, or
     * anything under it, fails on every OS. Fails when something is already at [path]. Delete the
     * returned file to heal.
     */
    fun fileInTheWay(path: File): File {
        check(!path.exists()) { "$path already exists" }
        path.absoluteFile.parentFile.mkdirs()
        path.writeText("A file where a folder is needed")
        return path
    }
}
