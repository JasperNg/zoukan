package zoukan.desktop.network

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

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
