package xyz.nulldev.androidcompat.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PortableFileNamesTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `rewrites reserved Windows device names with and without extensions`() {
        val reserved = listOf(
            "CON", "con", "PrN", "aux", "NUL",
            "COM0", "COM1", "COM9", "COM¹", "COM²", "COM³",
            "LPT0", "LPT1", "LPT9", "LPT¹", "LPT²", "LPT³",
        )
        reserved.forEach { name ->
            PortableFileNames.isWindowsDeviceName(name).shouldBeTrue()
            val sanitized = PortableFileNames.portable(name, maxBytes = 255)
            sanitized shouldBe "${name}_"
            PortableFileNames.isWindowsDeviceName(sanitized).shouldBeFalse()

            val withExt = PortableFileNames.portable("$name.txt", maxBytes = 255)
            withExt shouldBe "${name}_.txt"
            PortableFileNames.isWindowsDeviceName(withExt).shouldBeFalse()
        }

        // Trailing spaces before the extension still count as a device name on Windows
        PortableFileNames.isWindowsDeviceName("NUL .txt").shouldBeTrue()
        PortableFileNames.portable("NUL .txt", maxBytes = 255) shouldBe "NUL _.txt"

        // Look-alikes that are valid filenames are preserved
        listOf("CONSOLE", "NULL", "COM10", "LPT10", "my.CON").forEach { ok ->
            PortableFileNames.isWindowsDeviceName(ok).shouldBeFalse()
            PortableFileNames.portable(ok, maxBytes = 255) shouldBe ok
        }
    }

    @Test
    fun `replaces invalid FAT and control characters`() {
        val raw = "a\"b*c/d:e<f>g?h\\i|j\u0000k\u001Fl\u007Fm"
        PortableFileNames.portable(raw, maxBytes = 255) shouldBe "a_b_c_d_e_f_g_h_i_j_k_l_m"

        for (code in 0x00..0x1f) {
            PortableFileNames.isValidFatFilenameChar(code.toChar()).shouldBeFalse()
        }
        PortableFileNames.isValidFatFilenameChar(0x7f.toChar()).shouldBeFalse()
        listOf('"', '*', '/', ':', '<', '>', '?', '\\', '|').forEach { ch ->
            PortableFileNames.isValidFatFilenameChar(ch).shouldBeFalse()
        }
        PortableFileNames.isValidFatFilenameChar('a').shouldBeTrue()
    }

    @Test
    fun `trims leading and trailing dots and spaces including after truncation`() {
        PortableFileNames.portable(" .  hello world . ", maxBytes = 255) shouldBe "hello world"
        PortableFileNames.portable("...", maxBytes = 255) shouldBe PortableFileNames.INVALID
        PortableFileNames.portable("   ", maxBytes = 255) shouldBe PortableFileNames.INVALID
        PortableFileNames.portable("", maxBytes = 255) shouldBe PortableFileNames.INVALID
        PortableFileNames.portable(" . . ", maxBytes = 255) shouldBe PortableFileNames.INVALID

        // Cut lands right after a dot/space, so the newly exposed trailing dot/space is trimmed
        PortableFileNames.portable("abc .def", maxBytes = 5) shouldBe "abc"
    }

    @Test
    fun `replaces lone surrogates while keeping valid surrogate pairs intact`() {
        val highOnly = "a\uD83Db"
        val lowOnly = "a\uDE00b"
        val reversed = "a\uDE00\uD83Db"
        val validEmoji = "a\uD83D\uDE00b" // 😀 is 4 UTF-8 bytes

        PortableFileNames.portable(highOnly, maxBytes = 255) shouldBe "a_b"
        PortableFileNames.portable(lowOnly, maxBytes = 255) shouldBe "a_b"
        PortableFileNames.portable(reversed, maxBytes = 255) shouldBe "a__b"
        PortableFileNames.portable(validEmoji, maxBytes = 255) shouldBe validEmoji

        // Truncation inside the 4-byte emoji drops it cleanly rather than leaving a lone surrogate
        PortableFileNames.portable(validEmoji, maxBytes = 3) shouldBe "a"
        PortableFileNames.portable("\uD83D\uDE00", maxBytes = 3) shouldBe PortableFileNames.INVALID
    }

    @Test
    fun `truncates multi-byte UTF-8 and device-name suffixes within maxBytes`() {
        val cjk = "漫画".repeat(20) // 3 bytes per char = 120 bytes
        val cut = PortableFileNames.portable(cjk, maxBytes = 10)
        cut.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 10
        cut shouldBe "漫画漫" // 9 bytes

        // Device name whose underscore suffix exceeds maxBytes falls back to INVALID
        PortableFileNames.portable("CON", maxBytes = 3) shouldBe PortableFileNames.INVALID

        // Truncated extension exposing a device name still inserts '_' within maxBytes
        val conExtCut = PortableFileNames.portable("CON.txt", maxBytes = 4)
        conExtCut.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 4
        conExtCut shouldBe "CON_"
    }

    @Test
    fun `disallowNonAscii hex-encodes non-ASCII characters as UTF-8 bytes`() {
        PortableFileNames.portable("café", maxBytes = 255, disallowNonAscii = true) shouldBe "cafc3a9"
        PortableFileNames.portable("日本語", maxBytes = 255, disallowNonAscii = true) shouldBe "e697a5e69cace8aa9e"
        PortableFileNames.portable("café", maxBytes = 5, disallowNonAscii = true) shouldBe "cafc3"
    }

    @Test
    fun `output satisfies cross-OS portability invariants and can be created on disk`() {
        val samples = listOf(
            "Normal Title (2026)",
            "CON",
            "NUL.cbz",
            "   ...   ",
            "ch:1/2\\3?*",
            "a".repeat(300),
            "😀".repeat(80),
            "dot. ",
            "bad\uD800name",
        )
        val maxBytes = 200
        samples.forEachIndexed { idx, raw ->
            val name = PortableFileNames.portable(raw, maxBytes = maxBytes)
            name.isNotEmpty().shouldBeTrue()
            name.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual maxBytes
            name.shouldNotEndWith(".")
            name.shouldNotEndWith(" ")
            PortableFileNames.isWindowsDeviceName(name).shouldBeFalse()
            name.indices.forEach { i ->
                PortableFileNames.isLoneSurrogate(name, i).shouldBeFalse()
                PortableFileNames.isValidFatFilenameChar(name[i]).shouldBeTrue()
            }

            val dir = File(temp, "case-$idx").apply { mkdirs() }
            val created = File(dir, name).createNewFile()
            created.shouldBeTrue()
        }
    }
}
