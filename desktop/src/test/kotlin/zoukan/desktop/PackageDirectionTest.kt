package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.manga.CustomCovers
import java.io.File

/**
 * Runtime code does not depend on the UI package: only the entry points that start the UI
 * (main, the release self-test, the compatibility harness) may reach into it.
 */
class PackageDirectionTest {
    @Test
    fun `runtime classes do not reference the ui package`() {
        val root = File(ZoukanRuntime::class.java.protectionDomain.codeSource.location.toURI())
        val classes = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        classes.size shouldBeGreaterThan 500
        val ui = "zoukan/desktop/ui/".toByteArray(Charsets.US_ASCII)
        val offenders = classes
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith("zoukan/desktop/") && !it.startsWith("zoukan/desktop/ui/") }
            .filterNot { path -> ALLOWED.any { path.startsWith(it) } }
            .filter { path -> File(root, path).readBytes().indexOf(ui) >= 0 }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `a custom cover keeps its file name`(@TempDir dir: File) {
        CustomCovers.coverDir = File(dir, "covers")
        CustomCovers.file(42).relativeTo(dir).invariantSeparatorsPath shouldBe "covers/custom/a1d0c6e83f027327d8461063f4ac58a6"
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1

    private companion object {
        /** Entry points that start or drive the UI. */
        val ALLOWED = listOf("zoukan/desktop/MainKt", "zoukan/desktop/app/SelfTest", "zoukan/desktop/harness/")
    }
}
