package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The per-entry Pictures folder keeps the name an older version gave it. */
class PicturesFolderTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `an entry's existing pictures folder under the old name is used again`() {
        val old = File(root, ".hack__Sign").apply { mkdirs() }

        PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe old
    }

    @Test
    fun `a new entry's pictures folder gets the portable name`() {
        PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe File(root, "hack__Sign")
        // Where both spellings agree, nothing changes
        PageImages.entryPicturesFolder(root, "Plain title") shouldBe File(root, "Plain title")
    }

    @Test
    fun `a file or a link under the old name is not taken for the folder`() {
        File(root, ".hack__Sign").writeText("not a folder")
        PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe File(root, "hack__Sign")
    }
}
