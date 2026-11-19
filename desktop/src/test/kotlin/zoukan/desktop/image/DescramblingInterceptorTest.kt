package zoukan.desktop.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.PageImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Verifies extension-style image descrambling in an OkHttp interceptor using `android.graphics`
 * (`BitmapFactory`, `Bitmap.createBitmap`, `Canvas.drawBitmap`, `Bitmap.compress`) across JPEG,
 * PNG, and AVIF pages, and verifies non-image responses (HTML) pass through untouched and fail cleanly.
 */
class DescramblingInterceptorTest {

    private val scrambledPages = listOf(
        listOf("scrambled-1.jpg", 60, 180, 170),
        listOf("scrambled-2.png", 170, 60, 160),
        listOf("scrambled-3.avif", 100, 100, 220),
    )

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/e2e-fixtures/images/$name")!!.use { it.readBytes() }

    private fun dominantColorShare(bytes: ByteArray, targetR: Int, targetG: Int, targetB: Int): Double {
        val image = Image.makeFromEncoded(bytes)
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        try {
            var hits = 0
            var total = 0
            for (y in 0 until bitmap.height step 8) {
                for (x in 0 until bitmap.width step 8) {
                    val argb = bitmap.getColor(x, y)
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    if (abs(r - targetR) <= 45 && abs(g - targetG) <= 45 && abs(b - targetB) <= 45) hits++
                    total++
                }
            }
            return hits.toDouble() / total
        } finally {
            bitmap.close()
            image.close()
        }
    }

    private fun descramblingClient(
        descrambledCount: AtomicInteger,
        payloads: Map<String, Pair<String, ByteArray>>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.request.url.pathSegments.last().startsWith("scrambled-")) return@addInterceptor response
            val raw = response.body.bytes()
            val mediaType = response.body.contentType()
            val scrambled = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return@addInterceptor response.newBuilder().body(raw.toResponseBody(mediaType)).build()
            val width = scrambled.width
            val height = scrambled.height
            val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(page).drawBitmap(scrambled, Rect(width / 2, 0, width, height), Rect(0, 0, width, height), null)
            val bytes = ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            descrambledCount.incrementAndGet()
            response.newBuilder().body(bytes.toResponseBody("image/png".toMediaType())).build()
        }
        .addInterceptor { chain: Interceptor.Chain ->
            val request = chain.request()
            val name = request.url.pathSegments.last()
            val (mime, body) = payloads.getValue(name)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(mime.toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `descrambling interceptor restores JPEG, PNG, and AVIF pages and passes HTML through`() {
        val payloads = mutableMapOf<String, Pair<String, ByteArray>>()
        scrambledPages.forEach { entry ->
            val file = entry[0] as String
            val r = entry[1] as Int
            val g = entry[2] as Int
            val b = entry[3] as Int
            val bytes = resource(file)
            payloads[file] = "application/octet-stream" to bytes
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).shouldNotBeNull()
            // Before descrambling, the right half holds the target image while the left half is noise
            val pngBefore = ByteArrayOutputStream().also { raw.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            dominantColorShare(pngBefore, r, g, b) shouldBeLessThan 0.6
        }
        val htmlBody = "<html><body>Not an image</body></html>".toByteArray()
        payloads["scrambled-html.jpg"] = "text/html" to htmlBody

        val descrambledCount = AtomicInteger(0)
        val client = descramblingClient(descrambledCount, payloads)

        scrambledPages.forEach { entry ->
            val file = entry[0] as String
            val r = entry[1] as Int
            val g = entry[2] as Int
            val b = entry[3] as Int
            client.newCall(Request.Builder().url("https://example.invalid/$file").build()).execute().use { resp ->
                resp.isSuccessful shouldBe true
                val outBytes = resp.body.bytes()
                dominantColorShare(outBytes, r, g, b) shouldBeGreaterThan 0.75
                val page = PageImage.decode(outBytes)
                try {
                    page.slices.isNotEmpty() shouldBe true
                } finally {
                    page.release()
                }
            }
        }
        descrambledCount.get() shouldBe scrambledPages.size

        // Non-image HTML response: BitmapFactory returns null, interceptor passes it untouched, PageImage rejects it cleanly
        client.newCall(Request.Builder().url("https://example.invalid/scrambled-html.jpg").build()).execute().use { resp ->
            val outBytes = resp.body.bytes()
            BitmapFactory.decodeByteArray(outBytes, 0, outBytes.size).shouldBeNull()
            outBytes.contentEquals(htmlBody) shouldBe true
            shouldThrow<UnreadableImageException> { PageImage.decode(outBytes) }
        }
        descrambledCount.get() shouldBe scrambledPages.size
    }
}
