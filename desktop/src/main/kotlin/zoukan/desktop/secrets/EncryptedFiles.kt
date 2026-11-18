package zoukan.desktop.secrets

import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Single files encrypted whole with the file key ([FileCipher]): the cover cache and custom covers.
 * Files written before encryption are plain and still read; the caller decides whether to rewrite
 * them ([Contents.encrypted]).
 */
object EncryptedFiles {
    class Contents(val bytes: ByteArray, val encrypted: Boolean)

    /** A file that is encrypted but cannot be decrypted (damaged, or written with a key that is lost). */
    class UnreadableFileException(file: File, cause: Throwable) :
        IOException("${file.name} cannot be decrypted", cause)

    /**
     * [file]'s contents: decrypted when it is encrypted, as they are when it is plain. Throws
     * [UnreadableFileException] when it is encrypted but cannot be decrypted, and leaves it as it is.
     */
    fun read(file: File, codec: SettingsFileCodec): Contents {
        val stored = file.readBytes()
        if (!codec.isEncoded(stored)) return Contents(stored, encrypted = false)
        val plain = try {
            codec.decode(stored)
        } catch (e: Exception) {
            throw UnreadableFileException(file, e)
        }
        return Contents(plain, encrypted = true)
    }

    /**
     * Writes [plain] encrypted into [target] through a temporary file of its own beside it, then
     * moves it into place (replacing what is there), so two writes at once never mix and a failed
     * write leaves [target] as it was.
     */
    fun write(target: File, plain: ByteArray, codec: SettingsFileCodec) {
        replace(target, codec.encode(plain))
    }

    /**
     * Writes [stored] into [target] as it is, the same way as [write]: through a temporary file of
     * its own beside it, saved to the disk before it is moved into place, so that after a crash or
     * a power loss [target] holds either what it held or [stored], never an empty or partial file.
     */
    fun replace(target: File, stored: ByteArray) {
        val dir = target.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(stored)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Deletes the temporary files that [write] and [replace] left in [dir] when the app was closed
     * or crashed mid-write: `<name>.<random number>.tmp`, for targets whose whole name matches
     * [targetName]. Nothing else in [dir] is touched, and a file that cannot be deleted is left for
     * next time. Call it when no write into [dir] can be under way (at start). Returns how many
     * files were deleted.
     */
    fun deleteTemporaryFiles(dir: File, targetName: Regex): Int {
        val temporary = Regex("(?:${targetName.pattern})\\.[0-9]{1,20}\\.tmp")
        return dir.listFiles().orEmpty().count { it.isFile && temporary.matches(it.name) && it.delete() }
    }
}
