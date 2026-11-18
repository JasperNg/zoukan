package exh.metadata.metadata

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Gallery addresses as the built-in gallery source stores them. On desktop a full address is
 * parsed by the Android compatibility layer's Uri (the project's change here), so it must split
 * the same way Android's does.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EHentaiGalleryUrlTest {

    @Test
    fun `the gallery ID and token come from a full address`() {
        val url = "https://e-hentai.org/g/123456/abcdef0123/?p=2"
        EHentaiSearchMetadata.galleryId(url) shouldBe "123456"
        EHentaiSearchMetadata.galleryToken(url) shouldBe "abcdef0123"
    }

    @Test
    fun `a stored path and a full address normalize to the same stored URL`() {
        val expected = "/g/123456/abcdef0123/?nw=always"
        EHentaiSearchMetadata.normalizeUrl("https://exhentai.org/g/123456/abcdef0123/") shouldBe expected
        EHentaiSearchMetadata.normalizeUrl("/g/123456/abcdef0123/") shouldBe expected
        EHentaiSearchMetadata.normalizeUrl("/g/123456/abcdef0123/?nw=always") shouldBe expected
        // Doubled slashes are ignored
        EHentaiSearchMetadata.normalizeUrl("https://e-hentai.org//g//123456//abcdef0123") shouldBe expected
    }

    @Test
    fun `an ID and a token build the stored URL`() {
        EHentaiSearchMetadata.idAndTokenToUrl("1", "t") shouldBe "/g/1/t/?nw=always"
    }
}
