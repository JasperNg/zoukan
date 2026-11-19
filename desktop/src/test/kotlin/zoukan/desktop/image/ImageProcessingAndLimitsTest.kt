package zoukan.desktop.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.FileSystem
import okio.buffer
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import tachiyomi.decoder.ImageFormats
import zoukan.desktop.compat.localized
import zoukan.desktop.download.ImageType
import zoukan.desktop.ui.reader.PageImage
import zoukan.desktop.ui.reader.PageImages
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs

/** The one sniffer that downloads, the reader, the AVIF/HEIF decoder and the extension decoder share. */
class ImageFormatsTest {
    /** An ftyp box with [major] and [compatible] brands, then some bytes of a following box. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        val box = ByteArray(size + 8)
        box[3] = size.toByte()
        "ftyp".forEachIndexed { i, c -> box[4 + i] = c.code.toByte() }
        major.forEachIndexed { i, c -> box[8 + i] = c.code.toByte() }
        compatible.forEachIndexed { b, brand -> brand.forEachIndexed { i, c -> box[16 + 4 * b + i] = c.code.toByte() } }
        // The next box's header, which must not be read as a brand
        "\u0000\u0000\u0000\u0010avif".forEachIndexed { i, c -> box[size + i] = c.code.toByte() }
        return box
    }

    @Test
    fun `AVIF and HEIF by their major brand`() {
        ImageFormats.sniff(ftyp("avif", "mif1")) shouldBe Format.Avif
        ImageFormats.sniff(ftyp("avis", "msf1")) shouldBe Format.Avif
        listOf("heic", "heix", "heim", "heis", "hevc", "hevx").forEach { ImageFormats.sniff(ftyp(it, "mif1")) shouldBe Format.Heif }
    }

    @Test
    fun `AVIF and HEIF by a compatible brand when the major brand is generic`() {
        ImageFormats.sniff(ftyp("mif1", "mif1", "miaf", "avif")) shouldBe Format.Avif
        ImageFormats.sniff(ftyp("msf1", "msf1", "avis")) shouldBe Format.Avif
        ImageFormats.sniff(ftyp("mif1", "mif1", "heic")) shouldBe Format.Heif
        // avif wins over heic among compatible brands
        ImageFormats.sniff(ftyp("mif1", "heic", "avif")) shouldBe Format.Avif
        // A generic image file with no codec brand is HEIF, as before
        ImageFormats.sniff(ftyp("mif1", "mif1")) shouldBe Format.Heif
        // Brands past the ftyp box do not count
        ImageFormats.sniff(ftyp("mp42", "isom")) shouldBe null
        ImageFormats.sniff(ftyp("isom")) shouldBe null
    }

    @Test
    fun `all callers agree`() {
        val avif = ftyp("mif1", "miaf", "avif")
        ImageType.fromBytes(avif) shouldBe ImageType.AVIF
        FfmpegImageDecoder.handles(avif) shouldBe true
        ImageDecoder.findType(avif)?.format shouldBe Format.Avif
        val heif = ftyp("mif1", "heic")
        ImageType.fromBytes(heif) shouldBe ImageType.HEIF
        FfmpegImageDecoder.handles(heif) shouldBe true
        ImageType.fromBytes(ftyp("mp42", "isom")) shouldBe null
        FfmpegImageDecoder.handles(ftyp("mp42", "isom")) shouldBe false
    }

    @Test
    fun `other formats and short input`() {
        ImageFormats.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) shouldBe Format.Jpeg
        ImageFormats.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) shouldBe Format.Png
        ImageFormats.sniff("GIF89a".toByteArray()) shouldBe Format.Gif
        ImageFormats.sniff("RIFF0000WEBPVP8 ".toByteArray()) shouldBe Format.Webp
        ImageFormats.sniff(byteArrayOf(0xFF.toByte(), 0x0A)) shouldBe Format.Jxl
        ImageFormats.sniff(byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)) shouldBe Format.Jxl
        ImageFormats.sniff(ByteArray(0)) shouldBe null
        ImageFormats.sniff("....ftyp".toByteArray()) shouldBe null
        ImageFormats.sniff("<html>".toByteArray()) shouldBe null
    }

    @Test
    fun `text bodies are told from unknown binary ones`() {
        ImageType.looksLikeText("<!DOCTYPE html><html><body>Login</body></html>".toByteArray()) shouldBe true
        ImageType.looksLikeText("﻿{\"error\":\"rate limited\"}\r\n".toByteArray()) shouldBe true
        ImageType.looksLikeText("Ceci n'est pas une image: éè".toByteArray()) shouldBe true
        // A BMP, which the sniffer does not know, has control bytes
        ImageType.looksLikeText(byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x36, 0, 0, 0, 0, 0)) shouldBe false
        ImageType.looksLikeText(ByteArray(0)) shouldBe false
    }
}

/**
 * Images from outside (covers, page previews, a picked cover, pages to combine) that declare an
 * absurd size are an ordinary error with a message, found from the header before anything that
 * size is allocated, with the reader's bounds.
 */
class ImageSizeLimitTest {

