package zoukan.desktop.e2e

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import zoukan.desktop.backup.SyntheticBackup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The WebDAV and SyncYomi protocol [FixtureSyncServer] speaks, as the app's sync services
 * (WebDavSyncService, SyncYomiSyncService) expect it. Plain HTTP against the server, no app runtime.
 */
@OptIn(ExperimentalSerializationApi::class)
class FixtureSyncServerTest {

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    // Unit, so JUnit runs the expression-bodied tests (it skips test methods that return a value)
    private fun withSync(block: (FixtureServer, FixtureSyncServer) -> Unit) = FixtureServer().use { server ->
        FixtureSyncServer(server).use { block(server, it) }
    }

    private fun FixtureServer.call(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(Duration.ofSeconds(30))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun basic(user: String, password: String) =
        mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))

    private val auth = basic("fixture", "secret")
    private val token = mapOf("X-API-Token" to "fixture-key")
    private val davFile = "/dav/komikku/backup.proto"

    private val HttpResponse<ByteArray>.etag: String? get() = headers().firstValue("ETag").orElse(null)

    // WebDAV

    @Test
    fun `WebDAV without or with wrong credentials answers 401`() = withSync { server, sync ->
        val none = server.call("GET", davFile)
        none.statusCode() shouldBe 401
        none.headers().firstValue("WWW-Authenticate").orElse("") shouldContain "Basic"
        server.call("MKCOL", "/dav/komikku", headers = basic("fixture", "wrong")).statusCode() shouldBe 401
        server.call("PUT", davFile, "x".toByteArray(), basic("other", "secret")).statusCode() shouldBe 401
        sync.remoteBackup shouldBe null
    }

    @Test
    fun `WebDAV MKCOL makes a folder once, and needs its parent`() = withSync { server, _ ->
        server.call("MKCOL", "/dav/komikku", headers = auth).statusCode() shouldBe 201
        server.call("MKCOL", "/dav/komikku", headers = auth).statusCode() shouldBe 405
        server.call("MKCOL", "/dav/a/b", headers = auth).statusCode() shouldBe 409
        server.call("MKCOL", "/dav/a", headers = auth).statusCode() shouldBe 201
        server.call("MKCOL", "/dav/a/b", headers = auth).statusCode() shouldBe 201
    }

    @Test
    fun `WebDAV GET with no backup answers 404`() = withSync { server, sync ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("GET", davFile, headers = auth).statusCode() shouldBe 404
        sync.etag shouldBe null
    }

    @Test
    fun `WebDAV PUT before the folder exists answers 409`() = withSync { server, sync ->
        server.call("PUT", davFile, "x".toByteArray(), auth).statusCode() shouldBe 409
        sync.remoteBackup shouldBe null
    }

    @Test
    fun `WebDAV PUT, GET and If-None-Match follow the ETag`() = withSync { server, sync ->
        val bytes = "first backup".toByteArray()
        server.call("MKCOL", "/dav/komikku", headers = auth)

        val put = server.call("PUT", davFile, bytes, auth)
        put.statusCode() shouldBe 201
        put.etag shouldBe "\"v1\""
        sync.etag shouldBe "v1"

        val get = server.call("GET", davFile, headers = auth)
        get.statusCode() shouldBe 200
        get.body().toList() shouldBe bytes.toList()
        get.etag shouldBe "\"v1\""

        // The app sends the ETag back without its quotes
        val unchanged = server.call("GET", davFile, headers = auth + ("If-None-Match" to "v1"))
        unchanged.statusCode() shouldBe 304
        unchanged.body().size shouldBe 0
        server.call("GET", davFile, headers = auth + ("If-None-Match" to "\"v1\"")).statusCode() shouldBe 304
        server.call("GET", davFile, headers = auth + ("If-None-Match" to "v0")).statusCode() shouldBe 200
    }

    @Test
    fun `WebDAV PUT with a stale If-Match answers 412 and keeps the backup`() = withSync { server, sync ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("PUT", davFile, "mine".toByteArray(), auth)

        server.call("PUT", davFile, "stale".toByteArray(), auth + ("If-Match" to "v0")).statusCode() shouldBe 412
        sync.remoteBackup!!.decodeToString() shouldBe "mine"
        sync.etag shouldBe "v1"

        val replaced = server.call("PUT", davFile, "newer".toByteArray(), auth + ("If-Match" to "v1"))
        replaced.statusCode() shouldBe 204
        replaced.etag shouldBe "\"v2\""
        sync.remoteBackup!!.decodeToString() shouldBe "newer"
    }

    @Test
    fun `WebDAV PUT with If-Match and no backup answers 412`() = withSync { server, sync ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("PUT", davFile, "x".toByteArray(), auth + ("If-Match" to "v1")).statusCode() shouldBe 412
        sync.remoteBackup shouldBe null
    }

    @Test
    fun `a seed is another device's push - a new ETag and a Backup the app decodes`() = withSync { server, sync ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("PUT", davFile, "mine".toByteArray(), auth)
        val backup = SyntheticBackup.create()

        sync.seed(BackupCodec.encode(backup))
        sync.etag shouldBe "v2"

        val get = server.call("GET", davFile, headers = auth + ("If-None-Match" to "v1"))
        get.statusCode() shouldBe 200
        get.etag shouldBe "\"v2\""
        // As WebDavSyncService and SyncYomiSyncService read it
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), get.body())
        decoded.backupManga.size shouldBe backup.backupManga.size
        server.call("PUT", davFile, "stale".toByteArray(), auth + ("If-Match" to "v1")).statusCode() shouldBe 412
    }

    @Test
    fun `a seed makes the WebDAV folder`() = withSync { server, sync ->
        sync.seed("seeded".toByteArray())
        sync.etag shouldBe "v1"
        server.call("MKCOL", "/dav/komikku", headers = auth).statusCode() shouldBe 405
        server.call("GET", davFile, headers = auth).body().decodeToString() shouldBe "seeded"
    }

    @Test
    fun `WebDAV serves only the backup file`() = withSync { server, _ ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("GET", "/dav/komikku/other.proto", headers = auth).statusCode() shouldBe 404
        server.call("PUT", "/dav/komikku/other.proto", "x".toByteArray(), auth).statusCode() shouldBe 403
        server.call("PROPFIND", davFile, headers = auth).statusCode() shouldBe 405
    }

    @Test
    fun `the calls are recorded with their status and preconditions`() = withSync { server, sync ->
        server.call("MKCOL", "/dav/komikku", headers = auth)
        server.call("GET", davFile, headers = auth)
        server.call("PUT", davFile, "x".toByteArray(), auth)
        server.call("PUT", davFile, "y".toByteArray(), auth + ("If-Match" to "v9"))

        sync.calls shouldContainExactly listOf(
            FixtureSyncServer.Call("MKCOL", "/dav/komikku", 201, null, null),
            FixtureSyncServer.Call("GET", davFile, 404, null, null),
            FixtureSyncServer.Call("PUT", davFile, 201, null, null),
            FixtureSyncServer.Call("PUT", davFile, 412, "v9", null),
        )
    }

    // SyncYomi

    @Test
    fun `SyncYomi with a wrong token answers 401`() = withSync { server, sync ->
        val wrong = mapOf("X-API-Token" to "wrong")
        server.call("GET", "/api/sync/content", headers = wrong).statusCode() shouldBe 401
        server.call("GET", "/api/sync/content").statusCode() shouldBe 401
        server.call("PUT", "/api/sync/content", "x".toByteArray(), wrong).statusCode() shouldBe 401
        server.call("POST", "/api/sync/event", "{}".toByteArray(), wrong).statusCode() shouldBe 401
        sync.remoteBackup shouldBe null
        sync.events shouldBe emptyList()
    }

    @Test
    fun `SyncYomi content follows the ETag`() = withSync { server, sync ->
        server.call("GET", "/api/sync/content", headers = token).statusCode() shouldBe 404

        val put = server.call("PUT", "/api/sync/content", "first".toByteArray(), token)
        put.statusCode() shouldBe 200
        put.etag shouldBe "\"v1\""

        val get = server.call("GET", "/api/sync/content", headers = token)
        get.statusCode() shouldBe 200
        get.body().decodeToString() shouldBe "first"

        // SyncYomiSyncService sends the ETag back as it got it, quotes and all
        server.call("GET", "/api/sync/content", headers = token + ("If-None-Match" to "\"v1\"")).statusCode() shouldBe 304
        server.call("PUT", "/api/sync/content", "stale".toByteArray(), token + ("If-Match" to "\"v0\"")).statusCode() shouldBe 412
        sync.remoteBackup!!.decodeToString() shouldBe "first"

        sync.seed("other device".toByteArray())
        sync.etag shouldBe "v2"
        val pulled = server.call("GET", "/api/sync/content", headers = token + ("If-None-Match" to "\"v1\""))
        pulled.statusCode() shouldBe 200
        pulled.body().decodeToString() shouldBe "other device"
        server.call("PUT", "/api/sync/content", "merged".toByteArray(), token + ("If-Match" to "\"v2\"")).etag shouldBe "\"v3\""
    }

    @Test
    fun `SyncYomi 2xx answers each carry an ETag`() = withSync { server, _ ->
        // The app throws "Missing ETag" on a 2xx without one
        val put = server.call("PUT", "/api/sync/content", "first".toByteArray(), token)
        put.statusCode() shouldBe 200
        put.etag shouldBe "\"v1\""

        val get = server.call("GET", "/api/sync/content", headers = token)
        get.statusCode() shouldBe 200
        get.etag shouldBe "\"v1\""
    }

    @Test
    fun `SyncYomi events are recorded`() = withSync { server, sync ->
        val event = """{"event":"SYNC_STARTED","device_name":"desktop","message":null}"""
        val response = server.call("POST", "/api/sync/event", event.toByteArray(), token + ("Content-Type" to "application/json"))
        response.statusCode() shouldBe 200
        sync.events shouldContainExactly listOf(event)
    }

    @Test
    fun `after close the sync routes are gone`() {
        FixtureServer().use { server ->
            FixtureSyncServer(server).close()
            server.extraRoutes.size shouldBe 0
            server.call("GET", "/api/sync/content", headers = token).statusCode() shouldBe 404
            server.call("GET", davFile, headers = auth).statusCode() shouldBe 404
        }
    }
}
