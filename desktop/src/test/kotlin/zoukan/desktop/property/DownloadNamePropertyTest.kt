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
 *   Android's code) whenever Android's name is valid on every OS; otherwise it builds a valid one
 *   and still looks downloads up by Android's name, so a downloads folder works in both apps;
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
    fun `entry and source folder names are Android's, or valid ones that still find Android's`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                // The entry and source name, and the chapter name's shorter limit
                for (limit in listOf(FileNames.MAX_FILE_NAME_BYTES, 229)) {
                    val android = DiskUtil.buildValidFilename(title, limit, ascii)
                    val desktop = FileNames.buildValidFilename(title, limit, ascii)
                    if (isPortable(android)) {
                        desktop shouldBe android
                    } else {
                        PortableName.problems(desktop) shouldBe emptySet()
                    }
                }
                // Android's entry folder is looked up
                val names = paths(ascii, hash = false).mangaDirNames(title)
                names.first() shouldBe FileNames.buildValidFilename(title, disallowNonAscii = ascii)
                names shouldContainAll listOf(DiskUtil.buildValidFilename(title, disallowNonAscii = ascii), DiskUtil.buildValidFilename(title, disallowNonAscii = !ascii))
                    .filter { isPathHere(it) }
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
            val name = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            val androidName = DownloadProvider.getChapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            if (isPortable(androidName)) name shouldBe androidName else PortableName.problems(name) shouldBe emptySet()

            // Every name Android looks a chapter up by is looked up on desktop too, the current one first
            val desktop = paths(c.ascii, c.hash).validChapterNames(chapter(c))
            val android = DownloadProvider.getValidChapterDirNames(c.name, c.scanlator, c.url, c.ascii, c.hash)
            desktop.take(2) shouldBe listOf(name, "$name.cbz")
            desktop shouldContainAll android.filter { isPathHere(it) }
        }
    }

    @Test
    fun `entry folder names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                val name = FileNames.buildValidFilename(title, disallowNonAscii = ascii)
                PortableName.problems(name).forEach { found.add(it, title, "\"${escaped(name)}\" (ascii=$ascii)") }
                // Stable: the same title always gives the same name
                FileNames.buildValidFilename(title, disallowNonAscii = ascii) shouldBe name
            }
        }
        assertAllValid(found)
    }

    @Test
    fun `chapter folder and CBZ names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), chapters) { c ->
            val name = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            for (file in listOf(name, "$name.cbz", name + DownloadPaths.TMP_SUFFIX)) {
                val input = (if (c.scanlator.isNullOrBlank()) "" else c.scanlator + "_") + c.name.ifBlank { "Chapter" }
                PortableName.problems(file).forEach { found.add(it, input, "\"${escaped(file)}\" (ascii=${c.ascii}, hash=${c.hash})") }
            }
            DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash) shouldBe name
        }
        assertAllValid(found)
    }

    @Test
    fun `a name that is already valid is kept as it is`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            val untouched = title.isNotEmpty() &&
                title.trim('.', ' ') == title &&
                title.none { it.code < 0x20 || it.code == 0x7f || it in "<>:\"/\\|?*" } &&
                title.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_FILE_NAME_BYTES &&
                !PortableName.hasLoneSurrogate(title) &&
                !PortableName.isWindowsDeviceName(title)
            if (untouched) FileNames.buildValidFilename(title) shouldBe title
        }
    }

    private class Found {
        val unknown = Counterexamples<Problem>()

        fun add(problem: Problem, input: String, detail: String) = unknown.add(problem, input, detail)
    }

    private fun isPortable(name: String) = PortableName.problems(name).isEmpty()

    /** Whether [name] can be a path on this OS at all: on Windows a name with `?` cannot be on disk, so it is not looked up. */
    private fun isPathHere(name: String) = runCatching { java.nio.file.Path.of(name) }.isSuccess

    private fun assertAllValid(found: Found) {
        check(found.unknown.kinds().isEmpty()) { "Invalid names (seed ${PropertyRuns.seed}):\n" + found.unknown.report() }
    }

    private fun chapter(c: ChapterInput) = Chapter.create().copy(name = c.name, scanlator = c.scanlator, url = c.url)

    /** Made once for each setting: a mock made on every iteration fails now and then under the JIT. */
    private val pathsBySetting = listOf(false, true).flatMap { a -> listOf(false, true).map { h -> (a to h) to newPaths(a, h) } }.toMap()

    private fun paths(ascii: Boolean, hash: Boolean): DownloadPaths = pathsBySetting.getValue(ascii to hash)

    private fun newPaths(ascii: Boolean, hash: Boolean): DownloadPaths {
        val library = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames().get() } returns ascii }
        val download = mockk<DownloadPreferences> { every { includeChapterUrlHash().get() } returns hash }
        return DownloadPaths(mockk(relaxed = true), mockk(relaxed = true), library, download)
    }

    companion object {
        private const val ITERATIONS = 500
    }
}
