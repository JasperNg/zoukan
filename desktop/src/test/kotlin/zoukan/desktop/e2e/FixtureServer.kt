package zoukan.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * How a path fails on purpose ([FixtureServer.fail]), for the error-state journeys.
 */
sealed interface FixtureFailure {

    /** Answers with [code] (404, 500, 504...) and a plain-text [body]. */
    data class Status(val code: Int, val body: String = "Fixture failure $code") : FixtureFailure

    /** Answers normally, but only after [delayMillis]. */
    data class Slow(val delayMillis: Long) : FixtureFailure

    /**
     * Holds the request without an answer until [FixtureServer.clearFailures] or
     * [FixtureServer.close], then answers 503. A forgotten hang gives up after 5 minutes.
     *
     * How long the app waits is the app's choice:
     * - the browse screens (global search, feed) stop a source call after 60 s
     *   (`withSourceTimeout`, GlobalSearchScreen.kt);
     * - the app's OkHttp client (`NetworkHelper.client`, which sources use) has a 30 s connect and
     *   a 30 s read timeout and a 120 s call timeout;
     * - `NetworkHelper.plainClient` (SyncYomi sync and other services that are not sources) has
     *   10 s connect, read and write timeouts and no call timeout;
     * - WebDAV sync (`WebDavSyncService`) builds on the plain client but sets 30 s connect, read
     *   and write timeouts, still with no call timeout.
     *
     * So a hang reaches the app's timeout message only after 10 s (SyncYomi) or 30 s or more
     * (sources, WebDAV). To show a timeout, answer [Status] 504 (a gateway timeout) instead; use
     * Hang only to show a loading state, then [FixtureServer.clearFailures].
     */
    data object Hang : FixtureFailure

    /**
     * An image answers 200 with its real content type and its full length, but only its format
     * magic is kept (the first 12 bytes, or an AVIF's whole `ftyp` box) and the rest is zeros:
     * a client still sniffs the format, but no decoder finds the image's size or data. An image
     * path without an image (a missing cover, the HTML page) answers as it does without the rule.
     * Any other path answers 200 JSON that is not valid (`{`).
     */
    data object CorruptImage : FixtureFailure

    // The failures below change the answer the path would give (a route, an image, the store, the
    // sync server or the release feed), for the fault-injection tests: a client must report each one
    // and keep nothing it half received.

    /** Closes the connection without any answer, as a server that crashes or a reset does. */
    data object Drop : FixtureFailure

    /**
     * Sends the path's status, headers and full Content-Length, then only the first [keepBytes]
     * bytes of the body, and closes the connection: a connection cut in the middle of the body.
     */
    data class Truncate(val keepBytes: Int) : FixtureFailure

    /**
     * Sends the path's status and full Content-Length and the first [afterBytes] bytes of the body,
     * then holds the rest until [FixtureServer.clearFailures] or [FixtureServer.close], and closes
     * the connection without it: a stall in the middle of the body, for a client's read timeout.
     */
    data class Stall(val afterBytes: Int) : FixtureFailure

    /** The path's answer at its length and type, with every byte from [fromByte] on inverted. */
    data class Corrupt(val fromByte: Int = 0) : FixtureFailure

    /** The path's answer with [contentType] in place of its own, as a misconfigured server or proxy sends. */
    data class WrongType(val contentType: String) : FixtureFailure

    /**
     * The path's answer with [extraBytes] more bytes after its body, sent chunked so no header gives
     * its size: for a client that checks a size it was told elsewhere (a release's asset size, a
     * signature's fixed length, a download cap).
     */
    data class Oversize(val extraBytes: Int) : FixtureFailure

    /** Answers [code] with [body] as [contentType] in place of the path's answer, for bad data of any kind. */
    class Answer(val code: Int, val contentType: String, val body: ByteArray) : FixtureFailure
}

/**
 * A local web server with the fixture manga, so the end-to-end journeys run offline and always see
 * the same catalogue. It serves the JSON [FixtureSource] reads and the page images generated by
 * `src/test/resources/e2e-fixtures/generate.py`: one chapter per image format (JPEG, PNG, WebP and
 * AVIF), each page one known colour so a test can check from a screenshot that it drew.
 *
 * It listens on 127.0.0.1 on a free port and records every path it is asked for, with the cookies
 * sent. The catalogue sets a cookie ([COOKIE_NAME]), as sites set a session cookie.
 */