    /** A real 16 by 16 JPEG whose frame header is rewritten to declare [width] by [height]. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", out)
        val bytes = out.toByteArray()
        val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
        bytes[sof + 5] = (height shr 8).toByte()
        bytes[sof + 6] = height.toByte()
        bytes[sof + 7] = (width shr 8).toByte()
        bytes[sof + 8] = width.toByte()
        return bytes
    }

    private val absurd get() = jpeg(60_000, 60_000)

    @Test
    fun `an absurd declared size is an error with the image's message`() {
        val error = shouldThrow<UnreadableImageException> { checkImageSize(absurd) }
        error.message shouldBe localized(DMR.strings.the_image_is_too_large)
        checkImageSize(jpeg(1_000, 1_500)) shouldBe (1_000 to 1_500)
        // A tall strip shows as a cover or preview as it does in the reader
        checkImageSize(jpeg(1_080, 65_500)) shouldBe (1_080 to 65_500)
        checkImageSize("not an image".toByteArray()) shouldBe null
    }

    private fun fetched(bytes: ByteArray) = SourceFetchResult(
        source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    @Test
    fun `covers and previews with an absurd size fail before Coil decodes them`() {
        val factory = DeclaredSizeCheck()
        shouldThrow<UnreadableImageException> { factory.create(fetched(absurd), mockk<Options>(relaxed = true), mockk(relaxed = true)) }
        // An ordinary image goes on to the next decoder, its bytes still all there
        val page = jpeg(16, 16)
        val result = fetched(page)
        factory.create(result, mockk<Options>(relaxed = true), mockk(relaxed = true)) shouldBe null
        result.source.source().readByteArray().size shouldBe page.size
    }

    @Test
    fun `combining or copying a page with an absurd size is an error, not a decode`() {
        shouldThrow<UnreadableImageException> { PageImages.combine(absurd, jpeg(16, 16), leftFirst = true, background = 0) }
        shouldThrow<UnreadableImageException> { PageImages.combine(jpeg(16, 16), absurd, leftFirst = true, background = 0) }
    }

    /** Noise of [width] by [height] in [format], so it does not compress below the prefix. */
    private fun noise(format: String, width: Int = 400, height: Int = 400): ByteArray {
        val random = java.util.Random(1)
        val type = if (format == "gif") BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()
    }

