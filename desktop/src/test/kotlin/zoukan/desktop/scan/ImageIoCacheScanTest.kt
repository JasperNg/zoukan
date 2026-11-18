package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * ImageIO's `read(InputStream)`, `read(URL)`, `write(..., OutputStream)` and its
 * `createImageInputStream` / `createImageOutputStream` on a stream copy the image to a temporary
 * file in the system's temporary folder while it is read or written (its file cache), so a
 * decrypted page or cover would land there in plain. Fails on these in the app's own code:
 *
 * - `ImageIO.read(...)` unless its argument is a `MemoryCacheImageInputStream(...)`;
 * - `ImageIO.write(...)` and `ImageIO.createImageInputStream(...)` / `createImageOutputStream(...)`.
 *
 * Read and write images held in memory with `MemoryImageIo` (desktop) or a
 * `MemoryCacheImageInputStream` / `MemoryCacheImageOutputStream` (android-compat). A `File`
 * argument is opened directly, with no cache file; such a site needs an entry with its reason.
 * Every command also turns ImageIO's file cache off at start (`runCommand`); this rule does not
 * rely on that, which a test or a caller outside `runCommand` would not have.
 */
class ImageIoCacheScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    @Test
    fun `images in memory are read and written through streams cached in memory`() {
        val findings = SourceScan.sources(sourceDirs).flatMap { source ->
            ImageIoCacheScanner.lines(source.text).filter { source.isOwn(it) }.map { SourceScan.finding(source, it) }
        }
        SourceScan.verify(
            findings,
            ALLOWED,
            "Read and write images in memory with MemoryImageIo or a MemoryCacheImageInputStream / " +
                "MemoryCacheImageOutputStream (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `flags ImageIO's file-cached reads and writes, and accepts memory-cached ones`() {
        ImageIoCacheScanner.lines(
            """
            val a = ImageIO.read(ByteArrayInputStream(bytes))
            val b = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))
            ImageIO.write(image, "png", out)
            ImageIO.createImageInputStream(input)
            ImageIO.createImageOutputStream(output)
            val c = ImageIO.read(javax.imageio.stream.MemoryCacheImageInputStream(it))
            val d = ImageIO.read(/* ZOUKAN --> */javax.imageio.stream.MemoryCacheImageInputStream(it)/* ZOUKAN <-- */)
            // ImageIO.read(stream) in a comment
            val e = "ImageIO.write(image) in a string"
            val f = javax.imageio.ImageIO.read(url)
            """.trimIndent(),
        ) shouldBe listOf(1, 3, 4, 5, 10)
    }

    private companion object {
        private const val MAIN = "desktop/src/main/kotlin/zoukan/desktop"

        val ALLOWED = listOf(
            Allowed(
                "$MAIN/image/MemoryImageIo.kt",
                "ImageIO.write(image, \"png\", stream)",
                "the helper itself: writes to a MemoryCacheImageOutputStream",
                function = "png",
            ),
            Allowed(
                "$MAIN/download/TallImageSplitter.kt",
                "ImageIO.createImageInputStream(image)",
                "a File, which ImageIO opens directly (FileImageInputStream), with no cache file: the page is " +
                    "already a file in the download folder",
                function = "splitIfTall",
            ),
            Allowed(
                "$MAIN/download/TallImageSplitter.kt",
                "ImageIO.createImageOutputStream(file)",
                "a File, which ImageIO opens directly (FileImageOutputStream), with no cache file: the part is " +
                    "written into the download folder",
                function = "writeJpeg",
            ),
        )
    }
}

/** Finds the ImageIO calls [ImageIoCacheScanTest] forbids; returns their 1-based lines. */
internal object ImageIoCacheScanner {
    private val CALL = Regex(
        """\bImageIO\s*\.\s*(?:write|createImageInputStream|createImageOutputStream)\s*\(|""" +
            """\bImageIO\s*\.\s*read\s*\((?!\s*(?:javax\s*\.\s*imageio\s*\.\s*stream\s*\.\s*)?MemoryCacheImageInputStream\s*\()""",
    )

    fun lines(text: String): List<Int> {
        val source = SourceScan.Source("", text)
        return CALL.findAll(source.masked).map { source.lineOf(it.range.first) }.distinct().toList()
    }
}