class FixtureServer : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    // Cached, not fixed: each request gets a thread, so requests held by [FixtureFailure.Hang]
    // cannot starve the others
    private val pool = Executors.newCachedThreadPool { Thread(it, "fixture-server").apply { isDaemon = true } }

    private class FailureRule(val prefix: String, val failure: FixtureFailure, val remaining: AtomicInteger)

    private val failures = CopyOnWriteArrayList<FailureRule>()

    /** Hung requests wait on this; [clearFailures] opens it and puts a fresh one in its place. */
    private val healSignal = AtomicReference(CountDownLatch(1))

    private val closed = AtomicBoolean()

    /** Every path asked for, in order. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every request, in order, with its Cookie and User-Agent headers. */
    val requestLog: MutableList<FixtureRequest> = Collections.synchronizedList(mutableListOf())

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use { handle(it) }
        }
        server.executor = pool
        server.start()
    }

    /**
     * How long the server takes to answer for an image, by file name, as a slow site or a busy
     * machine does. A page that comes late must not make a journey turn past it.
     */
    val imageDelays: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** How many times an image was asked for. */
    fun imageRequests(file: String): Int = requests.count { it == "/images/$file" }

    /**
     * More routes, asked before the built-in ones with the request and its path segments: a route
     * that answers returns true. [FixtureStore] serves an extension store through this.
     */
    val extraRoutes = CopyOnWriteArrayList<(HttpExchange, List<String>) -> Boolean>()

    /**
     * Makes every path that starts with [pathPrefix] fail as [failure], [times] times (by default
     * until [clearFailures]). Rules are asked in the order they were added and the first that
     * matches and has times left applies. They apply before [extraRoutes] too, so to the store and
     * sync routes. The request is still recorded in [requests] and [requestLog].
     */
    fun fail(pathPrefix: String, failure: FixtureFailure, times: Int = Int.MAX_VALUE) {
        require(times > 0) { "times must be positive: $times" }
        failures += FailureRule(pathPrefix, failure, AtomicInteger(times))
    }

    /** Removes every failure rule and answers the requests a [FixtureFailure.Hang] holds. */
    fun clearFailures() {
        failures.clear()
        healSignal.getAndSet(CountDownLatch(1)).countDown()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requests += path
        requestLog += FixtureRequest(path, exchange.requestHeaders["Cookie"].orEmpty(), exchange.requestHeaders["User-Agent"].orEmpty())
        val parts = path.trim('/').split('/')
        // Taken before the rules are read, so a clearFailures in between still releases this request
        val heal = healSignal.get()
        val rule = failures.firstOrNull { rule ->
            path.startsWith(rule.prefix) && rule.remaining.getAndUpdate { n -> if (n > 0) n - 1 else n } > 0
        }
        if (rule != null) {
            failWith(exchange, rule.failure, parts, heal)
        } else {
            route(exchange, parts)
        }
    }

    private fun failWith(exchange: HttpExchange, failure: FixtureFailure, parts: List<String>, heal: CountDownLatch) {
        when (failure) {
            is FixtureFailure.Status -> send(exchange, failure.code, "text/plain", failure.body.toByteArray())
            is FixtureFailure.Slow -> {
                try {
                    Thread.sleep(failure.delayMillis)
                } catch (_: InterruptedException) {
                    return // the server is closing
                }
                route(exchange, parts)
            }
            FixtureFailure.Hang -> {
                if (!closed.get()) {
                    try {
                        heal.await(HANG_LIMIT_MINUTES, TimeUnit.MINUTES)
                    } catch (_: InterruptedException) {
                        // The server is closing: answer as if healed
                    }
                }
                // The server may be stopping; the client then just sees the connection close
                runCatching { send(exchange, 503, "text/plain", "Fixture hang released".toByteArray()) }
            }
            FixtureFailure.CorruptImage -> {
                if (parts.size != 2 || parts[0] != "images") return send(exchange, 200, "application/json", "{".toByteArray())
                // No image here: the path's own answer (404, or the HTML page)
                val bytes = imageBytes(parts[1]) ?: return route(exchange, parts)
                send(exchange, 200, imageType(parts[1]), corrupted(bytes))
            }
            // Nothing is sent: closing the exchange without headers closes the connection
            FixtureFailure.Drop -> Unit
            is FixtureFailure.Answer -> send(exchange, failure.code, failure.contentType, failure.body)
            is FixtureFailure.Truncate, is FixtureFailure.Stall, is FixtureFailure.Corrupt,
            is FixtureFailure.WrongType, is FixtureFailure.Oversize,
            -> reshape(exchange, parts, failure, heal)
        }
    }

    /** Runs the path's own route into a buffer, then sends its answer changed as [failure] says. */
    private fun reshape(exchange: HttpExchange, parts: List<String>, failure: FixtureFailure, heal: CountDownLatch) {
        val captured = CapturedExchange(exchange)
        route(captured, parts)
        val status = captured.status ?: return // the route sent nothing: the connection just closes
        val body = captured.body.toByteArray()
        when (failure) {
            is FixtureFailure.Truncate -> cut(exchange, status, body, failure.keepBytes, hold = null)
            is FixtureFailure.Stall -> cut(exchange, status, body, failure.afterBytes, hold = heal)
            is FixtureFailure.Corrupt -> sendRaw(
                exchange,
                status,
                ByteArray(body.size) { i -> if (i >= failure.fromByte) body[i].toInt().inv().toByte() else body[i] },
            )
            is FixtureFailure.WrongType -> {
                exchange.responseHeaders.set("Content-Type", failure.contentType)
                sendRaw(exchange, status, body)
            }
            is FixtureFailure.Oversize -> {
                // Length 0: chunked, so no header says how long the body is
                exchange.sendResponseHeaders(status, 0)
                exchange.responseBody.use {
                    it.write(body)
                    it.write(ByteArray(failure.extraBytes) { i -> (i % 251).toByte() })
                }
            }
            else -> error("Not a reshaping failure: $failure")
        }
    }

    /**
     * Declares [body]'s whole length, sends its first [keep] bytes and closes the connection, after
     * waiting for [hold] when there is one (a stall).
     */
    private fun cut(exchange: HttpExchange, status: Int, body: ByteArray, keep: Int, hold: CountDownLatch?) {
        exchange.responseHeaders.set("Connection", "close")
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isEmpty()) return
        val out = exchange.responseBody
        out.write(body, 0, keep.coerceIn(0, body.size - 1))
        out.flush()
        if (hold != null && !closed.get()) {
            try {
                hold.await(HANG_LIMIT_MINUTES, TimeUnit.MINUTES)
            } catch (_: InterruptedException) {
                // The server is closing
            }
        }
        // Thrown out of the handler, so the server closes the connection; returning would leave it
        // open, and the client waiting for the bytes that were declared
        throw java.io.IOException("Fixture cut the connection after $keep of ${body.size} bytes")
    }

    private fun sendRaw(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * An exchange whose answer is kept instead of sent: the status the route gave and the body it
     * wrote, while headers go to [real]'s, so [reshape] can send the answer changed.
     */
    private class CapturedExchange(private val real: HttpExchange) : HttpExchange() {
        var status: Int? = null
        val body = java.io.ByteArrayOutputStream()

        override fun getRequestHeaders(): com.sun.net.httpserver.Headers = real.requestHeaders
        override fun getResponseHeaders(): com.sun.net.httpserver.Headers = real.responseHeaders
        override fun getRequestURI(): java.net.URI = real.requestURI
        override fun getRequestMethod(): String = real.requestMethod
        override fun getHttpContext(): com.sun.net.httpserver.HttpContext = real.httpContext
        override fun close() = Unit
        override fun getRequestBody(): java.io.InputStream = real.requestBody
        override fun getResponseBody(): java.io.OutputStream = body
        override fun sendResponseHeaders(rCode: Int, responseLength: Long) {
            status = rCode
        }
        override fun getRemoteAddress(): InetSocketAddress = real.remoteAddress
        override fun getResponseCode(): Int = status ?: -1
        override fun getLocalAddress(): InetSocketAddress = real.localAddress
        override fun getProtocol(): String = real.protocol
        override fun getAttribute(name: String?): Any? = real.getAttribute(name)
        override fun setAttribute(name: String?, value: Any?) = real.setAttribute(name, value)
        override fun setStreams(i: java.io.InputStream?, o: java.io.OutputStream?) = real.setStreams(i, o)
        override fun getPrincipal(): com.sun.net.httpserver.HttpPrincipal? = real.principal
    }

    private fun route(exchange: HttpExchange, parts: List<String>) {
        if (extraRoutes.any { it(exchange, parts) }) return
        when {
            parts == listOf("catalog") -> {
                // A session cookie for the whole site, as a site's first page sets one
                exchange.responseHeaders.add("Set-Cookie", "$COOKIE_NAME=$COOKIE_VALUE; Max-Age=3600; Path=/")
                json(exchange, catalogJson())
            }
            parts.size == 2 && parts[0] == "manga" -> FixtureCatalog.manga(parts[1])?.let { json(exchange, mangaJson(it)) }
                ?: notFound(exchange)
            // The chapter list on its own path, so a journey can fail it while the details load
            parts.size == 3 && parts[0] == "manga" && parts[2] == "chapters" ->
                FixtureCatalog.manga(parts[1])?.let { json(exchange, mangaJson(it)) } ?: notFound(exchange)
            parts.size == 3 && parts[0] == "chapter" -> {
                val chapter = FixtureCatalog.manga(parts[1])?.chapters?.firstOrNull { it.key == parts[2] }
                if (chapter == null) notFound(exchange) else json(exchange, pagesJson(chapter))
            }
            parts.size == 2 && parts[0] == "images" -> image(exchange, parts[1])
            else -> notFound(exchange)
        }
    }

    private fun catalogJson(): JsonArray = buildJsonArray {
        FixtureCatalog.manga.forEach { manga ->
            add(
                buildJsonObject {
                    put("key", manga.key)
                    put("title", manga.title)
                    put("cover", "$baseUrl/images/${manga.cover}")
                    put("bonus", manga.bonus)
                    put("searchOnly", manga.searchOnly)
                    put("genre", manga.genre)
                },
            )
        }
    }

    private fun mangaJson(manga: FixtureManga): JsonObject = buildJsonObject {
        put("key", manga.key)
        put("title", manga.title)
        put("author", "Fixture Author")
        put("description", manga.description)
        put("cover", "$baseUrl/images/${manga.cover}")
        put(
            "chapters",
            buildJsonArray {
                manga.chapters.forEach { chapter ->
                    add(
                        buildJsonObject {
                            put("key", chapter.key)
                            put("name", chapter.name)
                            put("number", chapter.number)
                            put("date", chapter.date)
                        },
                    )
                }
            },
        )
    }

    private fun pagesJson(chapter: FixtureChapter): JsonArray = buildJsonArray {
        FixtureCatalog.pages(chapter.format).forEach { add(JsonPrimitive("$baseUrl/images/${it.file}")) }
        // The scrambled chapter ends with a page the site answers with HTML
        if (chapter.format == FixtureCatalog.SCRAMBLED) add(JsonPrimitive("$baseUrl/images/${FixtureCatalog.HTML_PAGE}"))
    }

    private fun image(exchange: HttpExchange, file: String) {
        // Only the generated files, never a path out of the folder
        if (!FILE_NAME.matches(file)) return notFound(exchange)
        imageDelays[file]?.let { Thread.sleep(it) }
        // An image URL that answers with a web page, as a site's error or login page does
        if (file == FixtureCatalog.HTML_PAGE) {
            return send(exchange, 200, "text/html", "<html><body><h1>Please sign in</h1></body></html>".toByteArray())
        }
        val bytes = imageBytes(file) ?: return notFound(exchange)
        send(exchange, 200, imageType(file), bytes)
    }

    /** A generated image's bytes, or null for a name that is not one. */
    private fun imageBytes(file: String): ByteArray? {
        if (!FILE_NAME.matches(file)) return null
        return FixtureServer::class.java.getResourceAsStream("/e2e-fixtures/images/$file")?.use { it.readBytes() }
    }

    private fun imageType(file: String): String = when (file.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }

    // The charset said out loud: the titles are not all ASCII (FixtureCatalog.CJK_TITLE)
    private fun json(exchange: HttpExchange, body: Any) =
        send(exchange, 200, JSON_TYPE, body.toString().toByteArray(Charsets.UTF_8))

    private fun notFound(exchange: HttpExchange) = send(exchange, 404, "text/plain", "Not found".toByteArray())

    private fun send(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * Stops the server; a journey closes it early to check what still works offline. Requests a
     * [FixtureFailure.Hang] holds are answered first.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        healSignal.get().countDown()
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        private const val HANG_LIMIT_MINUTES = 5L

        /** Enough for every format's magic: JPEG's SOI and APP0 marker, PNG's signature, RIFF....WEBP. */
        private const val CORRUPT_KEPT_BYTES = 12

        /**
         * [bytes] at the same length with only the format magic kept: the first
         * [CORRUPT_KEPT_BYTES], or for ISO-BMFF (AVIF) the `ftyp` box, whose brands say the format.
         * The header that follows (JPEG's frame, PNG's IHDR, WebP's VP8, AVIF's meta) is zeros.
         */
        internal fun corrupted(bytes: ByteArray): ByteArray {
            val ftyp = bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val ftypSize = if (ftyp) ByteBuffer.wrap(bytes, 0, 4).int else 0
            val kept = maxOf(CORRUPT_KEPT_BYTES, ftypSize).coerceAtMost(bytes.size)
            return ByteArray(bytes.size).also { bytes.copyInto(it, endIndex = kept) }
        }

        const val COOKIE_NAME = "fixture_session"
        const val COOKIE_VALUE = "journey-cookie-1"

        /** The content type of the JSON the server answers. */
        const val JSON_TYPE = "application/json; charset=utf-8"
        private val FILE_NAME = Regex("[a-z0-9-]+\\.(jpg|png|webp|avif)")
    }
}

