package zoukan.desktop.download

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * File names as the Android app builds them (DiskUtil in core/common androidMain), so a downloads
 * folder copied from a phone is found on desktop and the other way round.
 *
 * Android's name is kept whenever it is a valid name on Windows, macOS and Linux. A few are not:
 * a Windows device name (`NUL`, `CON.txt`), a name cut at the limit that ends in a dot or a space
 * (Windows drops it), and half a surrogate pair (macOS refuses it, Java writes `?`). For those the
 * desktop writes [buildValidFilename]'s portable name instead, and still looks downloads up by
 * Android's name ([androidValidFilename]), so a folder Android or an older desktop wrote is found.
 */
object FileNames {
    // Safe theoretical max filename size is 255 bytes and 1 char = 2-4 bytes (UTF-8), less 15
    // reserved characters, as on Android
    const val MAX_FILE_NAME_BYTES = 240

    /** The name the desktop writes: Android's when that is valid on every OS, else a portable one. */
    fun buildValidFilename(
        origName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
        disallowNonAscii: Boolean = false,
    ): String {
        val android = androidValidFilename(origName, maxBytes, disallowNonAscii)
        return if (isPortable(android)) android else portableFilename(origName, maxBytes, disallowNonAscii)
    }

    /** Android's DiskUtil.buildValidFilename, unchanged: the name Android (and older desktop versions) wrote. */
    fun androidValidFilename(
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

    /**
     * Android's name with what makes it invalid somewhere taken out: half a surrogate pair becomes
     * `_` (before the cut, so the cut cannot turn it into `?`), a dot or space left at the end by
     * the cut goes, and a Windows device name gets `_` after its base name (`NUL` is `NUL_`,
     * `CON.txt` is `CON_.txt`). Equal to [androidValidFilename] whenever that is valid.
     */
    internal fun portableFilename(origName: String, maxBytes: Int, disallowNonAscii: Boolean): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) return "(invalid)"
        val sb = StringBuilder(name.length)
        name.forEachIndexed { i, c ->
            if (disallowNonAscii && c >= 0x80.toChar()) {
                sb.append(c.toString().toByteArray(Charsets.UTF_8).toHexString(HexFormat { upperCase = false }))
            } else if (isValidFatFilenameChar(c) && !isLoneSurrogate(name, i)) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        var out = truncateToLength(sb.toString(), maxBytes).trimEnd('.', ' ')
        if (isWindowsDeviceName(out)) {
            val baseEnd = out.indexOf('.').takeIf { it >= 0 } ?: out.length
            out = truncateToLength(out.substring(0, baseEnd) + "_" + out.substring(baseEnd), maxBytes).trimEnd('.', ' ')
        }
        return out.takeIf { it.isNotEmpty() && !isWindowsDeviceName(it) } ?: "(invalid)"
    }

    /**
     * Whether [name] is one file name that works on Windows, macOS and Linux: no reserved or control
     * character, no half surrogate pair, no dot or space at the end, not a Windows device name.
     */
    fun isPortable(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." && name != ".." &&
            name.indices.none { i -> !isValidFatFilenameChar(name[i]) || isLoneSurrogate(name, i) } &&
            name.last() != '.' && name.last() != ' ' &&
            !isWindowsDeviceName(name)

    /**
     * The names a folder written under [name] may have on disk: [name], and on macOS and Linux,
     * where Java writes half a surrogate pair as `?`, that spelling of it.
     */
    fun spellingsOnDisk(name: String): List<String> {
        if (name.indices.none { isLoneSurrogate(name, it) }) return listOf(name)
        val questionMarks = buildString { name.forEachIndexed { i, c -> append(if (isLoneSurrogate(name, i)) '?' else c) } }
        return listOf(name, questionMarks)
    }

    /** CON, PRN, AUX, NUL, COM0-9, LPT0-9 (and the superscript 1-3), with or without an extension. */
    private val windowsDeviceNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "\u00b9", "\u00b2", "\u00b3")) {
            add("COM$d")
            add("LPT$d")
        }
    }

    private fun isWindowsDeviceName(name: String): Boolean =
        name.substringBefore('.').trimEnd(' ').uppercase(java.util.Locale.ROOT) in windowsDeviceNames

    private fun isLoneSurrogate(s: String, i: Int): Boolean {
        val c = s[i]
        return when {
            c.isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
            c.isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
            else -> false
        }
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
