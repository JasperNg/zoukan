package zoukan.desktop.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import tachiyomi.decoder.ImageFormats
import zoukan.desktop.download.ImageType

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
