package zoukan.desktop

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.NoFollowDelete
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Moves the caches an older version kept in the data folder to [ZoukanDirs.localRoot] (on Windows,
 * out of the roaming profile). Runs at start, under the one-instance lock and before anything
 * opens them (Chromium, Coil, FFmpeg, the chapter cache).
 *
 * Only the folders in [ZoukanDirs.LOCAL_CACHES] move, never the database, preferences, covers,
 * backups or downloads. A folder is renamed when both places are on the same volume; otherwise it
 * is copied to a staging folder, which becomes the new folder only once the whole copy succeeded,
 * and only then is the old one deleted. When a folder cannot move (a file in use, no space, a link
 * in it), it stays where it is and this run uses it there; the next start tries again.
 * When both places have the folder, the old one is deleted; for Chromium's profile only if the new
 * one was used more recently, since an older version run in between uses the old one. Never throws.
 */
internal object CacheFolders {
    enum class Outcome {
        /** No old folder: the new place is used. */
        NOTHING_TO_MOVE,

        /** Renamed in one step. */
        MOVED,

        /** Copied to another volume, then the old folder deleted. */
        COPIED,

        /** Moved earlier; what was left of the old folder is deleted. */
        ALREADY_MOVED,

        /** Could not move: the old folder stays in use. */
        KEPT_IN_ROOT,

        /**
         * Both existed and the old one was used more recently (an older version ran in between):
         * the old folder took the new one's place, and the new one is deleted.
         */
        REPLACED,
    }

    private const val STAGING_SUFFIX = ".moving"

    /** The new folder, set aside while a more recently used old folder takes its place. */
    private const val ASIDE_SUFFIX = ".replaced"

    /** How many entries [newestModified] looks at, so a huge folder cannot slow the start. */
    private const val NEWEST_WALK_LIMIT = 20_000

