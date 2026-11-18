package zoukan.desktop.property

import kotlin.random.Random

/**
 * Text a source, a user or a damaged file may hand the app: titles, chapter names, scanlators,
 * preference keys and values. Built from pieces known to break file names and parsers, mixed with
 * random characters, and sometimes padded to just around the 240-byte name limit with characters
 * of 1 to 4 UTF-8 bytes, so a truncation can fall inside a character.
 */
object NastyStrings {

    /** Pieces that are each a known hard case for a file name. */
    val pieces: List<String> = listOf(
        "", " ", "  ", ".", "..", "...", " .", ". ", "a.", "a ",
        // Windows device names, in any case and with an extension
        "CON", "con", "Con", "PRN", "AUX", "NUL", "nul", "COM1", "com9", "LPT1", "lpt9", "COM\u00b9", "CON.txt", "aux.tar.gz", "NUL ",
        // Reserved on Windows, and the separators
        "<", ">", ":", "\"", "/", "\\", "|", "?", "*", "../", "..\\", "/etc/passwd", "C:\\", "\\\\?\\",
        // Control characters and odd spaces
        "\u0000", "\u0001", "\t", "\n", "\r", "\u001f", "\u007f", "\u0085", "\u00a0", "\u2028", "\u3000", "\ufeff", "\u200b", "\u200d",
        // Emoji, flags, ZWJ sequences, a mathematical letter (all surrogate pairs)
        "\ud83d\ude00", "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67", "\ud83c\uddef\ud83c\uddf5", "\ud835\udd4f",
        // Combining characters and strings that differ only by Unicode normalisation or case
        "e\u0301", "\u0301", "\u00e9", "\u00c5", "\u212b", "A\u030a", "\u00df", "\u1e9e", "\u0130", "\u0131", "\ufb01", "\u01c4",
        // Right-to-left text and direction marks
        "\u202e", "\u200f", "\u05e2\u05d1\u05e8\u05d9\u05ea", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629",
        // CJK and fullwidth look-alikes of reserved characters
        "\u6f2b\u753b", "\u307e\u3093\u304c", "\ud55c\uad6d\uc5b4", "\uff1a", "\uff0f", "\uff3c", "\uff1f",
        // Ordinary chapter-name shapes
        "Ch. 1", "Vol.2 Ch.3", "Chapter 10.5", "%2e%2e", "~", "\$HOME", "a", "Z", "0", "_",
    )

    /** Lone surrogates: a Kotlin String may hold them, UTF-8 cannot. */
    val loneSurrogates: List<String> = listOf("\ud83d", "\ude00", "\udbff", "\ud800")

    /** One character each of 1, 2, 3 and 4 UTF-8 bytes, and the two characters Windows trims. */
    private val paddingUnits = listOf("a", "\u00e9", "\u6f2b", "\ud83d\ude00", " ", ".")

    /**
     * A random string. [allowLoneSurrogates] false keeps it valid Unicode, for data that only ever
     * comes from UTF-8 (the database, XML, protobuf).
     */
    fun next(random: Random, allowLoneSurrogates: Boolean = true, maxPieces: Int = 10, padding: Boolean = true): String {
        val sb = StringBuilder()
        repeat(random.nextInt(0, maxPieces + 1)) {
            sb.append(
                when (random.nextInt(10)) {
                    0, 1, 2, 3 -> pieces.random(random)
                    4 -> randomAscii(random)
                    5 -> randomBmp(random, allowLoneSurrogates)
                    6 -> String(Character.toChars(random.nextInt(0x10000, 0x110000)))
                    7 -> if (allowLoneSurrogates) loneSurrogates.random(random) else pieces.random(random)
                    else -> randomWord(random)
                },
            )
        }
        if (padding && random.nextInt(5) == 0) {
            // Around the limit: 200 to 260 bytes of one unit, before or after the rest
            val unit = paddingUnits.random(random)
            val bytes = unit.toByteArray(Charsets.UTF_8).size
            val pad = unit.repeat(random.nextInt(200, 261) / bytes)
            if (random.nextBoolean()) sb.insert(0, pad) else sb.append(pad)
            if (random.nextBoolean()) sb.append(pieces.random(random))
        }
        return sb.toString()
    }

    private fun randomAscii(random: Random): String = random.nextInt(0, 0x80).toChar().toString()

    private fun randomBmp(random: Random, allowLoneSurrogates: Boolean): String {
        val c = random.nextInt(0, 0x10000).toChar()
        return if (!allowLoneSurrogates && c.isSurrogate()) "x" else c.toString()
    }

    private fun randomWord(random: Random): String =
        (1..random.nextInt(1, 12)).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.".random(random) }
            .joinToString("")
}
