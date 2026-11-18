package zoukan.desktop.property

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.SAXException
import zoukan.desktop.download.ComicInfoXml
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A ComicInfo.xml comes from any CBZ or folder the user opens, so it must not make the parser read
 * a file or open a connection (XML external entities), or expand entities without bound. Every
 * DOCTYPE is refused (ComicInfoXml.read sets disallow-doctype-decl).
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class ComicInfoEntityTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `an external entity naming a local file is refused and the file is not read into a value`() {
        val secret = File(dir, "secret.txt").apply { writeText("SECRET-MARKER") }
        val xml = """<?xml version="1.0"?><!DOCTYPE ComicInfo [<!ENTITY x SYSTEM "${secret.toURI()}">]><ComicInfo><Series>&x;</Series></ComicInfo>"""
        val outcome = runCatching { ComicInfoXml.read(xml.byteInputStream()) }
        (outcome.exceptionOrNull() is SAXException) shouldBe true
        outcome.getOrNull()?.values?.values?.any { "SECRET-MARKER" in it } shouldBe null
    }

    @Test
    fun `an external DTD, entity or parameter entity opens no connection`() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 200
            val connected = AtomicBoolean(false)
            val running = AtomicBoolean(true)
            val listener = thread(isDaemon = true) {
                while (running.get()) {
                    try {
                        server.accept().use { connected.set(true) }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: java.io.IOException) {
                        break
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/x"
            val documents = listOf(
                """<!DOCTYPE ComicInfo SYSTEM "$url.dtd"><ComicInfo><Series>s</Series></ComicInfo>""",
                """<!DOCTYPE ComicInfo [<!ENTITY x SYSTEM "$url.txt">]><ComicInfo><Series>&x;</Series></ComicInfo>""",
                """<!DOCTYPE ComicInfo [<!ENTITY % p SYSTEM "$url.ent"> %p;]><ComicInfo/>""",
                """<ComicInfo xmlns:xi="http://www.w3.org/2001/XInclude"><Series><xi:include href="$url.xml"/></Series></ComicInfo>""",
                """<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="urn:x $url.xsd"><Series>s</Series></ComicInfo>""",
            )
            documents.forEach { xml -> runCatching { ComicInfoXml.read(xml.byteInputStream()) } }
            running.set(false)
            listener.join(2_000)
            // A connection the listener did not take is still waiting in the backlog
            try {
                server.accept().close()
                connected.set(true)
            } catch (_: SocketTimeoutException) {
            }
            connected.get() shouldBe false
        }
    }

    @Test
    fun `nested entities (a billion laughs) are refused at once`() {
        val entities = (1..9).joinToString("") { "<!ENTITY l$it \"" + "&l${it - 1};".repeat(10) + "\">" }
        val xml = """<!DOCTYPE ComicInfo [<!ENTITY l0 "lol">$entities]><ComicInfo><Series>&l9;</Series></ComicInfo>"""
        val start = System.nanoTime()
        shouldThrow<SAXException> { ComicInfoXml.read(xml.byteInputStream()) }
        ((System.nanoTime() - start) / 1_000_000 < 2_000) shouldBe true
    }
}
