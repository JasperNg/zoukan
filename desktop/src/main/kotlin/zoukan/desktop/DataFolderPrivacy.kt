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
     * them out of Time Machine; one that is a symbolic link is skipped ([excludeFromBackup]). On POSIX systems: makes the data folder 0700 when it is Zoukan's
     * own per-platform folder or Zoukan made it on this start ([ZoukanDirs.rootIsNew]). A folder
     * the user chose (the data folder override) that already existed keeps its permissions.
     */
    fun protect(dirs: ZoukanDirs, note: (String) -> Unit) {
        if (dirs.isDefaultRoot || dirs.rootIsNew) restrictToOwner(dirs.root, note)
        if (!dirs.hidesFromSpotlight) return
        // Created now, so the first installer downloaded later is already excluded
        (dirs.disposableFolders + dirs.updates).forEach { dir -> excludeFromBackup(dir, note) }
    }

    /**
     * Creates [dir] and keeps it out of Time Machine with [exclude], unless [dir] is a symbolic
     * link: then nothing is created or marked and [note] says so, so a folder the link points to
     * (which may be the user's own) is never excluded from backups. Returns whether [dir] is
     * excluded now; never throws.
     */
    fun excludeFromBackup(
        dir: File,
        note: (String) -> Unit,
        exclude: (File, (String) -> Unit) -> Boolean = { folder, onFailure -> BackupExclusion.exclude(folder, onFailure) },
    ): Boolean {
        val isLink = try {
            Files.isSymbolicLink(dir.toPath())
        } catch (e: Exception) {
            note("Could not check ${dir.path}, leaving it in Time Machine: $e")
            return false
        }
        if (isLink) {
            note("${dir.path} is a symbolic link, so it and the folder it points to are left in Time Machine")
            return false
        }
        runCatching { dir.mkdirs() }
        return try {
            exclude(dir, note)
        } catch (e: Exception) {
            note("Could not keep ${dir.path} out of Time Machine: $e")
            false
        }
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
            libc.setxattr(dir.absolutePath, ATTRIBUTE, VALUE, NativeLong(VALUE.size.toLong()), 0, XATTR_NOFOLLOW)
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

    /**
     * Puts [dir] back into Time Machine backups: removes the exclusion attribute, whatever value it
     * holds. Returns whether [dir] is free of it now (also when it never had it); false on other
     * systems. Nothing else about [dir] or its files changes. A failure is passed to [note] and
     * never thrown.
     */
    fun include(dir: File, note: (String) -> Unit, enabled: Boolean = Platform.current == Platform.MACOS): Boolean {
        if (!enabled) return false
        return try {
            LibC.INSTANCE.removexattr(dir.absolutePath, ATTRIBUTE, XATTR_NOFOLLOW)
            true
        } catch (e: LastErrorException) {
            if (e.errorCode == ENOATTR) {
                true
            } else {
                note("Could not put ${dir.path} back into Time Machine: $e")
                false
            }
        } catch (e: Exception) {
            note("Could not put ${dir.path} back into Time Machine: $e")
            false
        } catch (e: LinkageError) {
            note("Could not put ${dir.path} back into Time Machine: $e")
            false
        }
    }

    /**
     * macOS's `XATTR_NOFOLLOW`: every call acts on the path itself, never on what a symbolic link
     * at that path points to, so a folder swapped for a link cannot get its target marked.
     */
    private const val XATTR_NOFOLLOW = 0x0001

    /** macOS's errno when a file has no such extended attribute. */
    private const val ENOATTR = 93

    /** Whether [dir] carries exactly the exclusion; false on other systems or when it cannot be read. */
    fun isExcluded(dir: File, enabled: Boolean = Platform.current == Platform.MACOS): Boolean {
        if (!enabled) return false
        return try {
            val buffer = ByteArray(VALUE.size + 1)
            val read = LibC.INSTANCE.getxattr(dir.absolutePath, ATTRIBUTE, buffer, NativeLong(buffer.size.toLong()), 0, XATTR_NOFOLLOW)
            read.toLong() == VALUE.size.toLong() && buffer.copyOf(VALUE.size).contentEquals(VALUE)
        } catch (e: LastErrorException) {
            false // ENOATTR: not set
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    /**
     * Whether [dir] carries the exclusion attribute with any value (which [include] would remove);
     * null when that could not be read (passed to [note], never thrown). False on other systems.
     */
    fun hasAttribute(dir: File, note: (String) -> Unit, enabled: Boolean = Platform.current == Platform.MACOS): Boolean? {
        if (!enabled) return false
        return try {
            LibC.INSTANCE.getxattr(dir.absolutePath, ATTRIBUTE, ByteArray(1), NativeLong(1), 0, XATTR_NOFOLLOW)
            true
        } catch (e: LastErrorException) {
            when (e.errorCode) {
                ENOATTR -> false
                // A value longer than the one-byte buffer: the attribute is there
                ERANGE -> true
                else -> {
                    note("Could not read the Time Machine exclusion of ${dir.path}: $e")
                    null
                }
            }
        } catch (e: Exception) {
            note("Could not read the Time Machine exclusion of ${dir.path}: $e")
            null
        } catch (e: LinkageError) {
            note("Could not read the Time Machine exclusion of ${dir.path}: $e")
            null
        }
    }

    /** macOS's errno when a buffer is too small for an extended attribute's value. */
    private const val ERANGE = 34

    private interface LibC : Library {
        @Throws(LastErrorException::class)
        fun getxattr(path: String, name: String, value: ByteArray, size: NativeLong, position: Int, options: Int): NativeLong

        @Throws(LastErrorException::class)
        fun setxattr(path: String, name: String, value: ByteArray, size: NativeLong, position: Int, options: Int): Int

        @Throws(LastErrorException::class)
        fun removexattr(path: String, name: String, options: Int): Int

        companion object {
            val INSTANCE: LibC by lazy {
                Native.load("c", LibC::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            }
        }
    }
}
