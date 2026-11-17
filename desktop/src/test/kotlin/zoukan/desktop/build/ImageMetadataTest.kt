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
        val hits = files.filter { hasManifest(it) }.map { it.relativeTo(root).path }
        hits.shouldBeEmpty()
    }

    /** PNG keeps the manifest in a caBX chunk, SVG in c2pa metadata, JPEG and WebP in a JUMBF box labelled c2pa. */
    private fun hasManifest(file: File): Boolean {
        val bytes = file.readBytes()
        return when (file.extension.lowercase()) {
            "png" -> pngChunkTypes(bytes).contains("caBX")
            "svg" -> bytes.toString(Charsets.UTF_8).contains("c2pa", ignoreCase = true)
            // Compressed pixels can hold any 4 bytes; a JUMBF box has both its type and its label
            else -> bytes.indexOf("jumb".toByteArray()) >= 0 && bytes.indexOf("c2pa".toByteArray()) >= 0
        }
    }

    /** The chunk types of a PNG, walked by length so pixel data is never mistaken for a type. */
    private fun pngChunkTypes(bytes: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = ((bytes[i].toInt() and 0xff) shl 24) or ((bytes[i + 1].toInt() and 0xff) shl 16) or
                ((bytes[i + 2].toInt() and 0xff) shl 8) or (bytes[i + 3].toInt() and 0xff)
            types += String(bytes, i + 4, 4, Charsets.US_ASCII)
            if (length < 0) break
            i += 12 + length
        }
        return types
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
