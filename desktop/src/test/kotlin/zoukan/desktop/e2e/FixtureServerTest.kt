package zoukan.desktop.e2e

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO

/**
 * The failures [FixtureServer.fail] puts on paths, which the error-state journeys use. Plain HTTP
 * against the server, no app runtime.
 */
class FixtureServerTest {

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    // Unit, so JUnit runs the expression-bodied tests (it skips test methods that return a value)
    private fun withServer(block: (FixtureServer) -> Unit) = FixtureServer().use(block)

    private fun FixtureServer.get(path: String): HttpResponse<ByteArray> =
        client.send(request(path), HttpResponse.BodyHandlers.ofByteArray())

    private fun FixtureServer.getAsync(path: String): CompletableFuture<HttpResponse<ByteArray>> =
        client.sendAsync(request(path), HttpResponse.BodyHandlers.ofByteArray())

    private fun FixtureServer.request(path: String): HttpRequest =
        HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(Duration.ofSeconds(30)).build()

    private val HttpResponse<ByteArray>.text get() = body().decodeToString()

    @Test
    fun `a status failure answers its code and leaves other paths alone`() = withServer { server ->
        server.fail("/manga/second", FixtureFailure.Status(500))

        val failed = server.get("/manga/second")
        failed.statusCode() shouldBe 500
        failed.text shouldBe "Fixture failure 500"
        server.get("/manga/formats").statusCode() shouldBe 200
    }

    @Test
    fun `a failure with times fails that often, then the path works`() = withServer { server ->
        server.fail("/catalog", FixtureFailure.Status(404), times = 1)

        server.get("/catalog").statusCode() shouldBe 404
        val healed = server.get("/catalog")
        healed.statusCode() shouldBe 200
        healed.text shouldStartWith "["
    }

    @Test
    fun `the first matching rule with times left applies`() = withServer { server ->
        server.fail("/manga", FixtureFailure.Status(500), times = 1)
        server.fail("/manga/second", FixtureFailure.Status(404))

        server.get("/manga/second").statusCode() shouldBe 500
        server.get("/manga/second").statusCode() shouldBe 404
        server.get("/manga/formats").statusCode() shouldBe 200
    }

    @Test
    fun `a slow failure answers normally after its delay`() = withServer { server ->
        val normal = server.get("/manga/formats").text
        server.fail("/manga/formats", FixtureFailure.Slow(300))

        val start = System.nanoTime()
        val slow = server.get("/manga/formats")
        val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        tookMillis shouldBeGreaterThanOrEqual 300
        slow.statusCode() shouldBe 200
        slow.text shouldBe normal
    }

    @Test
    fun `a hang holds the request until the failures are cleared, then answers 503`() = withServer { server ->
        server.fail("/manga/second", FixtureFailure.Hang)

        val hung = server.getAsync("/manga/second")
        shouldThrow<TimeoutException> { hung.get(500, TimeUnit.MILLISECONDS) }

        server.clearFailures()
        hung.get(1, TimeUnit.SECONDS).statusCode() shouldBe 503
        server.get("/manga/second").statusCode() shouldBe 200
    }

    @Test
    fun `a hang added after a clear hangs again`() = withServer { server ->
        server.fail("/manga/second", FixtureFailure.Hang)
        server.clearFailures()
        server.fail("/manga/second", FixtureFailure.Hang)

        val hung = server.getAsync("/manga/second")
        shouldThrow<TimeoutException> { hung.get(500, TimeUnit.MILLISECONDS) }

        server.clearFailures()
        hung.get(1, TimeUnit.SECONDS).statusCode() shouldBe 503
    }

    @Test
    fun `hung requests do not starve the others`() = withServer { server ->
        server.fail("/images/", FixtureFailure.Hang)

        // More hung requests than the old fixed pool had threads
        val hung = (1..8).map { server.getAsync("/images/cover-${it % 4 + 1}.png") }
        server.get("/catalog").statusCode() shouldBe 200
        server.get("/manga/formats").statusCode() shouldBe 200

        server.clearFailures()
        hung.forEach { it.get(5, TimeUnit.SECONDS).statusCode() shouldBe 503 }
    }

    @Test
    fun `closing the server releases hung requests`() {
        val server = FixtureServer()
        server.fail("/manga/second", FixtureFailure.Hang)
        val hung = server.getAsync("/manga/second")
        shouldThrow<TimeoutException> { hung.get(500, TimeUnit.MILLISECONDS) }

        val start = System.nanoTime()
        server.close()
        // Answered 503, or the connection closed under it: either way it no longer hangs
        runCatching { hung.get(5, TimeUnit.SECONDS) }
        hung.isDone shouldBe true
        (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000) shouldBe true
    }

    @Test
    fun `a corrupt image keeps its content type but does not decode`() = withServer { server ->
        server.get("/images/jpeg-1.jpg").let { ImageIO.read(ByteArrayInputStream(it.body())) } shouldNotBe null
        server.fail("/images/jpeg-1.jpg", FixtureFailure.CorruptImage)

        val corrupt = server.get("/images/jpeg-1.jpg")
        corrupt.statusCode() shouldBe 200
        corrupt.headers().firstValue("Content-Type").orElse(null) shouldBe "image/jpeg"
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(corrupt.body())) }.getOrNull()
        decoded shouldBe null
    }

    @Test
    fun `a corrupt answer on a JSON path is JSON that does not parse`() = withServer { server ->
        server.fail("/manga/formats", FixtureFailure.CorruptImage)

        val corrupt = server.get("/manga/formats")
        corrupt.statusCode() shouldBe 200
        corrupt.headers().firstValue("Content-Type").orElse(null) shouldBe "application/json"
        corrupt.text shouldBe "{"
    }

    @Test
    fun `failed requests are still recorded`() = withServer { server ->
        server.fail("/manga/second", FixtureFailure.Status(500))
        server.fail("/catalog", FixtureFailure.Status(404), times = 1)

        server.get("/catalog")
        server.get("/manga/second")
        server.get("/catalog")

        server.requests shouldContainInOrder listOf("/catalog", "/manga/second", "/catalog")
        server.requestLog.map { it.path } shouldContainInOrder listOf("/catalog", "/manga/second", "/catalog")
    }

    @Test
    fun `failures apply to extra routes`() = withServer { server ->
        server.extraRoutes.add { exchange, parts ->
            if (parts != listOf("catalog")) return@add false
            val bytes = "extra catalog".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            true
        }
        server.fail("/catalog", FixtureFailure.Status(500))

        server.get("/catalog").statusCode() shouldBe 500

        server.clearFailures()
        val healed = server.get("/catalog")
        healed.statusCode() shouldBe 200
        healed.text shouldBe "extra catalog"
    }
}
