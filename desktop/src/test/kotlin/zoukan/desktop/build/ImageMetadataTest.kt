package zoukan.desktop.build

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Images the project ships (app icons, readme images) carry no embedded provenance manifests
 * (C2PA content credentials): they would travel into every installer and say more about how a
 * file was made than the project means to publish.
 */
class ImageMetadataTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".github/workflows").isDirectory }

    private val images = setOf("png", "svg", "ico", "icns", "jpg", "jpeg", "webp", "gif")

    private fun tracked(): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        withClue("git ls-files failed: $out") { process.waitFor() shouldBe 0 }
        return out.split('\u0000').filter(String::isNotEmpty)
    }

    @Test
    fun `no shipped image carries a C2PA manifest`() {
        val files = tracked()
            .filter { it.substringAfterLast('.').lowercase() in images }
            .filterNot { it.contains("/src/test") }
            .map { File(root, it) }
            .filter(File::isFile)
        withClue("found no images under $root") { files.shouldNotBeEmpty() }
        // PNG keeps the manifest in a caBX chunk, JPEG and WebP in JUMBF boxes, SVG in c2pa: metadata
        val markers = listOf("caBX", "c2pa", "jumb").map { it.toByteArray() }
        val hits = files.filter { file ->
            val bytes = file.readBytes()
            markers.any { marker -> bytes.indexOf(marker) >= 0 }
        }.map { it.relativeTo(root).path }
        hits.shouldBeEmpty()
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
