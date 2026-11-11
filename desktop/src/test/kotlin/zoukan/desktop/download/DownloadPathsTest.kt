package zoukan.desktop.download

import eu.kanade.tachiyomi.util.lang.Hash
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Names must match the Android app's DownloadProvider, so a downloads folder works on both. */
class DownloadPathsTest {

    private fun name(name: String, scanlator: String? = null, hash: Boolean = false, ascii: Boolean = false) =
        DownloadPaths.chapterDirName(name, scanlator, "/chapter/1", disallowNonAscii = ascii, includeHash = hash)

    @Test
    fun `chapter names as on Android`() {
        name("Chapter 1") shouldBe "Chapter 1"
        name("Chapter 1", scanlator = "Group") shouldBe "Group_Chapter 1"
        name("Chapter 1", scanlator = " ") shouldBe "Chapter 1"
        name("") shouldBe "Chapter"
        name("Vol. 1: Ch. 2? <End>") shouldBe "Vol. 1_ Ch. 2_ _End_"
        name("...Hidden.") shouldBe "Hidden"
        name("Chapter 1", hash = true) shouldBe "Chapter 1_" + Hash.md5("/chapter/1").take(6)
    }

    @Test
    fun `non-ASCII names`() {
        name("第1話") shouldBe "第1話"
        name("第1話", ascii = true) shouldBe "e7acac1e8a9b1"
    }

    @Test
    fun `long names are cut at 229 bytes without splitting a character`() {
        val long = "あ".repeat(200)
        val result = name(long)
        (result.toByteArray().size <= FileNames.MAX_FILE_NAME_BYTES - 11) shouldBe true
        result.all { it == 'あ' } shouldBe true
    }

    @Test
    fun `natural order`() {
        listOf("10.jpg", "2.jpg", "001.jpg", "B.png", "a.png").sortedWith(FileNames.naturalOrder) shouldBe
            listOf("001.jpg", "2.jpg", "10.jpg", "a.png", "B.png")
    }
}
