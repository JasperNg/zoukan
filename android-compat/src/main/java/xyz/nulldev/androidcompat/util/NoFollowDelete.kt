package xyz.nulldev.androidcompat.util

// ZOUKAN: whole file -->

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer

/**
 * Deletes a file or folder tree without ever following a symbolic link or a Windows junction, for
 * the app and the extension installer alike (the app's `SafeDelete` adds the check that a path is
 * inside its folder). In android-compat, the lowest module both depend on.
 */
object NoFollowDelete {
    private val logger = KotlinLogging.logger {}

    /**
     * Deletes [path] and, for a real folder, everything in it, never following a link. Not
     * walkFileTree: it walks into a Windows junction (which Java reports as a folder), and would
     * delete the files the junction points to. Nor Kotlin's deleteRecursively, which follows a
     * symbolic link to a folder. What could not be deleted stays; the caller checks the path is gone.
     *
     * The walk keeps its own stack rather than calling itself once per level, so a very deep tree
     * cannot overflow the thread's stack. Each path is checked for a link when it is reached.
     */
    fun deleteTree(path: Path) {
        // A path, and whether its children are already on the stack (it is deleted when it comes back)
        val stack = ArrayDeque<Pair<Path, Boolean>>()
        stack.addLast(path to false)
        while (stack.isNotEmpty()) {
            val (current, listed) = stack.removeLast()
            if (!listed) {
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue
                if (!isLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    stack.addLast(current to true)
                    try {
                        Files.newDirectoryStream(current).use { children -> children.forEach { stack.addLast(it to false) } }
                    } catch (e: IOException) {
                        logger.warn(e) { "Could not delete everything in ${current.fileName}" }
                    } catch (e: DirectoryIteratorException) {
                        logger.warn(e) { "Could not delete everything in ${current.fileName}" }
                    }
                    continue
                }
            }
            // A link or a junction is removed as itself
            try {
                Files.delete(current)
            } catch (e: IOException) {
                logger.debug(e) { "Could not delete ${current.fileName}" }
            }
        }
    }

    /**
     * A symbolic link, a Windows junction or another reparse point that leads elsewhere: anything
     * whose real location is not the folder it is listed in.
     *
     * A folder is judged by its real path alone. Windows reports every reparse point as "other",
     * and a junction as a folder too; but a cloud-sync placeholder folder or a deduplicated one is a
     * reparse point whose real path is where it is listed, and is the folder itself. A file that is
     * a reparse point is still treated as a link: it is removed as itself, which is harmless.
     *
     * A path that cannot be checked ([LinkState.UNKNOWN]) counts as a link too, so nothing behind it
     * is touched.
     */
    fun isLink(path: Path): Boolean = linkState(path) != LinkState.NOT_A_LINK

    /** What [isLink] found: a link, not a link, or a path whose attributes or real location could not be read. */
    enum class LinkState { NOT_A_LINK, LINK, UNKNOWN }

    /** [isLink], telling a link apart from a path that could not be checked, for an accurate message. */
    fun linkState(path: Path): LinkState = try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val link = when {
            attrs.isSymbolicLink -> true
            !attrs.isDirectory -> attrs.isOther
            else -> !resolvesToItself(path)
        }
        if (link) LinkState.LINK else LinkState.NOT_A_LINK
    } catch (e: IOException) {
        LinkState.UNKNOWN
    }

    /**
     * Whether [path]'s real location is where it is listed: true for an ordinary file or folder,
     * also one Windows tags with a reparse point (a cloud-sync placeholder, a deduplicated or
     * compressed file); false for a link, which resolves elsewhere.
     *
     * @throws IOException when the real location cannot be read
     */
    fun resolvesToItself(path: Path): Boolean {
        val parent = path.toAbsolutePath().parent ?: return false
        return samePath(path.toRealPath(), parent.toRealPath().resolve(path.fileName.toString()))
    }

    /**
     * Whether [real] is [listed]. macOS ignores the Unicode form of names, and its real path gives
     * the form on disk (NFD, from a folder made outside Java or copied from another system) while
     * the listed name may be NFC: the same folder, not a link. Elsewhere the forms are two names.
     *
     * The real path also gives the letter case on disk, which on a file system that ignores case
     * can differ from the listed name (a chapter renamed by case only): the same folder when only
     * the last name differs, by case alone, in the same parent, and the parent holds no entry of
     * exactly the listed name. Where case counts (a Windows folder set case-sensitive), a junction
     * can lead to a sibling named in another case, and then both names are listed. The paths are
     * compared as text, never with [Path.equals]: on Windows that ignores case, and would call such
     * a junction the folder it leads to.
     */
    private fun samePath(real: Path, listed: Path): Boolean =
        samePath(form(real.toString()), form(listed.toString()), real.parent == listed.parent) { hasEntryNamedExactly(listed) }

    /**
     * [samePath] on the paths' text ([form] already applied): the same when the text is equal, or
     * when it differs by letter case alone, in [sameParent], and [parentListsExactly] the listed
     * name is false. Anything else is a link.
     */
    internal fun samePath(real: String, listed: String, sameParent: Boolean, parentListsExactly: () -> Boolean): Boolean {
        if (real == listed) return true
        return sameParent && real.equals(listed, ignoreCase = true) && !parentListsExactly()
    }

    private fun form(name: String): String = if (IGNORES_UNICODE_FORM) Normalizer.normalize(name, Normalizer.Form.NFC) else name

    /** Whether [path]'s parent lists an entry of exactly [path]'s name; true when it cannot be read. */
    private fun hasEntryNamedExactly(path: Path): Boolean {
        val parent = path.parent ?: return true
        val name = form(path.fileName.toString())
        return try {
            Files.newDirectoryStream(parent).use { children -> children.any { form(it.fileName.toString()) == name } }
        } catch (e: IOException) {
            true
        }
    }

    private val IGNORES_UNICODE_FORM = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}

// ZOUKAN: whole file <--
