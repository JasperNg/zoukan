package zoukan.desktop.download

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.NoFollowDelete
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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

    private val systemTrash: (File) -> Boolean = { file ->
        try {
            canTrash() && java.awt.Desktop.getDesktop().moveToTrash(file)
        } catch (e: RuntimeException) {
            logcat(LogPriority.WARN, e) { "Could not move ${file.name} to the trash" }
            false
        }
    }

    /** Tests replace it, so no machine's trash is used. */
    @Volatile
    internal var trash: (File) -> Boolean = systemTrash

    /**
     * Whether files in [folder] can go to the system trash: the trash works here, and [folder] is on
     * a volume that has one. A network share (SMB, AFP, NFS, WebDAV, a Windows UNC path) has none:
     * Finder and Explorer delete there for good. A trash replaced by a test always works.
     *
     * Only a good guess: a delete into the trash that still fails deletes nothing (see [delete]).
     */
    fun canTrash(folder: File): Boolean {
        if (trash !== systemTrash) return true
        if (!canTrash()) return false
        val type = try {
            Files.getFileStore(folder.toPath()).type()
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        return volumeHasTrash(folder.absolutePath, type)
    }

    /** Whether a volume of file system [type] holding [path] has a system trash (see [canTrash]). */
    internal fun volumeHasTrash(path: String, type: String?): Boolean {
        if (path.startsWith("\\\\") || path.startsWith("//")) return false
        return type.orEmpty().lowercase() !in NETWORK_FILE_SYSTEMS
    }

    private val NETWORK_FILE_SYSTEMS = setOf("smbfs", "afpfs", "nfs", "nfs4", "webdav", "cifs", "smb2", "smb3", "ftp", "fuse.sshfs")

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
     * at least [minDepth] levels down. With [toTrash], a file or folder goes to the system trash and
     * is never deleted for good: when the move fails (a network share, a volume without a trash) it
     * stays where it is and this returns false. A link is removed as itself either way: what it points
     * to is never touched.
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
        if (toTrash && isPlain(path)) {
            if (trash(path.toFile())) return true
            // The user was told "trash": nothing is deleted for good instead
            logcat(LogPriority.WARN) { "Not deleting ${path.fileName}: it could not be moved to the trash" }
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }
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
     * Deletes [path] and, for a real folder, everything in it, never following a link or a Windows
     * junction ([NoFollowDelete.deleteTree]).
     */
    fun deleteTreeNoFollow(path: Path) = NoFollowDelete.deleteTree(path)

    /**
     * A symbolic link, a Windows junction or another reparse point: anything whose real location is
     * not the folder it is listed in ([NoFollowDelete.isLink]).
     */
    internal fun isLink(path: Path): Boolean = NoFollowDelete.isLink(path)
}
