package zoukan.desktop.network

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.MemoryFileSystem
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallenge
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import exh.log.EHLogLevel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import okio.FileNotFoundException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** The desktop's HTTP cache: held in memory only, bounded, and working as OkHttp's disk cache does. */
class MemoryFileSystemTest {
    private val dir = "/cache".toPath()

    private fun MemoryFileSystem.write(name: String, text: String) =
        sink(dir / name).buffer().use { it.writeUtf8(text) }

    private fun MemoryFileSystem.read(name: String) = source(dir / name).buffer().use { it.readUtf8() }

    @Test
    fun `files are written, read, appended, moved, listed and deleted`() {
        val fs = MemoryFileSystem(maxTotalBytes = 1_000, maxFileBytes = 1_000)
        assertThrows<IOException> { fs.write("a", "no parent yet") }
        fs.createDirectories(dir)
        fs.write("a", "hello")
        fs.appendingSink(dir / "a").buffer().use { it.writeUtf8(" world") }
        fs.read("a") shouldBe "hello world"
        fs.metadata(dir / "a").size shouldBe 11L
        fs.size shouldBe 11L

        fs.write("b", "replaced")
        fs.atomicMove(dir / "b", dir / "a")
        fs.read("a") shouldBe "replaced"
        fs.exists(dir / "b") shouldBe false
        fs.size shouldBe 8L
        fs.list(dir) shouldContainExactly listOf(dir / "a")

        fs.delete(dir / "a")
        fs.exists(dir / "a") shouldBe false
        fs.size shouldBe 0L
        assertThrows<IOException> { fs.delete(dir / "a", mustExist = true) }
        fs.deleteRecursively(dir)
        fs.exists(dir) shouldBe false
        fs.metadataOrNull(dir).shouldBeNull()
    }

    @Test
    fun `an open source keeps its bytes when the file is replaced or deleted`() {
        val fs = MemoryFileSystem(maxTotalBytes = 1_000, maxFileBytes = 1_000)
        fs.createDirectories(dir)
        fs.write("a", "first")
        val open = fs.source(dir / "a").buffer()
        fs.write("a", "second")
        fs.delete(dir / "a")
        open.readUtf8() shouldBe "first"
    }

