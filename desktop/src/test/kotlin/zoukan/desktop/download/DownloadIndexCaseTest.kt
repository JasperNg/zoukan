package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.text.Normalizer

/**
 * The name keys, the matching and the deletes, run as on a disk that ignores case (macOS,
 * Windows) and as on one that does not (most Linux disks), whatever disk the tests run on, so
 * the case-insensitive path runs on Linux CI too.
 */
class DownloadIndexCaseTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    @AfterEach
    fun waitForSaves() = kits.forEach { it.awaitBackground() }

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `a key is the NFC name, lowercased only where case is ignored`(ignoreCase: Boolean) {
        val index = DownloadIndex(ignoreCase = ignoreCase)
        val nfd = Normalizer.normalize("Café 1", Normalizer.Form.NFD)

        index.key(temp, nfd) shouldBe (if (ignoreCase) "café 1" else Normalizer.normalize("Café 1", Normalizer.Form.NFC))
        (index.key(temp, "Chapter 1") == index.key(temp, "chapter 1")) shouldBe ignoreCase
    }

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `matching finds a name in another case only where case is ignored, under its name on disk`(ignoreCase: Boolean) {
        val index = DownloadIndex(ignoreCase = ignoreCase)
        val dir = File(temp, "dir").apply { mkdirs() }
        File(dir, "chapter 1.cbz").writeText("x")
        File(dir, "Chapter 10.cbz").writeText("x")

        index.matching(temp, dir, listOf("Chapter 1.cbz")) shouldBe (if (ignoreCase) listOf("chapter 1.cbz") else emptyList())
        index.matching(temp, dir, listOf("Chapter 10.cbz")) shouldBe listOf("Chapter 10.cbz")
    }

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `a delete finds the chapter in another case only where case is ignored, and nothing else goes`(ignoreCase: Boolean) = runBlocking<Unit> {
        val kit = DownloadKit(temp, ignoreCase = ignoreCase).also { kits += it }
        val entry = kit.manga(1, "Entry")
        // The entry folder and the chapter in another case, as copied from a disk that ignores case
        val other = kit.write("A (EN)/entry/chapter 1.cbz")
        val neighbour = kit.write("A (EN)/entry/Chapter 10.cbz")

        kit.manager.delete(entry, listOf(kit.chapter(1, 1)))

        // Where case is ignored, the same chapter; where not, another file that is never touched
        other.exists() shouldBe !ignoreCase
        neighbour.exists() shouldBe true
        if (ignoreCase) {
            kit.manager.downloadCount(entry) shouldBe 1
            kit.manager.findChapter(entry, kit.chapter(10, 1))!!.parentFile.name shouldBe "entry"
        }
    }
}
