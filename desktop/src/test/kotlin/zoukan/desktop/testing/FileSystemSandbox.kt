package zoukan.desktop.testing

import com.sun.jna.NativeLibrary
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest

/**
 * A temporary home folder for tests of code that creates, deletes, marks or changes the permissions
 * of files, with the things that code has got wrong before:
 *
 * - [dataFolder], the app's data folder, and [storageFolder], whose [storageZoukanFolders]
 *   (downloads, local, autobackup) are the app's; the storage folder itself is not;
 * - [userFolder], a folder the user chose, and [downloads], with the user's own files;
 * - [linkFromApp], a symbolic link inside the data folder to [linkTarget] in the user's folder
 *   (null where the OS refuses symbolic links, as Windows does without the privilege);
 * - [otherProgramFolder], standing in for the temp folder, with another program's JVM crash files;
 * - [readOnlyFolder], a folder (and file) that cannot be written.
 *
 * Every path, its content hash, permissions, extended attributes and link target is recorded when
 * the sandbox is made, and again by [record]. [assertNothingOutsideAppChanged] then fails, listing
 * each change, when anything outside the app's folders ([appFolders]) was created, deleted or
 * changed. [close] gives the read-only folder its permissions back and deletes the sandbox without
 * following links.
 *
 * ```
 * FileSystemSandbox.create(temp).use { sandbox ->
 *     codeUnderTest(sandbox.dataFolder, sandbox.otherProgramFolder)
 *     sandbox.assertNothingOutsideAppChanged()
 * }
 * ```
 */
