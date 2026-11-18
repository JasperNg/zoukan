package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The coroutine bridge every source and the app's own requests go through, and the two
 * interceptors that keep a misbehaving request from crashing the app. No network: an interceptor
 * answers every request.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CallAwaitTest {

    private val clients = mutableListOf<OkHttpClient>()

    @AfterEach
    fun shutDown() {
        clients.forEach { it.dispatcher.executorService.shutdownNow() }
    }

    private fun client(vararg interceptors: Interceptor) = OkHttpClient.Builder()
        .apply { interceptors.forEach { addInterceptor(it) } }
        .build()
        .also { clients += it }

    private fun answer(code: Int, body: String = "", closed: AtomicBoolean? = null) = Interceptor { chain ->
        val source = object : ForwardingSource(Buffer().writeUtf8(body)) {
            override fun close() {
                closed?.set(true)
                super.close()
            }
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("status $code")
            .body(source.buffer().asResponseBody(null, body.length.toLong()))
            .build()
    }

    private fun call(client: OkHttpClient): Call = client.newCall(Request.Builder().url("https://example.org/a").build())

    @Test
    fun `awaitSuccess returns a successful response with its body`() = runBlocking<Unit> {
        call(client(answer(200, "hello"))).awaitSuccess().use { it.body.string() shouldBe "hello" }
    }

    @Test
    fun `awaitSuccess turns an error status into an HttpException and closes the body`() = runBlocking<Unit> {
        val closed = AtomicBoolean(false)
        val error = shouldThrow<HttpException> { call(client(answer(503, "busy", closed))).awaitSuccess() }
        error.code shouldBe 503
        closed.get() shouldBe true
        // await alone hands the error response to the caller
        call(client(answer(404))).await().use { it.code shouldBe 404 }
    }

    @Test
    fun `a network failure reaches the caller as an IOException with its message`() = runBlocking<Unit> {
        val error = shouldThrow<IOException> {
            call(client(Interceptor { throw IOException("connection reset") })).await()
        }
        error.message shouldBe "connection reset"
        error.cause!!.message shouldBe "connection reset"
    }

    @Test
    fun `cancelling the coroutine cancels the call and does not wait for it`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = Interceptor { chain ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            chain.proceed(chain.request())
        }
        val call = call(client(blocking, answer(200)))
        // Started at once, so the call is enqueued before this thread waits for the interceptor
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        started.await(10, TimeUnit.SECONDS) shouldBe true
        withTimeout(5_000) {
            pending.cancel()
            shouldThrow<CancellationException> { pending.await() }
        }
        call.isCanceled() shouldBe true
        release.countDown()
    }

    @Test
    fun `an unexpected exception in a later interceptor arrives as an IOException`() = runBlocking<Unit> {
        // Without it, the exception would end OkHttp's dispatcher thread and the caller would wait forever
        val client = client(UncaughtExceptionInterceptor(), Interceptor { throw IllegalStateException("bug in a source") })
        val error = withTimeout(10_000) { shouldThrow<IOException> { call(client).await() } }
        error.cause!!.cause!!.message shouldContain "bug in a source"
    }

    @Test
    fun `the default user agent is added only when the request has none`() = runBlocking<Unit> {
        var seen: String? = null
        val record = Interceptor { chain ->
            seen = chain.request().header("User-Agent")
            chain.proceed(chain.request())
        }
        val client = client(UserAgentInterceptor { "Default UA" }, record, answer(200))

        client.newCall(Request.Builder().url("https://example.org/").build()).await().close()
        seen shouldBe "Default UA"

        client.newCall(Request.Builder().url("https://example.org/").header("User-Agent", "Source UA").build()).await().close()
        seen shouldBe "Source UA"

        client.newCall(Request.Builder().url("https://example.org/").header("User-Agent", "").build()).await().close()
        seen shouldBe "Default UA"
    }
}
