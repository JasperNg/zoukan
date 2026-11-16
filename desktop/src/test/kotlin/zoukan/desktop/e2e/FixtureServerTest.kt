package zoukan.desktop.e2e

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.serviceLoaderEnabled
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import zoukan.desktop.download.ImageType
import zoukan.desktop.e2e.FixtureCatalog.CJK_DESCRIPTION
import zoukan.desktop.e2e.FixtureCatalog.CJK_TITLE
import zoukan.desktop.e2e.FixtureCatalog.LONG_TITLE
import zoukan.desktop.e2e.FixtureCatalog.MISSING_COVER
import zoukan.desktop.e2e.FixtureCatalog.MISSING_COVER_TITLE
import zoukan.desktop.image.FfmpegCoilDecoder
import zoukan.desktop.ui.reader.PageImage
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.IIOException

/**
 * The failures [FixtureServer.fail] puts on paths, which the error-state journeys use, and the
 * search-only long, CJK and missing-cover titles. Plain HTTP against the server, no app runtime;
 * corrupt images go through the app's own image decoders.
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
    fun `the chapter list has its own path, so it can fail while the details load`() = withServer { server ->
        server.fail("/manga/second/chapters", FixtureFailure.Status(500))

        server.get("/manga/second/chapters").statusCode() shouldBe 500
        server.get("/manga/second").statusCode() shouldBe 200
        val chapters = server.get("/manga/formats/chapters")
        chapters.statusCode() shouldBe 200
        chapters.text shouldBe server.get("/manga/formats").text
        server.get("/manga/nope/chapters").statusCode() shouldBe 404
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
        hung.get(RELEASE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 503
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
        hung.get(RELEASE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 503
    }

    @Test
    fun `hung requests do not starve the others`() = withServer { server ->
        server.fail("/images/", FixtureFailure.Hang)

        // More hung requests than the old fixed pool had threads
        val hung = (1..8).map { server.getAsync("/images/cover-${it % 4 + 1}.png") }
        server.get("/catalog").statusCode() shouldBe 200
        server.get("/manga/formats").statusCode() shouldBe 200

        server.clearFailures()
        hung.forEach { it.get(RELEASE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 503 }
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

    /**
     * A page of each format the fixture chapters use: its content type, the type the app sniffs,
     * and the error the reader's decode ([PageImage.decode]) throws for it once corrupt.
     */
    private data class Corruptible(val file: String, val type: String, val sniffed: ImageType, val readerError: Class<out Throwable>)

    private val corruptible = listOf(
        // Skia fails, then ImageIO's JPEG and PNG readers throw rather than answer null
        Corruptible("jpeg-1.jpg", "image/jpeg", ImageType.JPEG, IIOException::class.java),
        Corruptible("png-1.png", "image/png", ImageType.PNG, IIOException::class.java),
        // No ImageIO reader takes it: the reader's own "unsupported image format"
        Corruptible("webp-1.webp", "image/webp", ImageType.WEBP, zoukan.desktop.ui.common.UnreadableImageException::class.java),
        // The AVIF reader's FFmpeg cannot open it
        Corruptible("avif-1.avif", "image/avif", ImageType.AVIF, IOException::class.java),
    )

    @Test
    fun `a corrupt image keeps its content type, length and format, but the reader cannot decode it`() = withServer { server ->
        server.fail("/images/", FixtureFailure.CorruptImage, times = corruptible.size)
        val corrupt = corruptible.associate { it.file to server.get("/images/${it.file}") }
        corruptible.forEach { (file, type, sniffed, readerError) ->
            val intact = server.get("/images/$file").body()
            // The reader's own decode (Skia, then ImageIO with the AVIF reader) reads the real page
            PageImage.decode(intact).width shouldBeGreaterThan 0

            val response = corrupt.getValue(file)
            response.statusCode() shouldBe 200
            response.headers().firstValue("Content-Type").orElse(null) shouldBe type
            response.body().size shouldBe intact.size
            ImageType.fromBytes(response.body()) shouldBe sniffed
            // Skia fails on every one; the error the reader shows is the fallback's for that format
            val error = runCatching { PageImage.decode(response.body()) }.exceptionOrNull()
            withClue("$file: $error") { error?.javaClass shouldBe readerError }
        }
    }

    @Test
    fun `a corrupt image fails in the cover loader`() = withServer { server ->
        // Coil as the app sets it up for covers and previews: its Skia decoder, and the app's AVIF decoder
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .serviceLoaderEnabled(false)
            .components { add(FfmpegCoilDecoder.Factory()) }
            .memoryCache { null }
            .diskCache { null }
            .build()
        fun load(bytes: ByteArray) = runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(bytes).build()) }
        server.fail("/images/", FixtureFailure.CorruptImage, times = corruptible.size)
        val corrupt = corruptible.associate { it.file to server.get("/images/${it.file}").body() }
        corruptible.forEach { (file) ->
            withClue(file) {
                load(server.get("/images/$file").body()).shouldBeInstanceOf<SuccessResult>()
                load(corrupt.getValue(file)).shouldBeInstanceOf<ErrorResult>()
            }
        }
        loader.shutdown()
    }

    @Test
    fun `a corrupt image rule leaves image paths without an image as they are`() = withServer { server ->
        server.fail("/images/", FixtureFailure.CorruptImage)

        server.get("/images/$MISSING_COVER").statusCode() shouldBe 404
        val html = server.get("/images/${FixtureCatalog.HTML_PAGE}")
        html.statusCode() shouldBe 200
        html.headers().firstValue("Content-Type").orElse(null) shouldBe "text/html"
        server.get("/images/cover-1.png").headers().firstValue("Content-Type").orElse(null) shouldBe "image/png"
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

    // The long, CJK and missing-cover titles

    @Test
    fun `the catalogue holds the long, CJK and missing-cover titles, search-only`() = withServer { server ->
        val catalog = Json.parseToJsonElement(server.get("/catalog").text).jsonArray.map { it.jsonObject }
        val added = catalog.filter { it.string("title") in listOf(LONG_TITLE, CJK_TITLE, MISSING_COVER_TITLE) }

        added.map { it.string("key") } shouldContainExactlyInAnyOrder listOf("long", "cjk", "nocover")
        added.forEach { it.getValue("searchOnly").jsonPrimitive.boolean shouldBe true }
        added.single { it.string("key") == "nocover" }.string("cover") shouldBe "${server.baseUrl}/images/$MISSING_COVER"
        // The source's default catalogue is unchanged: the journeys count it
        FixtureSource.catalogPage(server.get("/catalog").text, query = "").mangas.map { it.title } shouldContainExactly
            listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second", "Fixture Third")
    }

    @Test
    fun `the long title is about 180 characters with a 40-character run without spaces in the middle`() {
        LONG_TITLE.length shouldBeInRange 170..190
        val longestRun = LONG_TITLE.split(' ').maxBy { it.length }
        longestRun.length shouldBeGreaterThanOrEqual 40
        val start = LONG_TITLE.indexOf(longestRun)
        // Neither at the start nor at the end: words to wrap before and after it
        start shouldBeGreaterThan 40
        (LONG_TITLE.length - start - longestRun.length) shouldBeGreaterThan 40
    }

    @Test
    fun `the source's search finds each title, a CJK query too`() = withServer { server ->
        val queries = mapOf(
            "Pneumonoultramicroscopic" to LONG_TITLE,
            "東京の漫画" to CJK_TITLE,
            "한국어" to CJK_TITLE,
            "Without Cover" to MISSING_COVER_TITLE,
        )
        queries.forEach { (query, title) ->
            // The source's own request and parse, through OkHttp as in the app
            OkHttpClient().newCall(Request.Builder().url(FixtureSource.searchUrl(server.baseUrl, query)).build()).execute().use { response ->
                // The query comes back from the URL as it went in
                response.request.url.queryParameter("q") shouldBe query
                val found = FixtureSource.catalogPage(response.body.string(), response.request.url.queryParameter("q").orEmpty())
                found.mangas.map { it.title } shouldContainExactly listOf(title)
            }
        }
        server.requests.count { it == "/catalog" } shouldBe queries.size
    }

    @Test
    fun `the missing cover answers 404`() = withServer { server ->
        server.get("/images/$MISSING_COVER").statusCode() shouldBe 404
        server.get("/images/cover-1.png").statusCode() shouldBe 200
    }

    @Test
    fun `the CJK title's JSON is UTF-8 and says so`() = withServer { server ->
        val response = server.get("/manga/cjk")
        response.statusCode() shouldBe 200
        val type = response.headers().firstValue("Content-Type").orElse("")
        type shouldBe FixtureServer.JSON_TYPE
        val charset = Charset.forName(type.substringAfter("charset=", "ISO-8859-1"))
        charset shouldBe Charsets.UTF_8
        val manga = Json.parseToJsonElement(String(response.body(), charset)).jsonObject
        manga.string("title") shouldBe CJK_TITLE
        manga.string("description") shouldBe CJK_DESCRIPTION
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        /**
         * How long a released hang may take to answer. Generous for a loaded runner: the tests
         * still prove the hold, by the 500 ms wait that timed out before the release.
         */
        const val RELEASE_SECONDS = 5L
    }
}
