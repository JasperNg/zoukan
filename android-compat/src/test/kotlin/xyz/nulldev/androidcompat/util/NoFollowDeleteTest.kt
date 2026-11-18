package xyz.nulldev.androidcompat.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Whether a folder's real path is where it is listed (NoFollowDelete.samePath). A case-only
 * difference is the same folder only when the parent does not list the exact name too: on Windows
 * a folder set case-sensitive can hold `foo` and a junction `Foo` that leads to it, and a tree
 * delete through the junction would empty `foo`.
 */
class NoFollowDeleteTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `identical text is the same folder`() {
        NoFollowDelete.samePath("/a/foo", "/a/foo", sameParent = true) { true } shouldBe true
    }

    @Test
    fun `a case-only difference where the parent lists both names is a link`() {
        NoFollowDelete.samePath("/a/foo", "/a/Foo", sameParent = true) { true } shouldBe false
    }

    @Test
    fun `a case-only difference where the parent lists only the real name is the same folder`() {
        NoFollowDelete.samePath("/a/foo", "/a/Foo", sameParent = true) { false } shouldBe true
    }

    @Test
    fun `any other difference is a link`() {
        NoFollowDelete.samePath("/a/bar", "/a/Foo", sameParent = true) { false } shouldBe false
        NoFollowDelete.samePath("/b/foo", "/a/Foo", sameParent = false) { false } shouldBe false
    }

    @Test
    fun `a junction to a sibling named in another case is a link on a case-sensitive Windows folder`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "junctions are Windows only")
        val dir = File(temp, "cs").apply { mkdirs() }
        assumeTrue(run("fsutil", "file", "setCaseSensitiveInfo", dir.absolutePath, "enable") == 0, "cannot set the folder case-sensitive")
        val real = File(dir, "foo").apply { mkdirs() }
        val page = File(real, "001.png").apply { writeBytes(byteArrayOf(1)) }
        val junction = File(dir, "Foo")
        assumeTrue(run("cmd", "/c", "mklink", "/J", junction.absolutePath, real.absolutePath) == 0 && junction.exists(), "mklink /J failed")

        NoFollowDelete.isLink(junction.toPath()) shouldBe true
        NoFollowDelete.isLink(real.toPath()) shouldBe false

        NoFollowDelete.deleteTree(junction.toPath())
        page.exists() shouldBe true
    }

    private fun run(vararg command: String): Int = ProcessBuilder(*command).redirectErrorStream(true).start().let {
        it.inputStream.readAllBytes()
        it.waitFor()
    }
}
