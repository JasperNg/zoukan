package zoukan.desktop.property

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Whether a string is one file or folder name that works on Windows, macOS and Linux, and what is
 * wrong with it when it is not.
 */
object PortableName {

    enum class Problem {
        EMPTY,

        /** `.` or `..`: the folder itself or its parent. */
        DOT_NAME,

        /** One of `<>:"/\|?*`, reserved on Windows (`/` everywhere). */
        RESERVED_CHARACTER,

        /** U+0000 to U+001F, refused by Windows (and U+0000 everywhere). */
        CONTROL_CHARACTER,

        /** Half of a surrogate pair: not valid UTF-8, so macOS refuses it and Java writes `?`. */
        LONE_SURROGATE,

        /** Windows drops a trailing dot or space, so the name on disk is another one. */
        TRAILING_DOT_OR_SPACE,

        /** CON, PRN, AUX, NUL, COM0-9, LPT0-9 (also with an extension): a device on Windows, not a file. */
        WINDOWS_DEVICE_NAME,

        /** Over 255 UTF-8 bytes (Linux, macOS) or 255 UTF-16 units (Windows). */
        TOO_LONG,

        /** Over the limit the Android app keeps to (240 bytes for a whole name). */
        OVER_ANDROID_LIMIT,

        /** Resolved against a folder, it ends up somewhere else. */
        ESCAPES_FOLDER,
    }

    private const val RESERVED = "<>:\"/\\|?*"

    private val deviceNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        // Windows also treats the superscript digits 1-3 as device numbers
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
            add("COM$d")
            add("LPT$d")
        }
    }

    /** Everything wrong with [name] as a file name; empty when it is a valid name everywhere. */
    fun problems(name: String, androidLimitBytes: Int = 240): Set<Problem> {
        val found = linkedSetOf<Problem>()
        if (name.isEmpty()) return setOf(Problem.EMPTY)
        if (name == "." || name == "..") found += Problem.DOT_NAME
        if (name.any { it in RESERVED }) found += Problem.RESERVED_CHARACTER
        if (name.any { it.code < 0x20 }) found += Problem.CONTROL_CHARACTER
        if (hasLoneSurrogate(name)) found += Problem.LONE_SURROGATE
        if (name.last() == '.' || name.last() == ' ') found += Problem.TRAILING_DOT_OR_SPACE
        if (isWindowsDeviceName(name)) found += Problem.WINDOWS_DEVICE_NAME
        val utf8 = name.toByteArray(Charsets.UTF_8).size
        if (utf8 > 255 || name.length > 255) found += Problem.TOO_LONG
        if (utf8 > androidLimitBytes) found += Problem.OVER_ANDROID_LIMIT
        if (escapesFolder(name)) found += Problem.ESCAPES_FOLDER
        return found
    }

    fun isWindowsDeviceName(name: String): Boolean =
        name.substringBefore('.').trimEnd(' ').uppercase() in deviceNames

    fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i++
            }
        }
        return false
    }

    private val folder: Path = Path.of("downloads", "source").toAbsolutePath()

    private fun escapesFolder(name: String): Boolean = try {
        val resolved = folder.resolve(name).normalize()
        resolved.parent != folder
    } catch (_: InvalidPathException) {
        // Not a path on this OS at all; the character checks name the reason
        false
    }
}
