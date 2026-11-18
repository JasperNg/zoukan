package zoukan.desktop.network

import eu.kanade.tachiyomi.network.MemoryFileSystem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
