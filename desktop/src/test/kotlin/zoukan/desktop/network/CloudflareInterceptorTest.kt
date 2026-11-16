package zoukan.desktop.network

import eu.kanade.tachiyomi.network.interceptor.CloudflareChallenge
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

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
