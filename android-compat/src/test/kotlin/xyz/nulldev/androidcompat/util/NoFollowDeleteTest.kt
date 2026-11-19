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

    @Test
    fun `a very deep tree is deleted without running out of stack`() {
        // As deep as the file system lets a path be, up to a few thousand levels
        val root = File(temp, "deep")
        var dir = root.apply { mkdirs() }
        var depth = 0
        while (depth < 3000) {
            val next = File(dir, "d")
            if (!next.mkdir()) break
            dir = next
            depth++
        }
        assumeTrue(depth >= 300, "the file system does not allow a deep enough tree")
        // A few levels up, where a file name still fits in the longest path: each level is 2
        // characters ("/d") and "/page.png" is 9, so 8 levels leave room whatever the length of the
        // temporary folder's random name (4 levels made the path one character too long when the
        // deepest folder filled the longest path exactly)
        File(generateSequence(dir) { it.parentFile }.drop(8).first(), "page.png").writeBytes(byteArrayOf(1))

        // A small stack, as a deeper tree on a normal stack: one call per level would overflow it
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                NoFollowDelete.deleteTree(root.toPath())
            } catch (e: Throwable) {
                failure = e
            }
        }, "deep-delete", 128L * 1024)
        thread.start()
        thread.join()

        failure shouldBe null
        root.exists() shouldBe false
    }

    @Test
    fun `a deep tree with a link at the bottom loses the link, never what it points to`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        val kept = File(outside, "kept.txt").apply { writeText("kept") }
        val root = File(temp, "tree")
        var dir = root
        repeat(50) { dir = File(dir, "d") }
        dir.mkdirs()
        val link = try {
            java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            null
        }
        assumeTrue(link != null, "symbolic links cannot be made here")

        NoFollowDelete.deleteTree(root.toPath())

        root.exists() shouldBe false
        kept.readText() shouldBe "kept"
    }

    @Test
    fun `a link, a folder and a path that cannot be checked are told apart`() {
        val folder = File(temp, "folder").apply { mkdirs() }
        NoFollowDelete.linkState(folder.toPath()) shouldBe NoFollowDelete.LinkState.NOT_A_LINK
        NoFollowDelete.isLink(folder.toPath()) shouldBe false

        // Its attributes cannot be read: not known to be a link, and still never followed
        val unreadable = File(temp, "absent").toPath()
        NoFollowDelete.linkState(unreadable) shouldBe NoFollowDelete.LinkState.UNKNOWN
        NoFollowDelete.isLink(unreadable) shouldBe true

        val link = try {
            java.nio.file.Files.createSymbolicLink(File(temp, "link").toPath(), folder.toPath())
        } catch (e: Exception) {
            null
        }
        assumeTrue(link != null, "symbolic links cannot be made here")
        NoFollowDelete.linkState(link!!) shouldBe NoFollowDelete.LinkState.LINK
        NoFollowDelete.isLink(link) shouldBe true
    }

    @Test
    fun `an ordinary folder reached by a name in another letter case is no link on a file system that ignores case`() {
        val onDisk = File(temp, "Entry/Chapter 1").apply { mkdirs() }
        val page = File(onDisk, "001.png").apply { writeBytes(byteArrayOf(1)) }
        val asListed = File(temp, "Entry/CHAPTER 1")
        assumeTrue(asListed.isDirectory, "the file system tells names apart by letter case")

        NoFollowDelete.linkState(asListed.toPath()) shouldBe NoFollowDelete.LinkState.NOT_A_LINK
        NoFollowDelete.isLink(asListed.toPath()) shouldBe false
        // And so it is deleted as the folder it is
        NoFollowDelete.deleteTree(asListed.toPath())
        page.exists() shouldBe false
        onDisk.exists() shouldBe false
    }

    private fun run(vararg command: String): Int = ProcessBuilder(*command).redirectErrorStream(true).start().let {
        it.inputStream.readAllBytes()
        it.waitFor()
    }
}
