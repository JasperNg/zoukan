package zoukan.desktop.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.connection.RealCall
import okio.Buffer
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.net.URLDecoder
import java.util.Collections

/**
 * An OkHttp client that answers every call itself, from recorded answers, and records each request.
 *
 * The hosted trackers' addresses are constants in the shared Android code, so their tests cannot
 * point them at a fixture server. This client stands in for the network instead: nothing it is
 * given ever reaches a socket.
 *
 * The tracker APIs add their own auth interceptor to the client they are given
 * (`client.newBuilder().addInterceptor(auth)`), which puts it after this one. So that the
 * recorded request is the one that would go out, with its Authorization header, this client
 * first runs the interceptors that come after it in the calling client, then answers.
 */
internal class RecordedHttp {

    /** A request the client received, after every application interceptor ran. */
    class Call(val method: String, val url: HttpUrl, val headers: Headers, val body: String) {
        val path: String get() = url.encodedPath

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

        fun jsonArray(): JsonArray = Json.parseToJsonElement(body).jsonArray

        /** The fields of a form-encoded body. */
        fun form(): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }

        override fun toString() = "$method $url"
    }

    private class Answer(val code: Int, val body: String, val headers: Map<String, String>)

    private class Route(val method: String, val path: String, val answer: Answer)

    private val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    private val queue = ArrayDeque<Answer>()
    private val routes = mutableListOf<Route>()

    /** Requests no answer was given for. They get a 500; a test checks this is empty. */
    val unexpected: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    val requests: List<Call> get() = synchronized(calls) { calls.toList() }

    /** Answers the next request that no route matches. Answers are used in order, once each. */
    fun answer(body: String = "", code: Int = 200, headers: Map<String, String> = emptyMap()) {
        synchronized(queue) { queue.addLast(Answer(code, body, headers)) }
    }

    /** Answers every [method] request to [path] (the URL's encoded path), for calls made in parallel. */
    fun on(method: String, path: String, body: String = "", code: Int = 200, headers: Map<String, String> = emptyMap()) {
        synchronized(routes) { routes += Route(method, path, Answer(code, body, headers)) }
    }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Terminal()).build()

    private inner class Terminal : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val interceptors = (chain.call() as RealCall).client.interceptors
            val later = interceptors.dropWhile { it !== this }.drop(1)
            return runFrom(chain, later, 0, chain.request())
        }
    }

    /** Runs [later] from [index] with [request], then answers: never the real network. */
    private fun runFrom(chain: Interceptor.Chain, later: List<Interceptor>, index: Int, request: Request): Response {
        if (index == later.size) return respond(request)
        val next = object : Interceptor.Chain by chain {
            override fun request(): Request = request

            override fun proceed(request: Request): Response = runFrom(chain, later, index + 1, request)
        }
        return later[index].intercept(next)
    }

    private fun respond(request: Request): Response {
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val call = Call(request.method, request.url, request.headers, body)
        calls += call
        val answer = synchronized(routes) { routes.firstOrNull { it.method == call.method && it.path == call.path }?.answer }
            ?: synchronized(queue) { queue.removeFirstOrNull() }
            ?: run {
                unexpected += call
                Answer(500, """{"error":"no recorded answer"}""", emptyMap())
            }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message(if (answer.code in 200..299) "OK" else "Error")
            .headers(answer.headers.toHeaders())
            .body(answer.body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** Every request was answered, went over HTTPS and carries none of [secrets] in its URL. */
    fun checkAllAnswered(vararg secrets: String, https: Boolean = true) {
        unexpected.shouldBeEmpty()
        requests.forEach { call ->
            if (https) check(call.url.isHttps) { "Not HTTPS: $call" }
            secrets.forEach { call.url.toString() shouldNotContain it }
        }
    }
}

/** A [PreferenceStore] in memory, whose values a test can read back. */
internal class MapPreferenceStore : PreferenceStore {
    private val values = MutableStateFlow<Map<String, Any?>>(emptyMap())

    private inner class MapPreference<T>(private val key: String, private val default: T) : Preference<T> {
        override fun key() = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = if (key in values.value) values.value[key] as T else default

        override fun set(value: T) {
            values.value += (key to value)
        }

        override fun isSet() = key in values.value

        override fun delete() {
            values.value -= key
        }

        override fun defaultValue() = default

        override fun changes(): Flow<T> = values.map { get() }

        override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

    override fun getString(key: String, defaultValue: String): Preference<String> = MapPreference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = MapPreference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = MapPreference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = MapPreference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = MapPreference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = MapPreference(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = MapPreference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = MapPreference(key, defaultValue)

    override fun getAll(): Map<String, *> = values.value
}

/**
 * What a hosted tracker needs to run in a unit test: registers the app's Json, track preferences
 * in memory, and a NetworkHelper whose client is [http]'s into the test's [Injekt] scope.
 * Tests pair this with `FreshInjekt` so nothing registered here leaks into other tests.
 */
internal class TrackerTestEnvironment {
    val http = RecordedHttp()
    val preferences = TrackPreferences(MapPreferenceStore())

    fun register() {
        // What the app registers (DesktopAppModule)
        Injekt.addSingleton(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
        Injekt.addSingleton(preferences)
        Injekt.addSingleton<NetworkHelper>(mockk { every { client } returns http.client })
    }
}
