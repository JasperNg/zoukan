package zoukan.desktop.manga

import java.io.File
import java.security.MessageDigest

/** Where a library entry's custom cover is kept, as Android's CoverCache: covers/custom/<md5 of the id>. */
object CustomCovers {
    /** The covers folder; set once at start by the image loader's setup. */
    @Volatile
    lateinit var coverDir: File

    fun file(mangaId: Long): File = File(File(coverDir, "custom"), md5(mangaId.toString()))

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
