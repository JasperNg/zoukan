package zoukan.desktop.manga

import java.io.File
import java.security.MessageDigest

/** Where a library entry's custom cover is kept, as Android's CoverCache: covers/custom/<md5 of the id>. */
object CustomCovers {
    /** The covers folder; set once at start by the image loader's setup. */
    @Volatile
    lateinit var coverDir: File

    /** The folder of custom covers inside [coverDir]; never part of the cover cache. */
    const val FOLDER = "custom"

    fun file(mangaId: Long): File = File(File(coverDir, FOLDER), md5(mangaId.toString()))

    /**
     * "Clear cover cache": deletes the source covers kept in [coverDir], which download again when
     * shown. Custom covers (the [FOLDER] subfolder) are the user's own and are never touched, as
     * Android's CoverCache keeps them apart. Returns how many files were deleted.
     */
    fun clearCache(coverDir: File): Int =
        coverDir.listFiles().orEmpty().count { it.isFile && it.name != FOLDER && it.delete() }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
