package zoukan.desktop.compat

import androidx.core.util.AtomicFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class AtomicFileTest {
    @TempDir
    lateinit var dir: Path

    private val file: File get() = dir.resolve("table").toFile()

    private fun AtomicFile.write(text: String) {
        val stream = startWrite()
        stream.write(text.toByteArray())
        finishWrite(stream)
    }

    private fun AtomicFile.read(): String = openRead().use { String(it.readBytes()) }

    @Test
    fun `a write replaces the old content`() {
        val atomic = AtomicFile(file)
        atomic.write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a failed write keeps the old content`() {
        val atomic = AtomicFile(file)
        atomic.write("old")
        val stream = atomic.startWrite()
        stream.write("half".toByteArray())
        atomic.failWrite(stream)
        atomic.read() shouldBe "old"
        File(file.path + ".new").exists() shouldBe false
    }

    @Test
    fun `a stale new file does not change what is read`() {
        AtomicFile(file).write("old")
        File(file.path + ".new").writeText("junk")
        AtomicFile(file).read() shouldBe "old"
    }

    @Test
    fun `a rename refused once is retried and the new content lands`() {
        var calls = 0
        val atomic = AtomicFile(file) { from, to ->
            if (calls++ == 0) throw AccessDeniedException(to.toString())
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE)
        }
        AtomicFile(file).write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a rename refused once because the file is in use is retried`() {
        // On Windows a sharing violation is a plain FileSystemException, not AccessDeniedException
        var calls = 0
        val atomic = AtomicFile(file) { from, to ->
            if (calls++ == 0) {
                throw FileSystemException(from.toString(), to.toString(), "being used by another process")
            }
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE)
        }
        AtomicFile(file).write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a rename refused every time keeps the old content and reports it`() {
        val atomic = AtomicFile(file) { _, to -> throw AccessDeniedException(to.toString()) }
        AtomicFile(file).write("old")
        val stream = atomic.startWrite()
        stream.write("new".toByteArray())
        shouldThrow<AccessDeniedException> { atomic.finishWrite(stream) }
        atomic.failWrite(stream)
        atomic.read() shouldBe "old"
    }
}
