package zoukan.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import zoukan.desktop.app.AppVersion
import zoukan.desktop.update.TestReleaseKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub's releases API for [repo] on the fixture [server]: the release list the update check
 * reads, one release by tag (What's new reads its notes) and the release assets (installers and
 * their `.sig` signatures).
 *
 * The app keeps its own address rules: a checker made with [client] talks to
 * `https://api.github.com` as in the shipped app, so UpdateChecker's checks (https, the API host)
 * run unchanged on every address; the client alone carries the requests to the fixture server.
 */
class FixtureReleases(
    private val server: FixtureServer,
    private val repo: String = AppVersion.releaseRepo,
) : AutoCloseable {

    /** One release asset as served: [bytes] are what the download gets. */
    data class Asset(val name: String, val bytes: ByteArray, val sha256: String? = TestReleaseKey.sha256(bytes))

    /** The release list's JSON. */
    @Volatile
    var feed: JsonArray = JsonArray(emptyList())

    /** Releases by tag, for the release-by-tag address. */
    val byTag = ConcurrentHashMap<String, JsonObject>()

    private val assets = ConcurrentHashMap<String, ByteArray>()
    private val nextId = AtomicInteger(1)

    val releasesPath = "/repos/$repo/releases"
    fun tagPath(tag: String) = "$releasesPath/tags/$tag"

    private val route: (HttpExchange, List<String>) -> Boolean = route@{ exchange, _ ->
        val path = exchange.requestURI.path
        val body = when {
            path == releasesPath -> feed.toString().toByteArray()
            path.startsWith("$releasesPath/tags/") -> byTag[path.removePrefix("$releasesPath/tags/")]?.toString()?.toByteArray()
            path.startsWith("$releasesPath/assets/") -> assets[path.removePrefix("$releasesPath/assets/")]
            else -> return@route false
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
        } else {
            exchange.responseHeaders.add("Content-Type", if (path.contains("/assets/")) "application/octet-stream" else "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        true
    }

    init {
        server.extraRoutes += route
    }

    /**
     * A release as GitHub lists it, with [assets] served at asset addresses on the API host, as
     * GitHub's `url` field gives them.
     */
    fun release(tag: String, name: String, notes: String, prerelease: Boolean = false, assets: List<Asset> = emptyList()): JsonObject =
        buildJsonObject {
            put("tag_name", tag)
            put("name", name)
            put("body", notes)
            put("draft", false)
            put("prerelease", prerelease)
            put("html_url", "https://github.com/$repo/releases/tag/$tag")
            putJsonArray("assets") {
                assets.forEach { asset ->
                    val id = nextId.getAndIncrement().toString()
                    this@FixtureReleases.assets[id] = asset.bytes
                    add(
                        buildJsonObject {
                            put("name", asset.name)
                            put("url", "$API$releasesPath/assets/$id")
                            put("size", asset.bytes.size)
                            asset.sha256?.let { put("digest", "sha256:$it") }
                        },
                    )
                }
            }
        }

    /** Lists [releases] as the release list and by their tags. */
    fun publish(vararg releases: JsonObject) {
        feed = buildJsonArray { releases.forEach { add(it) } }
        releases.forEach { byTag[it["tag_name"].toString().trim('"')] = it }
    }

    /** [base] with the requests to `https://api.github.com` sent to the fixture server instead. */
    fun client(base: OkHttpClient): OkHttpClient {
        val fixture = server.baseUrl.toHttpUrl()
        return base.newBuilder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.host == API.toHttpUrl().host && url.isHttps) {
                val local = url.newBuilder().scheme("http").host(fixture.host).port(fixture.port).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            } else {
                chain.proceed(chain.request())
            }
        }.build()
    }

    override fun close() {
        server.extraRoutes -= route
    }

    companion object {
        const val API = "https://api.github.com"
    }
}