    @Test
    fun `writes past the size limits fail, except to uncapped files`() {
        val fs = MemoryFileSystem(maxTotalBytes = 10, maxFileBytes = 8, uncapped = { it.name == "journal" })
        fs.createDirectories(dir)
        assertThrows<IOException> { fs.write("big", "123456789") }
        fs.write("a", "12345678")
        assertThrows<IOException> { fs.write("b", "123") }
        fs.write("journal", "123")
        fs.size shouldBe 11L
        // A deleted file's bytes are freed
        fs.delete(dir / "a")
        fs.write("b", "123")
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val requests = AtomicInteger()

    @AfterEach
    fun tearDown() = server.close()

    /** Answers every request with [body], cacheable for a minute. */
    private fun serve(body: String) = thread(isDaemon = true, name = "cache test server") {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                reader.readLine() ?: return@use
                do {
                    val header = reader.readLine()
                } while (!header.isNullOrEmpty())
                requests.incrementAndGet()
                val bytes = body.toByteArray()
                val head = "HTTP/1.1 200 OK\r\nCache-Control: max-age=60\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                it.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private fun client(maxSize: Long) = OkHttpClient.Builder()
        .cache(
            Cache(
                MemoryFileSystem(maxTotalBytes = 3 * maxSize, maxFileBytes = maxSize, uncapped = { it.name.startsWith("journal") }),
                "/network_cache".toPath(),
                maxSize,
            ),
        )
        .build()

    private fun url() = "http://127.0.0.1:${server.localPort}/page"

    @Test
    fun `OkHttp's cache answers a repeated request from memory`() {
        serve("cached body")
        val client = client(maxSize = 1024 * 1024)
        client.newCall(Request.Builder().url(url()).build()).execute().use {
            it.body.string() shouldBe "cached body"
            it.networkResponse.shouldNotBeNull()
        }
        client.newCall(Request.Builder().url(url()).build()).execute().use {
            it.body.string() shouldBe "cached body"
            it.cacheResponse.shouldNotBeNull()
            it.networkResponse.shouldBeNull()
        }
        requests.get() shouldBe 1
    }

    @Test
    fun `a response too large for the cache still arrives whole and is not kept`() {
        val large = "x".repeat(64 * 1024)
        serve(large)
        val client = client(maxSize = 16 * 1024)
        repeat(2) {
            client.newCall(Request.Builder().url(url()).build()).execute().use {
                it.body.string() shouldBe large
                it.cacheResponse.shouldBeNull()
            }
        }
        requests.get() shouldBe 2
    }
}

/**
 * The in-memory HTTP cache under load from many threads at once, as the app's clients share it:
 * no response or file comes back mixed up, and the byte count matches the files it holds.
 */
class MemoryFileSystemConcurrencyTest {
    private val server = ServerSocket(0, 200, InetAddress.getLoopbackAddress())
    private val requests = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(THREADS)

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        server.close()
    }

    @Test
    fun `parallel calls through the shared cache each get their own whole response`() {
        serve()
        val maxSize = 64L * 1024
        val fs = MemoryFileSystem(maxTotalBytes = 3 * maxSize, maxFileBytes = maxSize, uncapped = { it.name.startsWith("journal") })
        val cache = Cache(fs, "/network_cache".toPath(), maxSize)
        val client = OkHttpClient.Builder().cache(cache).build()

        runAll { worker ->
            val random = Random(worker)
            repeat(40) {
                val page = random.nextInt(PAGES)
                val url = "http://127.0.0.1:${server.localPort}/page$page"
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    response.body.string() shouldBe body(page)
                }
            }
        }

        // Some answers came from the cache, and the cache still answers correctly afterwards
        (requests.get() < THREADS * 40) shouldBe true
        repeat(PAGES) { page ->
            val url = "http://127.0.0.1:${server.localPort}/page$page"
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() shouldBe body(page) }
        }
        cache.close()
        fs.size shouldBe filesSize(fs)
        fs.size shouldBeLessThanOrEqual 3 * maxSize
    }

    @Test
    fun `concurrent writes, moves, reads and deletes never mix files up and keep the size right`() {
        val fs = MemoryFileSystem(maxTotalBytes = Long.MAX_VALUE, maxFileBytes = Long.MAX_VALUE)
        val dir = "/d".toPath()
        fs.createDirectory(dir)
        val shared = List(4) { dir / "shared$it" }

        runAll { worker ->
            val random = Random(worker)
            val own = dir / "own$worker"
            val temp = dir / "tmp$worker"
            repeat(300) { round ->
                when (random.nextInt(5)) {
                    // A whole file at once, as the cache stores an entry: written aside, then moved in
                    0 -> {
                        fs.write(temp, content('a' + worker, random.nextInt(1, 20_000)))
                        fs.atomicMove(temp, shared.random(random))
                    }
                    1 -> fs.readOrNull(shared.random(random))?.let(::checkWhole)
                    2 -> fs.delete(shared.random(random), mustExist = false)
                    // A file only this worker writes reads back exactly as written
                    else -> {
                        val text = content('A' + worker, random.nextInt(1, 20_000) + round)
                        fs.write(own, text)
                        fs.readOrNull(own) shouldBe text
                    }
                }
            }
        }

        fs.size shouldBe filesSize(fs)
        fs.list(dir).forEach { fs.delete(it) }
        fs.size shouldBe 0L
    }

    /** Runs [work] on [THREADS] threads that start together; rethrows the first failure. */
    private fun runAll(work: (worker: Int) -> Unit) {
        val start = CountDownLatch(1)
        val futures = (0 until THREADS).map { worker ->
            pool.submit {
                start.await()
                work(worker)
            }
        }
        start.countDown()
        futures.forEach { it.get(2, TimeUnit.MINUTES) }
    }

    /** The bytes of all regular files, counted one by one. */
    private fun filesSize(fs: MemoryFileSystem): Long = fs.listRecursively("/".toPath())
        .mapNotNull { fs.metadataOrNull(it) }
        .filter { it.isRegularFile }
        .sumOf { it.size ?: 0L }

    private fun MemoryFileSystem.write(path: Path, text: String) = sink(path).buffer().use { it.writeUtf8(text) }

    private fun MemoryFileSystem.readOrNull(path: Path): String? = try {
        source(path).buffer().use { it.readUtf8() }
    } catch (_: FileNotFoundException) {
        null
    }

    /** "<length>:" then one character repeated, so a mixed-up or cut-short file is easy to spot. */
    private fun content(char: Char, length: Int) = "$length:" + char.toString().repeat(length)

    private fun checkWhole(text: String) {
        val length = text.substringBefore(':').toInt()
        val rest = text.substringAfter(':')
        rest.length shouldBe length
        rest.all { it == rest[0] } shouldBe true
    }

    /** A different body for each page, a few kilobytes each, so the cache has to evict some. */
    private fun body(page: Int) = "page $page " + ('a' + page).toString().repeat(2_000 + page * 500)

    /** Answers each connection on its own thread with the page's body, cacheable for a minute. */
    private fun serve() = thread(isDaemon = true, name = "cache concurrency test server") {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            thread(isDaemon = true) {
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    do {
                        val header = reader.readLine()
                    } while (!header.isNullOrEmpty())
                    requests.incrementAndGet()
                    val page = line.substringAfter("/page").substringBefore(' ').toInt()
                    val bytes = body(page).toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nCache-Control: max-age=60\r\nContent-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(head.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    private companion object {
        const val THREADS = 8
        const val PAGES = 20
    }
}

class CloudflareInterceptorTest {
    /** A body of [size] zero bytes made on demand, counting what is read. */
    private class CountingSource(private val size: Long) : Source {
        var read = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (read >= size) return -1
            val n = minOf(byteCount, size - read, 8192)
            sink.write(ByteArray(n.toInt()))
            read += n
            return n
        }
        override fun timeout() = Timeout.NONE
        override fun close() {}
    }

    private fun response(code: Int, body: ResponseBody) = Response.Builder()
        .request(Request.Builder().url("https://source.example/page").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("").header("Server", "cloudflare")
        .body(body).build()

    @Test
    fun `a 403 from cloudflare with a 16 MB body is not read past the limit`() {
        val source = CountingSource(16L * 1024 * 1024)
        val body = object : ResponseBody() {
            private val buffered = source.buffer()
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = buffered
        }
        CloudflareChallenge.isChallenge(response(403, body)) shouldBe false
        source.read shouldBeLessThanOrEqual 1024L * 1024 + 64 * 1024
    }

    @Test
    fun `a small challenge page is still recognised`() {
        val page = "<html><body><h1 id=\"challenge-error-title\">Checking</h1></body></html>"
        CloudflareChallenge.isChallenge(response(403, page.toResponseBody())) shouldBe true
    }
}

class RateLimitInterceptorTest {
    // The rate limiter treats a response without a network response as cached and frees its slot,
    // so the local answer must carry one
    private val answer = Interceptor { chain ->
        val network = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .networkResponse(network).body("ok".toResponseBody()).build()
    }

    @Test
    fun `a third request within the period waits for the first slot to free`() {
        val client = OkHttpClient.Builder().rateLimit(permits = 2, period = 1.seconds).addInterceptor(answer).build()
        val started = System.nanoTime()
        repeat(3) { client.newCall(Request.Builder().url("https://source.example/$it").build()).execute().close() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        elapsedMs shouldBeGreaterThanOrEqual 900L
        elapsedMs shouldBeLessThan 5_000L
    }

    @Test
    fun `requests within the permits do not wait`() {
        val client = OkHttpClient.Builder().rateLimit(permits = 3, period = 10.seconds).addInterceptor(answer).build()
        val started = System.nanoTime()
        repeat(3) { client.newCall(Request.Builder().url("https://source.example/$it").build()).execute().close() }
        ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 2_000L
    }
}

/** Verbose network logging keeps URLs (as upstream) but not the values of credential-like query parameters. */
class NetworkLoggingRedactionTest {
    @TempDir
    lateinit var dir: File

    private fun setLogLevel(level: EHLogLevel) {
        val prefs = mockk<SharedPreferences> { every { getInt(any(), any()) } returns level.ordinal }
        EHLogLevel.init(
            mockk<Context> {
                every { packageName } returns "zoukan.test"
                every { getSharedPreferences(any<String>(), any<Int>()) } returns prefs
            },
        )
    }

    @AfterEach
    fun resetLogLevel() = setLogLevel(EHLogLevel.MINIMAL)

    @Test
    fun `the logging interceptor redacts token-like query parameters`() {
        setLogLevel(EHLogLevel.EXTRA)
        val context = mockk<Context> { every { cacheDir } returns dir }
        val client = NetworkHelper(context, NetworkPreferences(InMemoryPreferenceStore())).client
        val logging = client.networkInterceptors.filterIsInstance<HttpLoggingInterceptor>().single()
        val redactUrl = HttpLoggingInterceptor::class.java.methods.single { it.name.startsWith("redactUrl") }

        val url = "https://api.example.com/v1/list?page=2&access_token=s3cret&API_KEY=k&sid=abc&q=one+piece".toHttpUrl()
        val logged = redactUrl.invoke(logging, url) as String
        // OkHttp writes ██ (percent-encoded) in place of each value
        val redacted = "%E2%96%88%E2%96%88"
        logged shouldBe "https://api.example.com/v1/list?page=2&access_token=$redacted&API_KEY=$redacted&sid=$redacted&q=one%20piece"
        // An address without such parameters is logged as it is
        val plain: HttpUrl = "https://example.com/manga/1?page=3".toHttpUrl()
        redactUrl.invoke(logging, plain) shouldBe plain.toString()
    }
}
