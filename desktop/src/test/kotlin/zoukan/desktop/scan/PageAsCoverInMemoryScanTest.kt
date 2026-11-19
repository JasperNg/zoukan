package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * "Set as cover" in the reader passes the page's bytes to EditManga.setCover in memory: the page
 * can come from an encrypted chapter, so it never goes through a temporary file, plain or in the
 * app's own temporary folder. Fails when the path takes a file again.
 */
class PageAsCoverInMemoryScanTest {
    private val sources by lazy { SourceScan.sources(listOf("desktop/src/main/kotlin")) }

    /** The text of the function whose header starts with [signature] in the file ending with [file], up to the next declaration. */
    private fun body(file: String, signature: String): String {
        val text = sources.single { it.path.endsWith(file) }.text
        val start = text.indexOf("fun $signature")
        check(start >= 0) { "$signature not found in $file" }
        val next = Regex("""\n    (?:/\*\*|(?:private |internal |suspend )*fun )""").find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, next)
    }

    private val tempFile = Regex("""createTempFile|createTempDirectory|AppTempFiles|\.writeBytes\(""")

    @Test
    fun `the reader's set as cover passes the page in memory`() {
        val action = body("ui/reader/ReaderScreenModel.kt", "setPageAsCover(")
        action shouldContain "setCover(manga, pageBytes(index))"
        tempFile.containsMatchIn(action) shouldBe false
    }

    @Test
    fun `setting a cover from bytes takes no temporary file`() {
        tempFile.containsMatchIn(body("manga/EditManga.kt", "setCover(manga: Manga, image: ByteArray")) shouldBe false
    }
}