/**
 * A request the server got: its path, its Cookie headers (none when it sent no cookie) and its
 * User-Agent headers.
 */
data class FixtureRequest(val path: String, val cookieHeaders: List<String>, val userAgents: List<String> = emptyList()) {
    /** The value of each cookie named [name] it sent, in order; more than one is a duplicate. */
    fun cookieValues(name: String): List<String> = cookieHeaders.flatMap { it.split(';') }
        .map { it.trim() }
        .filter { it.substringBefore('=') == name }
        .map { it.substringAfter('=', "") }
}

/** A page of the fixture manga: its image file and the colour most of it is. */
data class FixturePage(val file: String, val red: Int, val green: Int, val blue: Int)

data class FixtureChapter(val key: String, val name: String, val number: Float, val date: Long, val format: String)

data class FixtureManga(
    val key: String,
    val title: String,
    val cover: String,
    val description: String,
    val chapters: List<FixtureChapter>,
    /** Listed only when the source's "Show the bonus title" setting is on. */
    val bonus: Boolean = false,
    /** Listed only in search results, so the catalogue's default count stays the same. */
    val searchOnly: Boolean = false,
    /** The one genre [FixtureSource]'s "Hidden genres" setting filters on. */
    val genre: String = FixtureCatalog.GENRE_ACTION,
)

