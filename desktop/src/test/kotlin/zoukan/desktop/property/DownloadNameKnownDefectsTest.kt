package zoukan.desktop.property

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.download.FileNames
import zoukan.desktop.property.PortableName.Problem

/**
 * Names [DownloadNamePropertyTest] found that are not valid on every OS. Each test checks the
 * correct behaviour with the smallest input found, so it fails today; it is disabled until the
 * defect is fixed. The Android app builds the same names (each test checks that too), so a fix
 * changes names Android also uses: it must keep finding downloads made under the old name, and the
 * difference goes in desktop/PARITY.md.
 */
class DownloadNameKnownDefectsTest {

    private fun assertValid(input: String, name: String) {
        // Today the desktop and Android agree on the name; the defect is in both
        name shouldBe DiskUtil.buildValidFilename(input)
        PortableName.problems(name) shouldBe emptySet()
    }

    @Disabled("Known defect: a Windows device name (NUL, CON, COM1...) is kept as the folder name, as on Android (FileNames.kt:47)")
    @Test
    fun `a Windows device name is not a folder name`() {
        assertValid("nul", FileNames.buildValidFilename("nul"))
        assertValid("CON.txt", FileNames.buildValidFilename("CON.txt"))
        // A chapter name with the hash turned off, and CON.<anything> with it on
        PortableName.problems(DownloadPaths.chapterDirName("CON", null, "/c/1", disallowNonAscii = false, includeHash = false)) shouldBe emptySet()
        PortableName.problems(DownloadPaths.chapterDirName("CON.1", null, "/c/1", disallowNonAscii = false, includeHash = true)) shouldBe emptySet()
    }

    @Disabled("Known defect: a name cut to 240 bytes can end in a space or a dot, which Windows drops, as on Android (FileNames.kt:21 trims before FileNames.kt:33 truncates)")
    @Test
    fun `a name cut at the limit does not end in a space or a dot`() {
        val input = "a".repeat(239) + " b"
        assertValid(input, FileNames.buildValidFilename(input))
        val dot = "a".repeat(239) + ".b"
        assertValid(dot, FileNames.buildValidFilename(dot))
    }

    @Disabled("Known defect: half a surrogate pair is kept in the name, which macOS refuses and Java writes as '?', as on Android (FileNames.kt:47)")
    @Test
    fun `half a surrogate pair does not reach the name`() {
        assertValid("\udbff", FileNames.buildValidFilename("\udbff"))
    }

    @Disabled("Known defect: half a surrogate pair in a name over the limit becomes '?', reserved on Windows, as on Android (FileNames.kt:38)")
    @Test
    fun `half a surrogate pair in a long name does not become a question mark`() {
        val input = "\ud800" + "a".repeat(240)
        val name = FileNames.buildValidFilename(input)
        name shouldBe DiskUtil.buildValidFilename(input)
        (Problem.RESERVED_CHARACTER in PortableName.problems(name)) shouldBe false
    }
}
