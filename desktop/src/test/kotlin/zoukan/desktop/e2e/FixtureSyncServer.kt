package zoukan.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The two sync services the app talks to, on the [FixtureServer], for the sync journeys. Both serve
 * one remote backup: the protobuf bytes of a `Backup` (not gzipped, as the sync services read and
 * write it), held in memory only, with an ETag that counts up (`"v1"`, `"v2"`...) on every store.
 *
 * **WebDAV** under `/dav/` ([webDavUrl] is what the app's "WebDAV URL" setting takes), with Basic
 * auth ([user], [password]; 401 without):
 * - `MKCOL /dav/<folder>`: 201, or 405 when it exists, or 409 when its parent does not.
 * - `GET /dav/<davFolder>/backup.proto`: 404 while there is no backup; else 200 with the bytes and
 *   the ETag, or 304 when `If-None-Match` names the current ETag.
 * - `PUT` the same path: 412 when `If-Match` names another ETag (or there is no backup yet); 409
 *   when the folder was not made; else it stores the bytes under a new ETag and answers 201
 *   (created) or 204 (replaced).
 *
 * **SyncYomi** at [clientHost] (the app's "Client host" setting), checked by `X-API-Token`
 * ([apiKey]; 401 otherwise):
 * - `GET /api/sync/content` and `PUT /api/sync/content`, with the WebDAV GET and PUT semantics,
 *   except that a PUT answers 200. Every 2xx answer carries the ETag, which the app requires.
 * - `POST /api/sync/event`: 200, and the JSON body goes to [events].
 *
 * The app sends the ETag back as it got it (SyncYomi) or without its quotes (WebDAV): both match.
 *
 * To put another device's backup on the remote, encode a `Backup` as the sync services do, plain
 * protobuf (not [eu.kanade.tachiyomi.data.backup.BackupCodec.write], which gzips it):
 * ```
 * val sync = FixtureSyncServer(server)
 * sync.seed(BackupCodec.encode(Backup(backupManga = listOf(otherDevicesManga))))
 * // then in the app: WebDAV URL = sync.webDavUrl, user "fixture", password "secret", and "Sync now"
 * ```
 * The app's ETag is in `SyncPreferences.lastSyncEtag()`; after a seed it no longer matches, so the
 * app's next pull gets the seeded backup.
 */
class FixtureSyncServer(
    private val server: FixtureServer,
    val user: String = "fixture",
    val password: String = "secret",
    val apiKey: String = "fixture-key",
    /** Where WebDAV serves backup.proto; the app's folder setting defaults to "komikku". */
    val davFolder: String = "komikku",
) : AutoCloseable {

    /** A request the fake answered: what the app sent and the status it got. */
    data class Call(
        val method: String,
        val path: String,
        val status: Int,
        val ifMatch: String?,
        val ifNoneMatch: String?,
    )

    private val lock = Any()
    private var backup: ByteArray? = null
    private var version = 0

    /** The WebDAV collections that exist, as paths under /dav without slashes at the ends ("" is /dav). */
    private val collections = mutableSetOf("")

    /** Every request the sync routes answered, in order. */
    val calls: MutableList<Call> = CopyOnWriteArrayList()

    /** The JSON bodies of the SyncYomi events (`POST /api/sync/event`), in order. */
    val events: MutableList<String> = CopyOnWriteArrayList()

    val webDavUrl: String get() = "${server.baseUrl}/dav"
    val clientHost: String get() = server.baseUrl

    /** A copy of the remote backup's bytes, or null while there is none. */
    val remoteBackup: ByteArray? get() = synchronized(lock) { backup?.copyOf() }

    /** The remote backup's ETag without quotes ("v1"), or null while there is none. */
    val etag: String? get() = synchronized(lock) { currentEtag() }

    /**
     * Puts [bytes] on the remote under a new ETag, as another device's push does. The WebDAV
     * folder counts as made.
     */
    fun seed(bytes: ByteArray) {
        synchronized(lock) {
            store(bytes)
            folderChain(davFolder).forEach { collections += it }
        }
    }

    private val davRoute: (HttpExchange, List<String>) -> Boolean = route@{ exchange, parts ->
        if (parts.firstOrNull() != "dav") return@route false
        if (!basicAuthOk(exchange)) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fixture\"")
            answer(exchange, 401, "text/plain", "Unauthorized".toByteArray())
        } else {
            dav(exchange, parts.drop(1).filter { it.isNotEmpty() })
        }
        true
    }

    private val syncYomiRoute: (HttpExchange, List<String>) -> Boolean = route@{ exchange, parts ->
        if (parts.size != 3 || parts[0] != "api" || parts[1] != "sync" || parts[2] !in setOf("content", "event")) {
            return@route false
        }
        when {
            exchange.requestHeaders.getFirst("X-API-Token") != apiKey ->
                answer(exchange, 401, "text/plain", "Invalid API token".toByteArray())
            parts[2] == "event" -> if (exchange.requestMethod == "POST") {
                events += exchange.requestBody.readAllBytes().decodeToString()
                answer(exchange, 200, "application/json", "{}".toByteArray())
            } else {
                notAllowed(exchange, "POST")
            }
            else -> when (exchange.requestMethod) {
                "GET" -> get(exchange)
                "PUT" -> put(exchange, createdStatus = 200, replacedStatus = 200)
                else -> notAllowed(exchange, "GET, PUT")
            }
        }
        true
    }

    init {
        server.extraRoutes += davRoute
        server.extraRoutes += syncYomiRoute
    }

    private fun dav(exchange: HttpExchange, segments: List<String>): Int {
        val path = segments.joinToString("/")
        val file = if (davFolder.isEmpty()) BACKUP_FILE else "${davFolder.trim('/')}/$BACKUP_FILE"
        return when (exchange.requestMethod) {
            "MKCOL" -> synchronized(lock) {
                when {
                    path in collections -> notAllowed(exchange, "GET, PUT")
                    path.substringBeforeLast('/', "") !in collections -> answer(exchange, 409, "text/plain", "No parent collection".toByteArray())
                    else -> {
                        collections += path
                        answer(exchange, 201)
                    }
                }
            }
            "GET" -> if (path == file) get(exchange) else answer(exchange, 404, "text/plain", "Not found".toByteArray())
            "PUT" -> when {
                path != file -> answer(exchange, 403, "text/plain", "Only $file is writable".toByteArray())
                synchronized(lock) { file.substringBeforeLast('/', "") !in collections } ->
                    answer(exchange, 409, "text/plain", "No parent collection".toByteArray())
                else -> put(exchange, createdStatus = 201, replacedStatus = 204)
            }
            else -> notAllowed(exchange, "MKCOL, GET, PUT")
        }
    }

    /** The GET both services share: 404, 304 or 200 with the bytes, each 2xx and 304 with the ETag. */
    private fun get(exchange: HttpExchange): Int {
        val (bytes, tag) = synchronized(lock) { backup to currentEtag() }
        if (bytes == null || tag == null) return answer(exchange, 404, "text/plain", "No backup".toByteArray())
        val etagHeader = mapOf("ETag" to quoted(tag))
        if (matches(exchange.requestHeaders.getFirst("If-None-Match"), tag)) {
            return answer(exchange, 304, headers = etagHeader)
        }
        return answer(exchange, 200, "application/octet-stream", bytes, etagHeader)
    }

    /** The PUT both services share: 412 on a stale `If-Match`, else stored under a new ETag. */
    private fun put(exchange: HttpExchange, createdStatus: Int, replacedStatus: Int): Int {
        val body = exchange.requestBody.readAllBytes()
        val ifMatch = exchange.requestHeaders.getFirst("If-Match")
        val result = synchronized(lock) {
            val current = currentEtag()
            if (ifMatch != null && (current == null || !matches(ifMatch, current))) {
                null
            } else {
                val created = backup == null
                store(body)
                (if (created) createdStatus else replacedStatus) to currentEtag()!!
            }
        }
        result ?: return answer(exchange, 412, "text/plain", "ETag mismatch".toByteArray())
        val (status, tag) = result
        return answer(exchange, status, headers = mapOf("ETag" to quoted(tag)))
    }

    private fun store(bytes: ByteArray) {
        backup = bytes.copyOf()
        version++
    }

    private fun currentEtag(): String? = if (backup == null) null else "v$version"

    private fun basicAuthOk(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { Base64.getDecoder().decode(header.substring(6).trim()).toString(Charsets.ISO_8859_1) }
            .getOrNull() ?: return false
        return decoded == "$user:$password"
    }

    private fun notAllowed(exchange: HttpExchange, allow: String): Int {
        exchange.responseHeaders.add("Allow", allow)
        return answer(exchange, 405, "text/plain", "Method not allowed".toByteArray())
    }

    /** Records the call before [respond] flushes the HTTP response so callers never observe the response first. */
    private fun answer(
        exchange: HttpExchange,
        code: Int,
        type: String? = null,
        bytes: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ): Int {
        record(exchange, code)
        return respond(exchange, code, type, bytes, headers)
    }

    private fun record(exchange: HttpExchange, status: Int) {
        calls += Call(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            status = status,
            ifMatch = exchange.requestHeaders.getFirst("If-Match"),
            ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match"),
        )
    }

    override fun close() {
        server.extraRoutes -= davRoute
        server.extraRoutes -= syncYomiRoute
    }

    private companion object {
        const val BACKUP_FILE = "backup.proto"

        fun quoted(tag: String) = "\"$tag\""

        /** Whether an If-Match or If-None-Match header names [tag]: `*`, or one of its ETags, quoted or not. */
        fun matches(header: String?, tag: String): Boolean {
            if (header.isNullOrBlank()) return false
            return header.split(',').map { it.trim().removePrefix("W/").trim('"') }.any { it == "*" || it == tag }
        }

        /** "a/b" -> ["a", "a/b"]: the collections a folder setting needs. */
        fun folderChain(folder: String): List<String> = folder.trim('/').split('/').filter { it.isNotEmpty() }
            .runningReduce { acc, part -> "$acc/$part" }
    }
}

/**
 * Answers [exchange] with [code], the [headers] and [bytes] (none when empty), for the fixture
 * route sets. Returns [code].
 */
internal fun respond(
    exchange: HttpExchange,
    code: Int,
    type: String? = null,
    bytes: ByteArray = ByteArray(0),
    headers: Map<String, String> = emptyMap(),
): Int {
    type?.let { exchange.responseHeaders.add("Content-Type", it) }
    headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
    // -1 is "no body" for HttpServer; 0 would mean a chunked body
    exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
    return code
}
