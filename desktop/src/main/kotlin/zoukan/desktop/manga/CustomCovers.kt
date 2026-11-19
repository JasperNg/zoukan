package zoukan.desktop.manga

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import zoukan.desktop.compat.localized
import zoukan.desktop.download.ImageType
import zoukan.desktop.image.MemoryImageIo
import zoukan.desktop.secrets.EncryptedFiles
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where a library entry's custom cover is kept, as Android's CoverCache: covers/custom/<md5 of the id>.
 *
 * ZOUKAN: the files are encrypted with the file key ([codec]), like the cookies and preference
 * files. Covers saved before that are plain; they are read as they are and rewritten encrypted the
 * first time they are read. A custom cover is the user's own: it is never deleted here, not even
 * when it cannot be decrypted (its key may still be recovered from the secret store).
 */
object CustomCovers {
    /** The covers folder; set once at start by the image loader's setup. */
    @Volatile
    lateinit var coverDir: File

    /** The file key; set once at start by the image loader's setup. Without it covers are not saved. */
    @Volatile
    var codec: SettingsFileCodec? = null

    /** The folder of custom covers inside [coverDir]; never part of the cover cache. */
    const val FOLDER = "custom"

    /**
     * Reads, writes, copies, deletes and lazy rewrites of custom covers, so a rewrite never puts
     * back a cover just replaced or deleted, and a read never holds the file open while another
     * operation deletes or replaces it.
     */
    private val lock = Any()

    fun file(mangaId: Long): File = File(File(coverDir, FOLDER), md5(mangaId.toString()))

    /** Whether [file] is one of the custom covers (not a local source's cover, which is the user's file as it is). */
    fun isCustomCover(file: File): Boolean =
        file.absoluteFile.parentFile?.normalize() == File(coverDir, FOLDER).absoluteFile.normalize()

    /** Saves [image] as [mangaId]'s custom cover, encrypted. */
    fun save(mangaId: Long, image: File) = save(mangaId, image.readBytes())

    /**
     * Saves the image [bytes] as [mangaId]'s custom cover, encrypted, without a plain copy on disk.
     * What is no image (by its content, as a local entry's cover) or declares an absurd size is
     * refused before anything is written, so the cover it had is kept.
     */
    fun save(mangaId: Long, bytes: ByteArray) {
        val codec = codec ?: throw IOException("The file key is not available, so the cover cannot be saved")
        requireImage(bytes)
        synchronized(lock) { EncryptedFiles.write(file(mangaId), bytes, codec) }
    }

    private fun requireImage(bytes: ByteArray) {
        zoukan.desktop.image.checkImageSize(bytes)
        if (ImageType.fromBytes(bytes) != null) return
        // Exception, not Throwable: running out of memory on a huge image is not "not an image"
        val readable = try {
            MemoryImageIo.read(bytes) != null
        } catch (e: Exception) {
            false
        }
        if (!readable) throw IOException(localized(DMR.strings.not_an_image))
    }

    /**
     * Deletes [mangaId]'s custom cover, under the same lock as the lazy rewrite in [read], so a
     * rewrite under way cannot put it back. Returns whether there is none now.
     */
    fun delete(mangaId: Long): Boolean = synchronized(lock) {
        val file = file(mangaId)
        (file.delete() || !file.exists()).also { deleted ->
            if (!deleted) logcat(LogPriority.WARN) { "Could not delete the custom cover ${file.name}" }
        }
    }

    /**
     * Copies [fromMangaId]'s custom cover to [toMangaId] as it is stored (both use the same file
     * key), replacing the one [toMangaId] has, as Android's migration does. The copy is written
     * whole before it replaces anything, so a failure leaves [toMangaId]'s cover as it was; it runs
     * under the same lock as [save], [delete] and the lazy rewrite in [read]. Returns whether
     * there was a cover to copy.
     */
    fun copy(fromMangaId: Long, toMangaId: Long): Boolean = synchronized(lock) {
        val from = file(fromMangaId)
        if (!from.isFile) return@synchronized false
        EncryptedFiles.replace(file(toMangaId), from.readBytes())
        true
    }

    /**
     * The image of the custom cover [file], decrypted. Held under [lock] so an open read handle
     * cannot block a concurrent delete or replace on Windows; a plain cover (saved before
     * encryption) is rewritten encrypted in the same critical section, and stays plain if that fails.
     */
    fun read(file: File): ByteArray = synchronized(lock) {
        val codec = codec ?: return@synchronized file.readBytes()
        val contents = EncryptedFiles.read(file, codec)
        if (!contents.encrypted) {
            try {
                EncryptedFiles.write(file, contents.bytes, codec)
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not encrypt the custom cover ${file.name}; it stays as it is" }
            }
        }
        contents.bytes
    }

    /**
     * Deletes the temporary files that cover writes cut short left in the custom covers folder (the
     * app was closed or crashed mid-write), never a cover. Called at start, before any cover is
     * written. Returns how many were deleted.
     */
    fun deleteTemporaryFiles(): Int = EncryptedFiles.deleteTemporaryFiles(File(coverDir, FOLDER), COVER_NAME)

    /**
     * "Clear cover cache": deletes the source covers kept in [coverDir], which download again when
     * shown. Custom covers (the [FOLDER] subfolder) are the user's own and are never touched, as
     * Android's CoverCache keeps them apart. Returns how many files were deleted.
     */
    fun clearCache(coverDir: File): Int =
        coverDir.listFiles().orEmpty().count { it.isFile && it.name != FOLDER && it.delete() }

    /** A custom cover's file name: [md5] of the entry's id. */
    private val COVER_NAME = Regex("[0-9a-f]{32}")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
