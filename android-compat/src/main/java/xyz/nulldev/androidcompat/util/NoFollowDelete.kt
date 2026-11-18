package xyz.nulldev.androidcompat.util

// ZOUKAN: whole file -->

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
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
     */
    fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!isLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.newDirectoryStream(path).use { children -> children.forEach { deleteTree(it) } }
            } catch (e: IOException) {
                logger.warn(e) { "Could not delete everything in ${path.fileName}" }
            }
        }
        // A link or a junction is removed as itself
        try {
            Files.delete(path)
        } catch (e: IOException) {
            logger.debug(e) { "Could not delete ${path.fileName}" }
        }
    }

    /**
     * A symbolic link, a Windows junction or another reparse point: anything whose real location is
     * not the folder it is listed in.
     */
    fun isLink(path: Path): Boolean = try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attrs.isSymbolicLink || attrs.isOther -> true
            !attrs.isDirectory -> false
            else -> {
                val parent = path.toAbsolutePath().parent
                parent != null && !samePath(path.toRealPath(), parent.toRealPath().resolve(path.fileName.toString()))
            }
        }
    } catch (e: IOException) {
        // Unreadable: treated as a link, so nothing behind it is touched
        true
    }

    /**
     * Whether [real] is [listed]. macOS ignores the Unicode form of names, and its real path gives
     * the form on disk (NFD, from a folder made outside Java or copied from another system) while
     * the listed name may be NFC: the same folder, not a link. Elsewhere the forms are two names.
     */
    private fun samePath(real: Path, listed: Path): Boolean {
        if (real == listed) return true
        if (!IGNORES_UNICODE_FORM) return false
        return Normalizer.normalize(real.toString(), Normalizer.Form.NFC) == Normalizer.normalize(listed.toString(), Normalizer.Form.NFC)
    }

    private val IGNORES_UNICODE_FORM = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}

// ZOUKAN: whole file <--