    /** A source over [bytes] that counts what is pulled from it. */
    private class Counted(bytes: ByteArray) {
        var pulled = 0L
        val source: okio.BufferedSource = object : okio.ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) pulled += it }
        }.buffer()
    }

    @Test
    fun `a cover's size is found from a prefix of its bytes, and the source is left unread`() {
        for (format in listOf("jpeg", "png", "gif", "bmp")) {
            val bytes = noise(format)
            (bytes.size > 64 * 1024) shouldBe true
            val counted = Counted(bytes)

            checkImageSize(counted.source, prefixBytes = 16 * 1024) shouldBe (400 to 400)

            // Far less than the whole image was read, and every byte is still there for the decoder
            (counted.pulled < bytes.size / 2) shouldBe true
            counted.source.readByteArray().contentEquals(bytes) shouldBe true
        }
    }

    @Test
    fun `a size the prefix does not hold is found from the whole image`() {
        // A JPEG whose frame header comes after a large comment segment, past the prefix
        val plain = jpeg(1_000, 1_500)
        val comment = ByteArray(40_000) { 'x'.code.toByte() }
        val com = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ((comment.size + 2) shr 8).toByte(), (comment.size + 2).toByte()) + comment
        val bytes = plain.copyOfRange(0, 2) + com + plain.copyOfRange(2, plain.size)
        val counted = Counted(bytes)

        checkImageSize(counted.source, prefixBytes = 4 * 1024) shouldBe (1_000 to 1_500)
        counted.source.readByteArray().contentEquals(bytes) shouldBe true
    }

    @Test
    fun `an absurd size found from the prefix still fails`() {
        shouldThrow<UnreadableImageException> { checkImageSize(Counted(absurd).source, prefixBytes = 64) }
    }

    @Test
    fun `Skia is given a prefix only when it holds the whole header`() {
        val png = noise("png")
        holdsWholeHeader(png.copyOf(16 * 1024)) shouldBe true
        holdsWholeHeader(png.copyOf(20)) shouldBe false
        val jpeg = noise("jpeg")
        holdsWholeHeader(jpeg.copyOf(16 * 1024)) shouldBe true
        holdsWholeHeader(jpeg.copyOf(100)) shouldBe false
        holdsWholeHeader(noise("gif").copyOf(64)) shouldBe true
        holdsWholeHeader("not an image".toByteArray()) shouldBe false
    }
}

/** Images in memory are read and written without ImageIO's temporary file cache. */
class MemoryImageIoTest {

    @Test
    fun `reads and writes with ImageIO's file cache on and its folder gone`(@TempDir dir: Path) {
        val tiff = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), "tiff", it) }.toByteArray()
        val useCache = ImageIO.getUseCache()
        val cacheDirectory = ImageIO.getCacheDirectory()
        try {
            ImageIO.setUseCache(true)
            ImageIO.setCacheDirectory(dir.toFile())
            // A file-backed stream would fail now
            dir.toFile().delete()
            val image = MemoryImageIo.read(tiff)!!
            (image.width to image.height) shouldBe (7 to 5)
            val png = MemoryImageIo.png(image)
            MemoryImageIo.read(png)!!.width shouldBe 7
            MemoryImageIo.read("not an image".toByteArray()) shouldBe null
        } finally {
            ImageIO.setUseCache(useCache)
            ImageIO.setCacheDirectory(cacheDirectory)
        }
    }

    @Test
    fun `the PNG written into a byte array is the image, pixel for pixel`() {
        val image = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 200) for (x in 0 until 300) image.setRGB(x, y, (x * 7 + y * 13) and 0xFFFFFF)
        val back = MemoryImageIo.read(MemoryImageIo.png(image))!!
        (back.width to back.height) shouldBe (300 to 200)
        (0 until 200 step 17).all { y -> (0 until 300 step 23).all { x -> back.getRGB(x, y) == image.getRGB(x, y) } } shouldBe true
    }

    @Test
    fun `the byte array output stream seeks back, rewrites and grows`() {
        val stream = ByteArrayImageOutputStream()
        stream.write(ByteArray(100_000) { 1 })
        stream.seek(10)
        stream.writeInt(0x01020304)
        stream.seek(stream.length())
        stream.write(9)
        stream.close()
        val bytes = stream.toByteArray()
        bytes.size shouldBe 100_001
        bytes.copyOfRange(10, 14).toList() shouldBe listOf<Byte>(1, 2, 3, 4)
        bytes[9] shouldBe 1.toByte()
        bytes[14] shouldBe 1.toByte()
        bytes[100_000] shouldBe 9.toByte()
    }
}

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
