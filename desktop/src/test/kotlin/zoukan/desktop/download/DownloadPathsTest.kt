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
    fun `a computed name that opens a folder no listed name matches is not taken for it`() {
        // On Windows "SOUSOU~1" is the short name of another entry's folder: it exists and is the
        // same file, but it is not that folder's spelling, and that folder is not this entry's
        val listed = setOf("Sousou no Frieren")
        DownloadPaths.spellingOnDisk("SOUSOU~1", listed) { true } shouldBe null
        // The same when the file system cannot say whether the two are one
        DownloadPaths.spellingOnDisk("Sousou No Frieren", listed) { false } shouldBe null
        // A listed name that differs in letter case or Unicode form, and is the same folder, is its spelling
        DownloadPaths.spellingOnDisk("Sousou No Frieren", listed) { it == "Sousou no Frieren" } shouldBe "Sousou no Frieren"
        val nfd = java.text.Normalizer.normalize("Café", java.text.Normalizer.Form.NFD)
        DownloadPaths.spellingOnDisk("Café", setOf(nfd)) { true } shouldBe nfd
    }

    @Test
    fun `natural order`() {
        listOf("10.jpg", "2.jpg", "001.jpg", "B.png", "a.png").sortedWith(FileNames.naturalOrder) shouldBe
            listOf("001.jpg", "2.jpg", "10.jpg", "a.png", "B.png")
    }
}
