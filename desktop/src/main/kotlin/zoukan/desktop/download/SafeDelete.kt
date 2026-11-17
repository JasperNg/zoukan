package zoukan.desktop.download

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Every delete under the downloads folder goes through here. It never follows a symbolic link (a
 * linked folder loses the link, never the files it points to), and it refuses a path whose real
 * location is not strictly inside the downloads folder, at least [minDepth] levels down
 * (`<source>/<entry>` is 2), so a bad name or a link can never reach the folder itself, a source's
 * folder or anything outside it.
 */
object SafeDelete {

    /** Whether the system trash can be used here (not on a headless machine or most Linux desktops). */
    fun canTrash(): Boolean = try {
        java.awt.Desktop.isDesktopSupported() &&
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
    } catch (e: RuntimeException) {
        // HeadlessException, UnsupportedOperationException, SecurityException
        false
    } catch (e: LinkageError) {
        false
    }

    /** Tests replace it, so no machine's trash is used. */
    @Volatile
    internal var trash: (File) -> Boolean = { file ->
        try {
            canTrash() && java.awt.Desktop.getDesktop().moveToTrash(file)
        } catch (e: RuntimeException) {
            logcat(LogPriority.WARN, e) { "Could not move ${file.name} to the trash" }
            false
        }
    }

    /**
     * Where [target] really is, with the links in its parent folders resolved but not a link
     * [target] may itself be; null when that is not inside [root] at least [minDepth] levels down.
     */
    internal fun resolveInside(root: File, target: File, minDepth: Int): Path? = try {
        val rootReal = root.toPath().toRealPath()
        val name = target.name
        val parent = target.absoluteFile.parentFile
        if (name.isEmpty() || name == "." || name == ".." || parent == null) {
            null
        } else {
            val real = parent.toPath().toRealPath().resolve(name)
            real.takeIf { it.startsWith(rootReal) && it.nameCount - rootReal.nameCount >= minDepth }
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Deletes [target] (a file, a folder with everything in it, or a link) when it is inside [root],
     * at least [minDepth] levels down; into the system trash when [toTrash] and the trash works here.
     *
     * @return true when [target] is gone afterwards (or was not there)
     */
    fun delete(root: File, target: File, minDepth: Int = 2, toTrash: Boolean = false): Boolean {
        val path = resolveInside(root, target, minDepth)
        if (path == null) {
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                logcat(LogPriority.ERROR) { "Not deleting ${target.path}: it is not inside the downloads folder" }
            }
            return !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        // Only a plain file or folder goes to the trash: a link or a Windows junction is removed as itself
        if (toTrash && isPlain(path) && trash(path.toFile())) return true
        deleteTreeNoFollow(path)
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    private fun isPlain(path: Path): Boolean = !isLink(path)

    /** Removes [dir] only when it is an empty folder inside [root], at least [minDepth] levels down. */
    fun deleteIfEmpty(root: File, dir: File, minDepth: Int = 1): Boolean {
        val path = resolveInside(root, dir, minDepth) ?: return false
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.newDirectoryStream(path).use { if (it.iterator().hasNext()) return false }
            Files.delete(path)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Deletes [path] and, for a real folder, everything in it, never following a link. Not
     * walkFileTree: it walks into a Windows junction (which Java reports as a folder), and would
     * delete the files the junction points to.
     */
    fun deleteTreeNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!isLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.newDirectoryStream(path).use { children -> children.forEach { deleteTreeNoFollow(it) } }
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not delete everything in ${path.fileName}" }
            }
        }
        // A link or a junction is removed as itself
        deleteQuietly(path)
    }

    /**
     * A symbolic link, a Windows junction or another reparse point: anything whose real location is
     * not the folder it is listed in.
     */
    internal fun isLink(path: Path): Boolean = try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attrs.isSymbolicLink || attrs.isOther -> true
            !attrs.isDirectory -> false
            else -> {
                val parent = path.toAbsolutePath().parent
                parent != null && path.toRealPath() != parent.toRealPath().resolve(path.fileName.toString())
            }
        }
    } catch (e: IOException) {
        // Unreadable: treated as a link, so nothing behind it is touched
        true
    }

    /** What is left is reported by the caller, which checks the path is gone. */
    private fun deleteQuietly(path: Path) {
        try {
            Files.delete(path)
        } catch (e: IOException) {
            logcat(LogPriority.DEBUG, e) { "Could not delete ${path.fileName}" }
        }
    }
}
