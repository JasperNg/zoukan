package zoukan.desktop.download

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * File names exactly as the Android app builds them (DiskUtil in core/common androidMain), so a
 * downloads folder copied from a phone is found on desktop and the other way round.
 */
object FileNames {
    // Safe theoretical max filename size is 255 bytes and 1 char = 2-4 bytes (UTF-8), less 15
    // reserved characters, as on Android
    const val MAX_FILE_NAME_BYTES = 240

    fun buildValidFilename(
        origName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
        disallowNonAscii: Boolean = false,
    ): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) return "(invalid)"
        val sb = StringBuilder(name.length)
        name.forEach { c ->
            if (disallowNonAscii && c >= 0x80.toChar()) {
                sb.append(c.toString().toByteArray(Charsets.UTF_8).toHexString(HexFormat { upperCase = false }))
            } else if (isValidFatFilenameChar(c)) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        return truncateToLength(sb.toString(), maxBytes)
    }

    /** Truncates to [maxBytes] of UTF-8 without cutting a character in half. */
    fun truncateToLength(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
        val cb = CharBuffer.allocate(maxBytes)
        decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes), cb, true)
        decoder.flush(cb)
        return String(cb.array(), 0, cb.position())
    }

    private fun isValidFatFilenameChar(c: Char): Boolean {
        if (c in 0x00.toChar()..0x1f.toChar()) return false
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
            else -> true
        }
    }

    /** Case-insensitive natural order ("2" before "10"), like the Android app's for page files. */
    val naturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
