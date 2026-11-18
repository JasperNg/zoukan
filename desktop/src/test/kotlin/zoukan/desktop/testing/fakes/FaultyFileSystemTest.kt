package zoukan.desktop.testing.fakes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okio.Buffer
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.fakes.FaultyFileSystem.Op
import java.io.File

class FaultyFileSystemTest {
    @TempDir
    lateinit var dir: File

    private val fs = FaultyFileSystem()

    private fun path(name: String): Path = File(dir, name).toOkioPath()

    /** One `write` call per chunk, unbuffered, so a failed chunk is not written again at close. */
    private fun FaultyFileSystem.writeChunks(file: Path, vararg chunks: String) {
        sink(file).use { sink ->
            chunks.forEach { chunk -> Buffer().writeUtf8(chunk).let { sink.write(it, it.size) } }
        }
    }

    @Test
    fun `the n-th write fails once, the writes before it landed and the next ones go through`() {
        fs.failOn(Op.WRITE, nth = 2)
        val file = path("a.txt")
        shouldThrow<IOException> { fs.writeChunks(file, "one", "two", "three") }.message shouldContain "write"
        File(dir, "a.txt").readText() shouldBe "one"
        fs.writeChunks(file, "one", "two", "three")
        File(dir, "a.txt").readText() shouldBe "onetwothree"
        fs.calls(Op.WRITE) shouldBe 5
    }

    @Test
    fun `a full disk writes what fits, then fails with the OS message, and heals`() {
        fs.full(freeBytes = 4)
        val file = path("b.txt")
        val error = shouldThrow<IOException> { fs.writeChunks(file, "0123456789") }
        error.message shouldBe FaultyFileSystem.DISK_FULL
        File(dir, "b.txt").readText() shouldBe "0123"
        // Still full
        shouldThrow<IOException> { fs.writeChunks(path("c.txt"), "x") }

        fs.heal()
        fs.writeChunks(file, "0123456789")
        File(dir, "b.txt").readText() shouldBe "0123456789"
    }

    @Test
    fun `a read-only disk refuses every change and still reads`() {
        val existing = File(dir, "kept.txt").apply { writeText("kept") }
        val folder = File(dir, "folder").apply { mkdirs() }
        fs.readOnly()

        shouldThrow<IOException> { fs.writeChunks(path("new.txt"), "x") }.message shouldContain "Read-only"
        shouldThrow<IOException> { fs.appendingSink(existing.toOkioPath()) }
        shouldThrow<IOException> { fs.delete(existing.toOkioPath()) }
        shouldThrow<IOException> { fs.atomicMove(existing.toOkioPath(), path("moved.txt")) }
        shouldThrow<IOException> { fs.createDirectory(path("sub")) }
        fs.read(existing.toOkioPath()) { readUtf8() } shouldBe "kept"
        dir.list()!!.sorted() shouldBe listOf("folder", "kept.txt")
        folder.list()!!.toList() shouldBe emptyList()

        fs.heal()
        fs.atomicMove(existing.toOkioPath(), path("moved.txt"))
        File(dir, "moved.txt").readText() shouldBe "kept"
    }

    @Test
    fun `a move fails as often as planned, then works`() {
        val source = File(dir, "s").apply { writeText("s") }
        fs.failOn(Op.MOVE, times = 2) { IOException("The file is being used by another process") }
        repeat(2) { shouldThrow<IOException> { fs.atomicMove(source.toOkioPath(), path("t")) } }
        fs.atomicMove(source.toOkioPath(), path("t"))
        File(dir, "t").readText() shouldBe "s"
    }
}
