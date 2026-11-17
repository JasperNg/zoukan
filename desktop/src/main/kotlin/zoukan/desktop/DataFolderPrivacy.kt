package zoukan.desktop

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Keeps the data folder's disposable files (logs, crash reports, caches) out of Spotlight and Time
 * Machine on macOS, and the data folder itself private to the user on POSIX systems.
 *
 * Nothing here deletes or copies a file, and every step only notes a failure: the app starts as
 * it did before.
 */
internal object DataFolderPrivacy {
    /** Spotlight does not index a folder whose name ends in `.noindex`, nor anything inside it. */
    const val NOINDEX = ".noindex"

    /**
     * The folder [newName] in [parent], or [oldName] when only that one is there and cannot be renamed.
     *
     * - [newName] exists: it is used, and an [oldName] beside it is left as it is.
     * - Only [oldName] exists: one atomic rename in the same folder. If that fails, [oldName] is used
     *   for this run and the next start tries again.
     * - Neither: [newName], which the caller creates.
     */
    fun resolve(
        parent: File,
        oldName: String,
        newName: String,
        note: (String) -> Unit,
        move: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) },
    ): File {
        val new = File(parent, newName)
        val old = File(parent, oldName)
        return try {
            when {
                new.exists() -> new
                !old.exists() -> new
                else -> try {
                    move(old.toPath(), new.toPath())
                    new
                } catch (e: Exception) {
                    // A rename that reports a failure but happened anyway still counts
                    if (new.exists() && !old.exists()) {
                        new
                    } else {
                        note("Could not rename ${old.path} to $newName, using the old name for now: $e")
                        old
                    }
                }
            }
        } catch (e: Exception) {
            note("Could not check ${old.path}, using the old name: $e")
            old
        }
    }

    /**
     * On macOS: creates the disposable folders under their new names and `updates/`, and keeps
     * them out of Time Machine. On POSIX systems: makes the data folder 0700 when it is Zoukan's
     * own per-platform folder or Zoukan made it on this start ([ZoukanDirs.rootIsNew]). A folder
     * the user chose (the data folder override) that already existed keeps its permissions.
     */
    fun protect(dirs: ZoukanDirs, note: (String) -> Unit) {
        if (dirs.isDefaultRoot || dirs.rootIsNew) restrictToOwner(dirs.root, note)
        if (!dirs.hidesFromSpotlight) return
        dirs.disposableFolders.forEach { dir ->
            runCatching { dir.mkdirs() }
            BackupExclusion.exclude(dir, note)
        }
        // Created now, so the first installer downloaded later is already excluded
        runCatching { dirs.updates.mkdirs() }
        BackupExclusion.exclude(dirs.updates, note)
    }

    /**
     * Sets [dir] to rwx------ when the file system has POSIX permissions and it is not already.
     * A symbolic link is left alone, so the folder it points to keeps its permissions.
     */
    fun restrictToOwner(dir: File, note: (String) -> Unit) {
        try {
            val path = dir.toPath()
            if (Files.isSymbolicLink(path)) return
            if (!Files.isDirectory(path)) return
            if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
            val ownerOnly = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            if (Files.getPosixFilePermissions(path) != ownerOnly) Files.setPosixFilePermissions(path, ownerOnly)
        } catch (e: Exception) {
            note("Could not make ${dir.path} private to the user: $e")
        }
    }
}

/**
 * Time Machine's sticky exclusion on macOS: the extended attribute `tmutil addexclusion` writes.
 * It moves with the folder and needs no administrator rights. A no-op on other systems.
 */
internal object BackupExclusion {
    const val ATTRIBUTE = "com.apple.metadata:com_apple_backup_excludeItem"

    /** A binary property list holding the string "com.apple.backupd", byte for byte as tmutil writes it. */
    val VALUE: ByteArray = (
        "62706C6973743030" + "5F1011" + "636F6D2E6170706C652E6261636B757064" + "08" +
            "000000000000010100000000000000010000000000000000000000000000001C"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Excludes [dir] from Time Machine, unless it already is. Returns whether it is excluded now;
     * a failure is passed to [note] and never thrown.
     */
    fun exclude(dir: File, note: (String) -> Unit, enabled: Boolean = Platform.current == Platform.MACOS): Boolean {
        if (!enabled) return false
        return try {
            if (isExcluded(dir)) return true
            val libc = LibC.INSTANCE
            libc.setxattr(dir.absolutePath, ATTRIBUTE, VALUE, NativeLong(VALUE.size.toLong()), 0, 0)
            true
        } catch (e: Exception) {
            note("Could not keep ${dir.path} out of Time Machine: $e")
            false
        } catch (e: LinkageError) {
            // JNA or the C library failed to load
            note("Could not keep ${dir.path} out of Time Machine: $e")
            false
        }
    }

    /** Whether [dir] carries exactly the exclusion; false on other systems or when it cannot be read. */
    fun isExcluded(dir: File, enabled: Boolean = Platform.current == Platform.MACOS): Boolean {
        if (!enabled) return false
        return try {
            val buffer = ByteArray(VALUE.size + 1)
            val read = LibC.INSTANCE.getxattr(dir.absolutePath, ATTRIBUTE, buffer, NativeLong(buffer.size.toLong()), 0, 0)
            read.toLong() == VALUE.size.toLong() && buffer.copyOf(VALUE.size).contentEquals(VALUE)
        } catch (e: LastErrorException) {
            false // ENOATTR: not set
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private interface LibC : Library {
        @Throws(LastErrorException::class)
        fun getxattr(path: String, name: String, value: ByteArray, size: NativeLong, position: Int, options: Int): NativeLong

        @Throws(LastErrorException::class)
        fun setxattr(path: String, name: String, value: ByteArray, size: NativeLong, position: Int, options: Int): Int

        companion object {
            val INSTANCE: LibC by lazy {
                Native.load("c", LibC::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            }
        }
    }
}