    private val atomicRename: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }

    /**
     * The folders to use for this run: [dirs], with each cache that could not move kept in
     * [ZoukanDirs.root]. Does nothing when the caches have no folder of their own.
     */
    fun migrate(dirs: ZoukanDirs, rename: (Path, Path) -> Unit = atomicRename): ZoukanDirs {
        if (!dirs.hasLocalRoot) return dirs
        try {
            Files.createDirectories(dirs.localRoot.toPath())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Caches: ${dirs.localRoot} cannot be created; they stay in ${dirs.root}" }
            return dirs.keepingInRoot(ZoukanDirs.LOCAL_CACHES.toSet())
        }
        val kept = ZoukanDirs.LOCAL_CACHES.filterTo(mutableSetOf()) { relative ->
            val outcome = try {
                move(
                    dirs.root.resolve(relative).toPath(),
                    dirs.localRoot.resolve(relative).toPath(),
                    rename,
                    // Chromium's profile holds site storage (cookies, logins): an older version
                    // run in between keeps using the old folder, so the more recent one is kept
                    keepNewer = relative == ZoukanDirs.BROWSER_PROFILE,
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Caches: could not move $relative; it stays in ${dirs.root}" }
                Outcome.KEPT_IN_ROOT
            }
            if (outcome != Outcome.NOTHING_TO_MOVE) logcat(LogPriority.INFO) { "Caches: $relative ${outcome.name.lowercase()}" }
            outcome == Outcome.KEPT_IN_ROOT
        }
        return if (kept.isEmpty()) dirs else dirs.keepingInRoot(kept)
    }

    /**
     * Moves the folder [old] to [new]; see [CacheFolders]. When both exist, the old one is a
     * leftover and is deleted, unless [keepNewer] and the old one was modified more recently: then
     * it replaces the new one. Throws only before anything was changed.
     */
    internal fun move(old: Path, new: Path, rename: (Path, Path) -> Unit = atomicRename, keepNewer: Boolean = false): Outcome {
        val aside = new.resolveSibling(new.fileName.toString() + ASIDE_SUFFIX)
        if (keepNewer) recoverAside(old, new, aside, rename)
        val attrs = attributes(old) ?: return Outcome.NOTHING_TO_MOVE
        // A link or junction is the user's own arrangement: left as it is, and used where it is
        if (isLink(old, attrs)) return Outcome.KEPT_IN_ROOT
        if (!attrs.isDirectory) return Outcome.NOTHING_TO_MOVE
        if (attributes(new) != null) {
            // Both exist: either a move whose old folder could not all be deleted, or an older
            // version that recreated the old folder and used it after this one had moved it
            if (keepNewer && isMoreRecent(old, new)) return replaceWith(old, new, aside, rename)
            if (keepNewer) logcat(LogPriority.INFO) { "Caches: $new is more recent than $old; the old one is deleted" }
            if (!deleteTree(old)) logcat(LogPriority.WARN) { "Caches: could not delete all of the old $old" }
            return Outcome.ALREADY_MOVED
        }
        Files.createDirectories(new.parent)
        val outcome = moveInto(old, new, rename)
        // A set-aside folder whose replacement was cut short: the old folder has now taken its place
        if (keepNewer && outcome != Outcome.KEPT_IN_ROOT && !deleteTree(aside)) {
            logcat(LogPriority.WARN) { "Caches: could not delete all of $aside" }
        }
        return outcome
    }

    /** Renames, or on another volume copies, [old] to [new], which does not exist. */
    private fun moveInto(old: Path, new: Path, rename: (Path, Path) -> Unit): Outcome {
        try {
            rename(old, new)
            return Outcome.MOVED
        } catch (_: AtomicMoveNotSupportedException) {
            // Another volume: copied below
        } catch (e: IOException) {
            // For example a file still open: the folder stays, and the next start tries again
            logcat(LogPriority.WARN, e) { "Caches: could not move $old" }
            return Outcome.KEPT_IN_ROOT
        }
        val staging = new.resolveSibling(new.fileName.toString() + STAGING_SUFFIX)
        try {
            // A staging folder left by an earlier start that stopped half way
            if (!deleteTree(staging)) throw IOException("Could not delete the old staging folder $staging")
            copyTree(old, staging)
            Files.move(staging, new, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Caches: could not copy $old to $new" }
            deleteTree(staging)
            return Outcome.KEPT_IN_ROOT
        }
        if (!deleteTree(old)) logcat(LogPriority.WARN) { "Caches: could not delete all of the old $old; the next start tries again" }
        return Outcome.COPIED
    }

    /**
     * Puts the more recently used [old] in the place of [new]: [new] is set aside as [aside], [old]
     * moved in, and only then [aside] deleted. When a step fails, [new] is put back and [old] is
     * used where it is, so one whole folder always stays.
     */
    private fun replaceWith(old: Path, new: Path, aside: Path, rename: (Path, Path) -> Unit): Outcome {
        logcat(LogPriority.INFO) { "Caches: $old is more recent than $new (an older version used it); it replaces the new one" }
        if (!deleteTree(aside)) {
            logcat(LogPriority.WARN) { "Caches: could not delete the old $aside; $old is used where it is" }
            return Outcome.KEPT_IN_ROOT
        }
        try {
            rename(new, aside)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Caches: could not set $new aside; $old is used where it is" }
            return Outcome.KEPT_IN_ROOT
        }
        val moved = try {
            moveInto(old, new, rename)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Caches: could not move $old" }
            Outcome.KEPT_IN_ROOT
        }
        if (moved == Outcome.KEPT_IN_ROOT) {
            // The old folder is whole and used where it is; the new one goes back for the next start
            try {
                rename(aside, new)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Caches: could not put $aside back; the next start deletes it once $old has moved" }
            }
            return Outcome.KEPT_IN_ROOT
        }
        if (!deleteTree(aside)) logcat(LogPriority.WARN) { "Caches: could not delete all of $aside" }
        return Outcome.REPLACED
    }

    /**
     * Finishes a replacement an earlier start did not: [aside] is deleted once [new] exists, and put
     * back when neither [new] nor [old] does. With [old] there, it moves in and [aside] goes after.
     */
    private fun recoverAside(old: Path, new: Path, aside: Path, rename: (Path, Path) -> Unit) {
        val asideAttrs = attributes(aside) ?: return
        if (isLink(aside, asideAttrs) || !asideAttrs.isDirectory) return
        when {
            attributes(new) != null -> if (!deleteTree(aside)) logcat(LogPriority.WARN) { "Caches: could not delete all of $aside" }
            attributes(old) == null -> try {
                rename(aside, new)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Caches: could not put $aside back" }
            }
        }
    }

    /** Whether something in [old] was modified after everything in [new]; false when unknown. */
    private fun isMoreRecent(old: Path, new: Path): Boolean {
        val oldNewest = newestModified(old) ?: return false
        val newNewest = newestModified(new) ?: return false
        return oldNewest > newNewest
    }

    /**
     * The latest modification time (in milliseconds) of [root] and what is in it, looking at no
     * more than [NEWEST_WALK_LIMIT] entries and never following a link. Null when it cannot be read.
     */
    private fun newestModified(root: Path): Long? = try {
        var newest = Long.MIN_VALUE
        var seen = 0
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                private fun see(attrs: BasicFileAttributes): FileVisitResult {
                    newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                    return if (++seen >= NEWEST_WALK_LIMIT) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isLink(dir, attrs)) return FileVisitResult.SKIP_SUBTREE
                    return see(attrs)
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isLink(file, attrs)) return FileVisitResult.CONTINUE
                    return see(attrs)
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        newest.takeIf { it != Long.MIN_VALUE }
    } catch (_: Exception) {
        null
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

    private val IS_WINDOWS = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun safeResolvesToItself(path: Path, resolvesToItself: (Path) -> Boolean): Boolean = try {
        resolvesToItself(path)
    } catch (_: IOException) {
        false
    }

    /**
     * A symbolic link, or on Windows a junction or other reparse point whose real path resolves
     * elsewhere. Windows tags cloud-sync placeholders, deduplicated and compressed files or folders
     * with a reparse point ("other"), yet their real path is where they are listed.
     */
    internal fun isLink(
        path: Path,
        attrs: BasicFileAttributes,
        resolvesToItself: (Path) -> Boolean = NoFollowDelete::resolvesToItself,
        windows: Boolean = IS_WINDOWS,
    ): Boolean = when {
        attrs.isSymbolicLink -> true
        attrs.isDirectory -> attrs.isOther && !safeResolvesToItself(path, resolvesToItself)
        attrs.isRegularFile -> false
        attrs.isOther -> !windows || !safeResolvesToItself(path, resolvesToItself)
        else -> false
    }

    /**
     * Whether [path] with [attrs] is a plain file safe to copy: a regular file, or on [windows] a
     * reparse-tagged non-directory file whose real location is where it is listed.
     */
    internal fun isPlainFile(
        path: Path,
        attrs: BasicFileAttributes,
        resolvesToItself: (Path) -> Boolean = NoFollowDelete::resolvesToItself,
        windows: Boolean = IS_WINDOWS,
    ): Boolean = when {
        attrs.isSymbolicLink || attrs.isDirectory -> false
        attrs.isRegularFile -> true
        attrs.isOther -> windows && safeResolvesToItself(path, resolvesToItself)
        else -> false
    }

    /** Copies the folder [from] to [to]; any link or special file in it fails the copy. */
    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isLink(dir, attrs)) throw IOException("$dir is a link; not copied")
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!isPlainFile(file, attrs)) throw IOException("$file is not a plain file; not copied")
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Deletes the folder [root] and what is in it, never following a link or junction (those are
     * left, so their folder stays). Whether all of it is gone; never throws.
     */
    internal fun deleteTree(root: Path): Boolean {
        if (attributes(root) == null) return true
        var complete = true
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!isLink(dir, attrs)) return FileVisitResult.CONTINUE
                        complete = false
                        return FileVisitResult.SKIP_SUBTREE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isLink(file, attrs) || !delete(file)) complete = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        complete = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (!delete(dir)) complete = false
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            complete = false
        }
        return complete && attributes(root) == null
    }

    private fun delete(path: Path): Boolean = try {
        Files.delete(path)
        true
    } catch (_: IOException) {
        // A read-only file on Windows cannot be deleted until the flag is cleared
        try {
            Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
            Files.delete(path)
            true
        } catch (_: Exception) {
            false
        }
    }
}