/** The fixture catalogue, and the pages `generate.py` made (from its manifest.json). */
object FixtureCatalog {

    /** The image formats, in the order the chapters of [FORMATS_TITLE] use them. */
    val formats = listOf("jpeg", "png", "webp", "avif")

    const val FORMATS_TITLE = "Fixture Formats"
    const val BONUS_TITLE = "Fixture Bonus"

    /** Search-only: its description from the server is HTML (see HtmlDescriptionJourneyTest). */
    const val HTML_TITLE = "Markup Description"
    const val HTML_DESCRIPTION = "Tom &amp; Jerry<br>Second line &lt;3"

    /** Search-only: its chapter's pages are scrambled (see DescramblingJourneyTest). */
    const val SCRAMBLED_TITLE = "Scrambled Pages"

    /** The format of the scrambled chapter; its pages are manifest.json's "scrambled". */
    const val SCRAMBLED = "scrambled"

    /** The scrambled chapter's last page: an image URL the server answers with HTML. */
    const val HTML_PAGE = "scrambled-html.jpg"

    /**
     * Search-only: a title far longer than any grid cell, row or header has room for (about 180
     * characters), for truncation, ellipsis and wrapping. In the middle is one 45-letter word with
     * no space to break at, so a layout that only wraps at spaces overflows there.
     */
    const val LONG_TITLE = "The Extraordinarily Long Chronicle of the Wandering Cartographer and the " +
        "Pneumonoultramicroscopicsilicovolcanoconiosis Outbreak in the Forgotten Northern Mountain Villages, Volume Two"