class FileSystemSandbox private constructor(
    /** The sandbox's home folder, canonical (the temporary folder may be behind a link). */
    val home: File,
) : AutoCloseable {
    val dataFolder = File(home, "app-data/Zoukan")
    val storageFolder = File(home, "Documents/Zoukan")
    val storageZoukanFolders = listOf("downloads", "local", "autobackup").map { File(storageFolder, it) }
    val userFolder = File(home, "Documents/Chosen")
    val downloads = File(home, "Downloads")
    val otherProgramFolder = File(home, "tmp")
    val readOnlyFolder = File(home, "read-only")
    val linkTarget = File(userFolder, "linked-from-app")

    /** The link in the data folder to [linkTarget]; null where symbolic links cannot be made. */
    var linkFromApp: File? = null
        private set

    /** Another program's JVM crash file in [otherProgramFolder], with a process id no live process has. */
    val otherProgramCrashFile = File(otherProgramFolder, "hs_err_pid$OTHER_PID.log")

    private val owned = mutableListOf(dataFolder).apply { addAll(storageZoukanFolders) }

    /** The folders whose content the code under test may change. */
    val appFolders: List<File> get() = owned.toList()

    private var baseline: Map<String, Entry> = emptyMap()

    /** Counts the folder [dir] as the app's, so changes in it are allowed. Call [record] after. */
    fun addAppFolder(dir: File) {
        require(dir.absoluteFile.startsWith(home)) { "$dir is not in the sandbox $home" }
        owned += dir.absoluteFile
    }

    /** Makes a symbolic link at [link] to [target]; null where the OS refuses (Windows without the privilege). */
    fun link(link: File, target: File): File? = try {
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath()).toFile()
    } catch (e: IOException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Whether symbolic links can be made here. */
    val symlinksSupported: Boolean get() = linkFromApp != null

    /** Records the state the assertion compares with: call it after the test's own setup. */
    fun record() {
        baseline = snapshot()
    }

    /**
     * Fails, listing every change, when anything outside [appFolders] changed since [record]: a file
     * or folder created or deleted, a file's content, a permission, an extended attribute, or where
     * a link points.
     */
    fun assertNothingOutsideAppChanged() {
        val changes = changesOutsideApp()
        check(changes.isEmpty()) {
            "The code changed files outside the app's folders (${appFolders.joinToString { relative(it) }}):\n" +
                changes.joinToString("\n") { "  $it" }
        }
    }

    /** What [assertNothingOutsideAppChanged] would report, one line per change. */
    fun changesOutsideApp(): List<String> {
        val now = snapshot()
        val changes = mutableListOf<String>()
        for (path in (baseline.keys + now.keys).toSortedSet()) {
            if (isOwned(path)) continue
            val before = baseline[path]
            val after = now[path]
            val shown = path.ifEmpty { "." }
            when {
                before == null && after != null -> changes += "created ${after.kind} $shown"
                before != null && after == null -> changes += "deleted ${before.kind} $shown"
                before != null && after != null -> changes += before.differences(after).map { "$it: $shown" }
            }
        }
        return changes
    }

    override fun close() {
        // Writable again, or the folder and its file could not be deleted
        makeWritable(readOnlyFolder)
        makeWritable(File(readOnlyFolder, READ_ONLY_FILE))
        deleteTree(home)
    }

    private fun isOwned(path: String): Boolean = owned.any { dir ->
        val prefix = relative(dir)
        path == prefix || path.startsWith("$prefix/")
    }

    private fun relative(file: File): String = file.absoluteFile.relativeTo(home).invariantSeparatorsPath

    /** One path as recorded. */
    private data class Entry(
        val kind: String,
        val hash: String?,
        val linkTarget: String?,
        val permissions: String?,
        val xattrs: Map<String, String>?,
    ) {
        fun differences(other: Entry): List<String> = buildList {
            if (kind != other.kind) add("changed from $kind to ${other.kind}")
            if (hash != other.hash) add("changed content")
            if (linkTarget != other.linkTarget) add("link target $linkTarget -> ${other.linkTarget}")
            if (permissions != other.permissions) add("permissions $permissions -> ${other.permissions}")
            if (xattrs != other.xattrs) {
                val before = xattrs.orEmpty()
                val after = other.xattrs.orEmpty()
                (after.keys - before.keys).sorted().forEach { add("extended attribute $it added") }
                (before.keys - after.keys).sorted().forEach { add("extended attribute $it removed") }
                before.keys.intersect(after.keys).filter { before[it] != after[it] }.sorted().forEach { add("extended attribute $it changed") }
                if (xattrs == null || other.xattrs == null) add("extended attributes readable ${xattrs != null} -> ${other.xattrs != null}")
            }
        }
    }

    /** Every path under [home], links not followed, keyed by its path relative to [home]. */
    private fun snapshot(): Map<String, Entry> {
        val entries = sortedMapOf<String, Entry>()
        Files.walkFileTree(
            home.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    entries[key(dir)] = entry(dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    entries[key(file)] = entry(file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    entries[key(file)] = Entry("unreadable", null, null, null, null)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return entries
    }

    private fun key(path: Path): String = home.toPath().relativize(path).toString().replace(File.separatorChar, '/')

    private fun entry(path: Path, attrs: BasicFileAttributes): Entry {
        val kind = when {
            attrs.isSymbolicLink -> "link"
            attrs.isDirectory -> "folder"
            attrs.isRegularFile -> "file"
            else -> "other"
        }
        return Entry(
            kind = kind,
            hash = if (kind == "file") hash(path) else null,
            linkTarget = if (kind == "link") runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() else null,
            permissions = permissions(path),
            // A link's own attributes are read only on macOS; elsewhere the JDK would read its target's
            xattrs = if (kind == "link" && !isMac) emptyMap() else xattrs(path),
        )
    }

    private fun hash(path: Path): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        "unreadable"
    }

    private fun permissions(path: Path): String? = try {
        val views = path.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views ->
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
            "dos" in views -> {
                val dos = Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                "readOnly=${dos.isReadOnly} hidden=${dos.isHidden} system=${dos.isSystem}"
            }
            else -> null
        }
    } catch (e: IOException) {
        "unreadable"
    } catch (e: UnsupportedOperationException) {
        null
    }

    private fun xattrs(path: Path): Map<String, String>? {
        if (isMac) return MacXattrs.read(path)
        if (!xattrsSupported(path)) return emptyMap()
        return try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return emptyMap()
            view.list().sorted().associateWith { name ->
                val buffer = ByteBuffer.allocate(view.size(name))
                view.read(name, buffer)
                buffer.array().joinToString("") { "%02x".format(it) }
            }
        } catch (e: IOException) {
            null
        } catch (e: UnsupportedOperationException) {
            emptyMap()
        }
    }

    private fun xattrsSupported(path: Path): Boolean = isMac || (
        "user" in path.fileSystem.supportedFileAttributeViews() &&
            runCatching { Files.getFileStore(path).supportsFileAttributeView(UserDefinedFileAttributeView::class.java) }.getOrDefault(false)
        )

    /** Whether extended attributes can be read in this sandbox (macOS, most Linux file systems, NTFS). */
    val xattrsSupported: Boolean get() = xattrsSupported(home.toPath())

    private fun build() {
        dataFolder.mkdirs()
        write(dataFolder, "zoukan.db", "the app's database")
        storageZoukanFolders.forEach { it.mkdirs() }
        write(storageFolder, "downloads/Source/Manga/Chapter.cbz", "pages")
        // Not one of the app's folders, though it is in the storage folder
        write(storageFolder, "notes.txt", "the user's own file beside the app's folders")
        write(userFolder, "notes.txt", "the user's notes")
        write(userFolder, "photos/holiday.jpg", "a photo")
        write(downloads, "invoice.pdf", "the user's invoice")
        write(linkTarget, "keep.txt", "reached only through the app's link")
        write(otherProgramFolder, otherProgramCrashFile.name, otherProgramCrashText())
        write(otherProgramFolder, "replay_pid$OTHER_PID.log", "another program's replay file")
        write(otherProgramFolder, "unrelated.tmp", "another program's temporary file")
        write(readOnlyFolder, READ_ONLY_FILE, "cannot be changed")
        linkFromApp = link(File(dataFolder, "cache/linked-out"), linkTarget)
        makeReadOnly(File(readOnlyFolder, READ_ONLY_FILE))
        makeReadOnly(readOnlyFolder)
    }

    private fun write(dir: File, relative: String, text: String) = File(dir, relative).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun makeReadOnly(file: File) {
        val path = file.toPath()
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (file.isDirectory) "r-xr-xr-x" else "r--r--r--"))
        } else {
            file.setReadOnly()
        }
    }

    /**
     * Every extended attribute of a path on macOS, the path itself and not a link's target. The
     * JDK's view sees only its own `user.` namespace there, not the `com.apple.` attributes (Time
     * Machine's exclusion, quarantine) that matter, so this asks the C library.
     */
    private object MacXattrs {
        private const val XATTR_NOFOLLOW = 1
        private val libc by lazy { NativeLibrary.getInstance("c") }
        private val listxattr by lazy { libc.getFunction("listxattr") }
        private val getxattr by lazy { libc.getFunction("getxattr") }

        fun read(path: Path): Map<String, String>? {
            val file = path.toString().toByteArray(Charsets.UTF_8) + 0
            val size = listxattr.invokeLong(arrayOf<Any?>(file, null, 0L, XATTR_NOFOLLOW))
            if (size < 0) return null
            if (size == 0L) return emptyMap()
            val names = ByteArray(size.toInt())
            val read = listxattr.invokeLong(arrayOf<Any?>(file, names, size, XATTR_NOFOLLOW))
            if (read < 0) return null
            return String(names, 0, read.toInt(), Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.sorted()
                .associateWith { name ->
                    val key = name.toByteArray(Charsets.UTF_8) + 0
                    val length = getxattr.invokeLong(arrayOf<Any?>(file, key, null, 0L, 0, XATTR_NOFOLLOW))
                    if (length < 0) return null
                    val value = ByteArray(length.toInt())
                    if (length > 0 && getxattr.invokeLong(arrayOf<Any?>(file, key, value, length, 0, XATTR_NOFOLLOW)) < 0) return null
                    value.joinToString("") { "%02x".format(it) }
                }
        }
    }

    companion object {
        private val isMac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
        /** A process id no live process has (well above any OS's limit). */
        const val OTHER_PID = 2_000_000_777L

        const val READ_ONLY_FILE = "locked.txt"

        private fun makeWritable(file: File) {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())) return
            try {
                val path = file.toPath()
                if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
                    val permissions = Files.getPosixFilePermissions(path) + PosixFilePermission.OWNER_WRITE + PosixFilePermission.OWNER_READ +
                        (if (file.isDirectory) setOf(PosixFilePermission.OWNER_EXECUTE) else emptySet())
                    Files.setPosixFilePermissions(path, permissions)
                } else {
                    file.setWritable(true)
                }
            } catch (e: IOException) {
                // Deleting reports what is left
            }
        }

        /**
         * Deletes [dir] and everything in it, folders made read-only too; a link is deleted, never
         * what it points to. For a sandbox an interrupted run left behind.
         */
        fun deleteTree(dir: File) {
            val root = dir.toPath()
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // A folder the code under test made read-only
                        makeWritable(dir.toFile())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isSymbolicLink) makeWritable(file.toFile())
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        /** A JVM crash file another Java program wrote. */
        fun otherProgramCrashText(): String =
            "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n" +
                "#  SIGSEGV (0xb) at pc=0x1, pid=$OTHER_PID, tid=2\n#\n\n" +
                "Command Line: -Xmx512m org.example.OtherApp\n\n" +
                "Environment Variables:\nHOME=/home/other\n\n"

        /**
         * Makes a sandbox in a new folder in [parent] (a JUnit `@TempDir`, or by default [TestHome]'s
         * folder) and records its state.
         */
        fun create(parent: File = TestHome.root()): FileSystemSandbox {
            parent.mkdirs()
            val home = Files.createTempDirectory(parent.toPath(), "sandbox-").toRealPath().toFile()
            return FileSystemSandbox(home).apply {
                try {
                    build()
                    record()
                } catch (e: Throwable) {
                    close()
                    throw e
                }
            }
        }
    }
}
