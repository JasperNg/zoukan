package xyz.nulldev.androidcompat.util

// ZOUKAN: whole file -->

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * File names that are one valid name on Windows, macOS and Linux: no reserved or control
 * character, no half surrogate pair (macOS refuses it, Java writes `?`), no dot or space at the end
 * (Windows drops it), and not a Windows device name (`NUL`, `CON.txt`). In android-compat, the
 * lowest module the app's name builders (downloads, saved pictures, settings files) share.
 */
object PortableFileNames {

    /**
     * [origName] made a portable name of at most [maxBytes] UTF-8 bytes: dots and spaces trimmed at
     * both ends, reserved and control characters and half surrogate pairs as `_` (before the cut, so
     * the cut cannot make one), a dot or space left at the end by the cut dropped, and a Windows
     * device name given `_` after its base name (`NUL` is `NUL_`, `CON.txt` is `CON_.txt`). With
     * [disallowNonAscii], each non-ASCII character is written as its UTF-8 bytes in hex, as the
     * Android app's "Disallow non-ASCII filenames". `(invalid)` when nothing is left.
     */
    fun portable(origName: String, maxBytes: Int, disallowNonAscii: Boolean = false): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) return INVALID
        val sb = StringBuilder(name.length)
        name.forEachIndexed { i, c ->
            if (disallowNonAscii && c >= 0x80.toChar()) {
                c.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                    val v = b.toInt() and 0xff
                    sb.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0xf, 16))
                }
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
        return out.takeIf { it.isNotEmpty() && !isWindowsDeviceName(it) } ?: INVALID
    }

    /** What [portable] gives a name with nothing left in it. */
    const val INVALID = "(invalid)"

    /** CON, PRN, AUX, NUL, COM0-9, LPT0-9 (and the superscript 1-3), with or without an extension. */
    private val windowsDeviceNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
            add("COM$d")
            add("LPT$d")
        }
    }

    fun isWindowsDeviceName(name: String): Boolean =
        name.substringBefore('.').trimEnd(' ').uppercase(java.util.Locale.ROOT) in windowsDeviceNames

    /** Whether [s]'s character at [i] is half of a surrogate pair without its other half. */
    fun isLoneSurrogate(s: String, i: Int): Boolean {
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

    fun isValidFatFilenameChar(c: Char): Boolean {
        if (c in 0x00.toChar()..0x1f.toChar()) return false
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
            else -> true
        }
    }
}

// ZOUKAN: whole file <--