    /**
     * Search-only: Japanese (kanji, hiragana, katakana) and Korean (Hangul), for font fallback:
     * the glyphs come from a system CJK font, which a bare Linux CI image may not have. Its
     * description is CJK too, and the server must send both as UTF-8.
     */
    const val CJK_TITLE = "東京の漫画テスト・長いタイトル 한국어"
    const val CJK_DESCRIPTION = "東京を舞台にした試験用の漫画です。한국어 설명도 있습니다."

    /**
     * Search-only: its cover ([MISSING_COVER]) is not on the server, which answers 404, so the
     * app shows its broken-cover placeholder.
     */
    const val MISSING_COVER_TITLE = "Fixture Without Cover"

    /** A cover file name the server accepts but has no image for (404). */
    const val MISSING_COVER = "missing-cover.png"

    const val GENRE_ACTION = "action"
    const val GENRE_COMEDY = "comedy"
    const val GENRE_DRAMA = "drama"

    private const val DAY = 24 * 60 * 60 * 1000L
    private const val START = 1_767_225_600_000L // 2026-01-01

    val manga: List<FixtureManga> = listOf(
        FixtureManga(
            key = "formats",
            title = FORMATS_TITLE,
            cover = "cover-1.png",
            description = "One chapter in each image format: JPEG, PNG, WebP and AVIF.",
            chapters = formats.mapIndexed { i, format ->
                FixtureChapter(format, "Chapter ${i + 1} (${format.uppercase()})", (i + 1).toFloat(), START + i * DAY, format)
            },
        ),
        FixtureManga(
            key = "second",
            title = "Fixture Second",
            cover = "cover-2.png",
            description = "A second title, so the catalogue lists more than one.",
            chapters = listOf(FixtureChapter("jpeg", "Chapter 1", 1f, START, "jpeg")),
            genre = GENRE_DRAMA,
        ),
        FixtureManga(
            key = "third",
            title = "Fixture Third",
            cover = "cover-3.png",
            description = "A third title.",
            chapters = listOf(FixtureChapter("png", "Chapter 1", 1f, START, "png")),
            genre = GENRE_COMEDY,
        ),
        FixtureManga(
            key = "bonus",
            title = BONUS_TITLE,
            cover = "cover-4.png",
            description = "Listed only with the source's bonus setting on.",
            chapters = listOf(FixtureChapter("webp", "Chapter 1", 1f, START, "webp")),
            bonus = true,
        ),
        FixtureManga(
            key = "markup",
            title = HTML_TITLE,
            cover = "cover-2.png",
            description = HTML_DESCRIPTION,
            chapters = listOf(FixtureChapter("jpeg", "Chapter 1", 1f, START, "jpeg")),
            searchOnly = true,
        ),
        FixtureManga(
            key = "scrambled",
            title = SCRAMBLED_TITLE,
            cover = "cover-3.png",
            description = "Its pages come scrambled; the source puts them back together.",
            chapters = listOf(FixtureChapter(SCRAMBLED, "Chapter 1 (scrambled)", 1f, START, SCRAMBLED)),
            searchOnly = true,
        ),
        FixtureManga(
            key = "long",
            title = LONG_TITLE,
            cover = "cover-4.png",
            description = "A title too long for any row, with one very long word in the middle.",
            chapters = listOf(FixtureChapter("jpeg", "Chapter 1", 1f, START, "jpeg")),
            searchOnly = true,
        ),
        FixtureManga(
            key = "cjk",
            title = CJK_TITLE,
            cover = "cover-1.png",
            description = CJK_DESCRIPTION,
            chapters = listOf(FixtureChapter("png", "第1話", 1f, START, "png")),
            searchOnly = true,
        ),
        FixtureManga(
            key = "nocover",
            title = MISSING_COVER_TITLE,
            cover = MISSING_COVER,
            description = "Its cover is missing on the server.",
            chapters = listOf(FixtureChapter("jpeg", "Chapter 1", 1f, START, "jpeg")),
            searchOnly = true,
        ),
    )

    fun manga(key: String): FixtureManga? = manga.firstOrNull { it.key == key }

    private val pages: Map<String, List<FixturePage>> by lazy {
        val text = FixtureCatalog::class.java.getResourceAsStream("/e2e-fixtures/manifest.json")!!.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonObject.getValue("pages").jsonObject.mapValues { (_, list) ->
            list.jsonArray.map { page ->
                val rgb = page.jsonObject.getValue("rgb").jsonArray.map { it.jsonPrimitive.int }
                FixturePage(page.jsonObject.getValue("file").jsonPrimitive.content, rgb[0], rgb[1], rgb[2])
            }
        }
    }

    fun pages(format: String): List<FixturePage> = pages.getValue(format)
}
