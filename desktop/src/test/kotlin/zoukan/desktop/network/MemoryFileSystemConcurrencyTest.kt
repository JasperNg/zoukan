package zoukan.desktop.network

import eu.kanade.tachiyomi.network.MemoryFileSystem
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileNotFoundException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

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
