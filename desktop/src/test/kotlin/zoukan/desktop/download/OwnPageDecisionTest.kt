package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * [ChapterDownloader.isOwnPage]'s decision with the attributes Windows reports, which cannot be
 * made on other systems: a file tagged with a reparse point (a cloud-sync client's, a deduplicated
 * or compressed file) is "other", not a regular file, yet still the app's own page.
 */
class OwnPageDecisionTest {

    private val page: Path = Paths.get("Chapter 1_tmp", "002.png")

    private enum class Kind { REGULAR, DIRECTORY, SYMBOLIC_LINK, OTHER }

    private fun attrs(kind: Kind) = object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)
        override fun creationTime(): FileTime = FileTime.fromMillis(0)
        override fun isRegularFile() = kind == Kind.REGULAR
        override fun isDirectory() = kind == Kind.DIRECTORY
        override fun isSymbolicLink() = kind == Kind.SYMBOLIC_LINK
        override fun isOther() = kind == Kind.OTHER
        override fun size() = 10L
        override fun fileKey(): Any? = null
    }

    private fun decide(kind: Kind, resolvesToItself: Boolean) =
        ChapterDownloader.isOwnPage(page, { attrs(kind) }, { resolvesToItself })

    @Test
    fun `a file tagged with a reparse point that resolves to itself is a page`() {
        decide(Kind.OTHER, resolvesToItself = true) shouldBe true
    }

    @Test
    fun `a reparse point that resolves elsewhere is not a page`() {
        decide(Kind.OTHER, resolvesToItself = false) shouldBe false
    }

    @Test
    fun `a symbolic link is not a page, even one the real-path test would pass`() {
        decide(Kind.SYMBOLIC_LINK, resolvesToItself = true) shouldBe false
    }

    @Test
    fun `a folder is not a page`() {
        decide(Kind.DIRECTORY, resolvesToItself = true) shouldBe false
    }

    @Test
    fun `a regular file is a page`() {
        decide(Kind.REGULAR, resolvesToItself = false) shouldBe true
    }

    @Test
    fun `a file whose attributes or real path cannot be read is not a page`() {
        ChapterDownloader.isOwnPage(page, { throw IOException("gone") }, { true }) shouldBe false
        ChapterDownloader.isOwnPage(page, { attrs(Kind.OTHER) }, { throw IOException("gone") }) shouldBe false
    }
}
