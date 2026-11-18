package zoukan.desktop.property

import eu.kanade.tachiyomi.util.lang.Hash.md5
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/*
 * The Android app's own file-name code, copied without change so the property tests can compare
 * the desktop's names with Android's. DiskUtil lives in core/common androidMain, which the desktop
 * does not compile (it needs android.os.StatFs and friends), and DownloadProvider in the Android
 * app. AndroidNameOracleSourceTest fails when the Android source of any function here changes, so
 * this copy cannot drift from it.
 */

/** Functions copied from core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/util/storage/DiskUtil.kt. */
object DiskUtil {

    fun buildValidFilename(
        origName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
        disallowNonAscii: Boolean = false,
    ): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) {
            return "(invalid)"
        }
        val sb = StringBuilder(name.length)
        name.forEach { c ->
            if (disallowNonAscii && c >= 0x80.toChar()) {
                sb.append(
                    c.toString().toByteArray(Charsets.UTF_8).toHexString(
                        HexFormat {
                            upperCase = false
                        },
                    ),
                )
            } else if (isValidFatFilenameChar(c)) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        return truncateToLength(sb.toString(), maxBytes)
    }

    /**
     * Truncate a string to a maximum length, while maintaining valid Unicode encoding.
     */
    fun truncateToLength(s: String, maxBytes: Int): String {
        val charset = Charsets.UTF_8
        val decoder = charset.newDecoder()
        val sba = s.toByteArray(charset)
        if (sba.size <= maxBytes) {
            return s
        }
        // Ensure truncation by having byte buffer = maxBytes
        val bb = ByteBuffer.wrap(sba, 0, maxBytes)
        val cb = CharBuffer.allocate(maxBytes)
        // Ignore an incomplete character
        decoder.onMalformedInput(CodingErrorAction.IGNORE)
        decoder.decode(bb, cb, true)
        decoder.flush(cb)
        return String(cb.array(), 0, cb.position())
    }

    /**
     * Returns true if the given character is a valid filename character, false otherwise.
     */
    private fun isValidFatFilenameChar(c: Char): Boolean {
        if (0x00.toChar() <= c && c <= 0x1f.toChar()) {
            return false
        }
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
            else -> true
        }
    }

    // Safe theoretical max filename size is 255 bytes and 1 char = 2-4 bytes (UTF-8).
    // To allow for writing to ext4 through a FUSE layer in the future, also subtract 15
    // reserved characters.
    const val MAX_FILE_NAME_BYTES = 240
}

/** Functions copied from app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt, with its settings as parameters. */
object DownloadProvider {

    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean,
        includeChapterUrlHash: Boolean,
    ): String {
        var dirName = sanitizeChapterName(chapterName)
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAsciiFilenames)
        /* SY --> */ if (includeChapterUrlHash) /* SY <-- */ dirName += "_" + md5(chapterUrl).take(6)
        return dirName
    }

    /** getLegacyChapterDirNames, with the settings as parameters. */
    fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean,
        includeChapterUrlHash: Boolean,
    ): List<String> {
        val sanitizedChapterName = sanitizeChapterName(chapterName)
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$sanitizedChapterName"
                else -> sanitizedChapterName
            },
        )
        val otherChapterDirName =
            getChapterDirName(chapterName, chapterScanlator, chapterUrl, !disallowNonAsciiFilenames, !includeChapterUrlHash)
        return buildList(2) {
            add(chapterNameV1)
            add(otherChapterDirName)
        }
    }

    /** getValidChapterDirNames, with the settings as parameters. */
    fun getValidChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean,
        includeChapterUrlHash: Boolean,
    ): List<String> {
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl, disallowNonAsciiFilenames, includeChapterUrlHash)
        val legacyChapterDirNames =
            getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl, disallowNonAsciiFilenames, includeChapterUrlHash)
        return buildList {
            add(chapterDirName)
            add("$chapterDirName.cbz")
            legacyChapterDirNames.forEach {
                add(it)
                add("$it.cbz")
            }
        }
    }

    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }
}
