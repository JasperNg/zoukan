package zoukan.desktop.property

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.download.FileNames
import zoukan.desktop.property.PortableName.Problem

/**
 * Download folder and file names, for any title, chapter name, scanlator and chapter URL:
 * - the desktop builds the name the Android app builds ([DiskUtil], [DownloadProvider] are
 *   Android's code), so a downloads folder works in both apps;
 * - the name is one valid file name on Windows, macOS and Linux, inside its folder;
 * - the same input always gives the same name, and a name that is already valid is kept as it is,
 *   so titles that differ only by case or Unicode normalisation stay apart, as on Android.
 *
 * The desktop has its own copy of DiskUtil.buildValidFilename ([FileNames]), because DiskUtil is
 * Android-only; DownloadPaths builds the chapter name with it as DownloadProvider does.
 */
class DownloadNamePropertyTest {

    private val strings = arbFrom { NastyStrings.next(it) }

    private data class ChapterInput(val name: String, val scanlator: String?, val url: String, val ascii: Boolean, val hash: Boolean)

    private val chapters = arbFrom { r ->
        ChapterInput(
            name = NastyStrings.next(r),
            scanlator = if (r.nextInt(3) == 0) null else NastyStrings.next(r, maxPieces = 4),
            url = NastyStrings.next(r, maxPieces = 4, padding = false),
            ascii = r.nextBoolean(),
            hash = r.nextBoolean(),
        )
    }

    @Test
    fun `entry and source folder names are Android's`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                FileNames.buildValidFilename(title, disallowNonAscii = ascii) shouldBe DiskUtil.buildValidFilename(title, disallowNonAscii = ascii)
                // The chapter name's shorter limit
                FileNames.buildValidFilename(title, 229, ascii) shouldBe DiskUtil.buildValidFilename(title, 229, ascii)
            }
        }
    }

    @Test
    fun `truncation is Android's for any limit`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { r -> NastyStrings.next(r) to r.nextInt(0, 300) }) { (s, max) ->
            FileNames.truncateToLength(s, max) shouldBe DiskUtil.truncateToLength(s, max)
        }
    }

    @Test
    fun `chapter folder names are Android's, also the older names looked up`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), chapters) { c ->
            DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash) shouldBe
                DownloadProvider.getChapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)

            // Every name Android looks a chapter up by is looked up on desktop too, the current one first
            val desktop = paths(c.ascii, c.hash).validChapterNames(chapter(c))
            val android = DownloadProvider.getValidChapterDirNames(c.name, c.scanlator, c.url, c.ascii, c.hash)
            desktop.take(2) shouldBe android.take(2)
            desktop shouldContainAll android
        }
    }

    @Test
    fun `entry folder names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                val name = FileNames.buildValidFilename(title, disallowNonAscii = ascii)
                PortableName.problems(name).forEach { found.add(it, title, "\"${escaped(name)}\" (ascii=$ascii)", isKnownDefect(it, title, name, wasCut(title, FileNames.MAX_FILE_NAME_BYTES, ascii))) }
                // Stable: the same title always gives the same name
                FileNames.buildValidFilename(title, disallowNonAscii = ascii) shouldBe name
            }
        }
        assertOnlyKnown(found)
    }

    @Test
    fun `chapter folder and CBZ names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), chapters) { c ->
            val name = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            for (file in listOf(name, "$name.cbz", name + DownloadPaths.TMP_SUFFIX)) {
                val input = (if (c.scanlator.isNullOrBlank()) "" else c.scanlator + "_") + c.name.ifBlank { "Chapter" }
                val cut = wasCut(input, FileNames.MAX_FILE_NAME_BYTES - 11, c.ascii)
                PortableName.problems(file).forEach { found.add(it, input, "\"${escaped(file)}\" (ascii=${c.ascii}, hash=${c.hash})", isKnownDefect(it, input, file, cut)) }
            }
            DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash) shouldBe name
        }
        assertOnlyKnown(found)
    }

    @Test
    fun `a name that is already valid is kept as it is`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            val untouched = title.isNotEmpty() &&
                title.trim('.', ' ') == title &&
                title.none { it.code < 0x20 || it.code == 0x7f || it in "<>:\"/\\|?*" } &&
                title.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_FILE_NAME_BYTES &&
                !PortableName.hasLoneSurrogate(title)
            if (untouched) FileNames.buildValidFilename(title) shouldBe title
        }
    }

    private class Found {
        val unknown = Counterexamples<Problem>()
        val known = Counterexamples<Problem>()

        fun add(problem: Problem, input: String, detail: String, isKnown: Boolean) = (if (isKnown) known else unknown).add(problem, input, detail)
    }

    /**
     * Whether [problem] in [output], the name made from [input], is one of the known defects, each
     * with a disabled test with its smallest input in [DownloadNameKnownDefectsTest]. Only the exact
     * shape each defect produces is left out, so the property still fails on every other problem,
     * also on an input that has the defect's trigger in it.
     */
    private fun isKnownDefect(problem: Problem, input: String, output: String, cut: Boolean): Boolean = when (problem) {
        // `a Windows device name is not a folder name`
        Problem.WINDOWS_DEVICE_NAME -> true
        // `a name cut at the limit does not end in a space or a dot`: only a name that was cut
        Problem.TRAILING_DOT_OR_SPACE -> cut
        // `half a surrogate pair does not reach the name`: only the input's own halves, kept
        Problem.LONE_SURROGATE -> loneSurrogates(output) in 1..loneSurrogates(input)
        // `half a surrogate pair in a long name does not become a question mark`: only in a name that
        // was cut (the cut encodes to UTF-8, which writes a half pair as `?`), the only reserved
        // character is `?`, and there is at most one for each half pair of the input
        Problem.RESERVED_CHARACTER ->
            cut &&
                output.filter { it in "<>:\"/\\|?*" }.all { it == '?' } &&
                output.count { it == '?' } <= loneSurrogates(input)
        else -> false
    }

    /** How many halves of a surrogate pair [s] has on their own. */
    private fun loneSurrogates(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                i += 2
            } else {
                if (s[i].isSurrogate()) n++
                i++
            }
        }
        return n
    }

    /** Whether the name of [input] was cut to [maxBytes]: its full name is longer. */
    private fun wasCut(input: String, maxBytes: Int, ascii: Boolean) =
        FileNames.buildValidFilename(input, Int.MAX_VALUE, ascii).toByteArray(Charsets.UTF_8).size > maxBytes

    private fun assertOnlyKnown(found: Found) {
        if (found.known.kinds().isNotEmpty()) println("Known name defects seen:\n" + found.known.report())
        check(found.unknown.kinds().isEmpty()) { "Invalid names (seed ${PropertyRuns.seed}):\n" + found.unknown.report() }
    }

    private fun chapter(c: ChapterInput) = Chapter.create().copy(name = c.name, scanlator = c.scanlator, url = c.url)

    private fun paths(ascii: Boolean, hash: Boolean): DownloadPaths {
        val library = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames().get() } returns ascii }
        val download = mockk<DownloadPreferences> { every { includeChapterUrlHash().get() } returns hash }
        return DownloadPaths(mockk(relaxed = true), mockk(relaxed = true), library, download)
    }

    companion object {
        private const val ITERATIONS = 500
    }
}
