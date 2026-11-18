package zoukan.desktop.e2e

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The failures that change a path's own answer ([FixtureFailure.Drop], [FixtureFailure.Truncate],
 * [FixtureFailure.Stall], [FixtureFailure.Corrupt], [FixtureFailure.WrongType],
 * [FixtureFailure.Oversize], [FixtureFailure.Answer]), as the app's HTTP client (OkHttp) sees them.
 */
class FixtureServerFaultsTest {
    private val server = FixtureServer()
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private val imagePath = "/images/" + FixtureCatalog.pages("png").first().file

    @AfterEach
    fun close() {
        server.close()
    }

    private fun call(path: String, client: OkHttpClient = this.client): Response =
        client.newCall(Request.Builder().url(server.baseUrl + path).build()).execute()

    private fun body(path: String): ByteArray = call(path).use { it.body.bytes() }

    @Test
    fun `a drop closes the connection without an answer`() {
        server.fail(imagePath, FixtureFailure.Drop)
        shouldThrow<IOException> { call(imagePath).close() }
        server.requests.count { it == imagePath } shouldBe 1
    }

    @Test
    fun `a truncated answer declares its full length, then the connection is cut mid-body`() {
        val whole = body(imagePath)
        server.fail(imagePath, FixtureFailure.Truncate(keepBytes = 100))
        call(imagePath).use { response ->
            response.code shouldBe 200
            response.body.contentLength() shouldBe whole.size.toLong()
            val received = java.io.ByteArrayOutputStream()
            val error = shouldThrow<IOException> { response.body.byteStream().copyTo(received) }
            // The connection is closed, not left open until the client's read timeout
            (error is SocketTimeoutException) shouldBe false
            received.size() shouldBeLessThan whole.size
        }
        // Healed: the whole image again
        server.clearFailures()
        body(imagePath) shouldBe whole
    }

    @Test
    fun `a truncate applies to the extra routes too, and only as often as asked`() {
        server.extraRoutes += { exchange, parts ->
            if (parts == listOf("blob")) {
                val bytes = ByteArray(5000) { 7 }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                true
            } else {
                false
            }
        }
        server.fail("/blob", FixtureFailure.Truncate(10), times = 1)
        shouldThrow<IOException> { body("/blob") }
        body("/blob").size shouldBe 5000
    }

    @Test
    fun `a stall holds the rest of the body until a read timeout, and a clear releases it`() {
        server.fail(imagePath, FixtureFailure.Stall(afterBytes = 50))
        val impatient = client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        call(imagePath, impatient).use { response ->
            response.code shouldBe 200
            shouldThrow<SocketTimeoutException> { response.body.bytes() }
        }
        server.clearFailures()
        body(imagePath).size shouldNotBe 0
    }

    @Test
    fun `a corrupt answer keeps its length and the bytes before the corruption`() {
        val whole = body(imagePath)
        server.fail(imagePath, FixtureFailure.Corrupt(fromByte = 16))
        val corrupt = body(imagePath)
        corrupt.size shouldBe whole.size
        corrupt.copyOfRange(0, 16) shouldBe whole.copyOfRange(0, 16)
        corrupt.copyOfRange(16, corrupt.size).zip(whole.copyOfRange(16, whole.size).toList()).all { (a, b) -> a != b } shouldBe true
    }

    @Test
    fun `a wrong type keeps the body and changes only the content type`() {
        val whole = body("/catalog")
        server.fail("/catalog", FixtureFailure.WrongType("text/html"))
        call("/catalog").use { response ->
            response.header("Content-Type") shouldBe "text/html"
            response.body.bytes() shouldBe whole
        }
    }

    @Test
    fun `an oversize answer sends more than the path's body, with no length declared`() {
        val whole = body(imagePath)
        server.fail(imagePath, FixtureFailure.Oversize(extraBytes = 1000))
        call(imagePath).use { response ->
            response.header("Content-Length") shouldBe null
            val bytes = response.body.bytes()
            bytes.size shouldBe whole.size + 1000
            bytes.copyOfRange(0, whole.size) shouldBe whole
        }
    }

    @Test
    fun `an answer replaces the path's answer with any status, type and bytes`() {
        server.fail("/catalog", FixtureFailure.Answer(502, "text/html", "<html>Bad gateway</html>".toByteArray()))
        call("/catalog").use { response ->
            response.code shouldBe 502
            response.header("Content-Type") shouldBe "text/html"
            response.body.string() shouldBe "<html>Bad gateway</html>"
        }
    }
}
