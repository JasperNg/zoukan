package zoukan.desktop.property

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.download.FileNames
import zoukan.desktop.property.PortableName.Problem

/**
 * Names [DownloadNamePropertyTest] found that the Android app builds but that are not valid on
 * every OS, with the smallest inputs found. The desktop writes a portable name for each, and still
 * looks downloads up by Android's name, so a folder Android (or an older desktop) wrote is found.
 */
class DownloadNameKnownDefectsTest {

    private val paths = paths(ascii = false, hash = false)
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The desktop writes a valid name, and looks the entry up by Android's name too. */
    private fun assertValid(input: String, name: String) {
        PortableName.problems(name) shouldBe emptySet()
        val android = DiskUtil.buildValidFilename(input)
        name shouldNotBe android
        paths.mangaDirNames(input).first() shouldBe name
        // A name this OS cannot have as a folder (`?`, or a device name on Windows) cannot be on disk, so it is not looked up
        if (isPathHere(android)) {
            paths.mangaDirNames(input) shouldContain android
        } else {
            paths.mangaDirNames(input) shouldNotContain android
        }
    }

    /**
     * Whether [name] can be a folder on this OS: [java.nio.file.Path.of] accepts it and, on
     * Windows, it is not a device name (`NUL`, `CON.1`: `Path.of` accepts those too, but they name
     * the device, never a folder, so [DownloadPaths] does not look them up there).
     */
    private fun isPathHere(name: String) =
        runCatching { java.nio.file.Path.of(name) }.isSuccess && !(isWindows && PortableName.isWindowsDeviceName(name))

    @Test
    fun `a Windows device name is not a folder name`() {
        assertValid("nul", FileNames.buildValidFilename("nul"))
        FileNames.buildValidFilename("nul") shouldBe "nul_"
        assertValid("CON.txt", FileNames.buildValidFilename("CON.txt"))
        FileNames.buildValidFilename("CON.txt") shouldBe "CON_.txt"
        // A chapter name with the hash turned off, and CON.<anything> with it on
        PortableName.problems(DownloadPaths.chapterDirName("CON", null, "/c/1", disallowNonAscii = false, includeHash = false)) shouldBe emptySet()
        PortableName.problems(DownloadPaths.chapterDirName("CON.1", null, "/c/1", disallowNonAscii = false, includeHash = true)) shouldBe emptySet()
        // CON with the hash is valid as Android names it (CON_xxxxxx), so it stays Android's
        DownloadPaths.chapterDirName("CON", null, "/c/1", disallowNonAscii = false, includeHash = true) shouldBe
            DownloadProvider.getChapterDirName("CON", null, "/c/1", false, true)
        for (hash in listOf(false, true)) {
            val names = paths(ascii = false, hash = hash).validChapterNames(chapter("CON.1"))
            val android = DownloadProvider.getChapterDirName("CON.1", null, "/c/1", false, hash)
            if (isPathHere(android)) {
                names shouldContain android
                names shouldContain "$android.cbz"
            } else {
                names shouldNotContain android
                names shouldNotContain "$android.cbz"
            }
        }
    }

    @Test
    fun `a name cut at the limit does not end in a space or a dot`() {
        val input = "a".repeat(239) + " b"
        assertValid(input, FileNames.buildValidFilename(input))
        val dot = "a".repeat(239) + ".b"
        assertValid(dot, FileNames.buildValidFilename(dot))
        FileNames.buildValidFilename(dot) shouldBe "a".repeat(239)
    }

    @Test
    fun `half a surrogate pair does not reach the name`() {
        assertValid("\udbff", FileNames.buildValidFilename("\udbff"))
        FileNames.buildValidFilename("a\udbffb") shouldBe "a_b"
        // As an older desktop wrote it on macOS and Linux, where Java writes the half pair as '?'
        if (isPathHere("a?b")) paths.mangaDirNames("a\udbffb") shouldContain "a?b"
    }

    @Test
    fun `half a surrogate pair in a long name does not become a question mark`() {
        val input = "\ud800" + "a".repeat(240)
        val name = FileNames.buildValidFilename(input)
        (Problem.RESERVED_CHARACTER in PortableName.problems(name)) shouldBe false
        assertValid(input, name)
    }

    private fun chapter(name: String) = Chapter.create().copy(name = name, url = "/c/1")

    private fun paths(ascii: Boolean, hash: Boolean): DownloadPaths {
        val library = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames().get() } returns ascii }
        val download = mockk<DownloadPreferences> { every { includeChapterUrlHash().get() } returns hash }
        return DownloadPaths(mockk(relaxed = true), mockk(relaxed = true), library, download)
    }
}
