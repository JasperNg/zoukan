package tachiyomi.core.common.util.system

import eu.kanade.tachiyomi.util.lang.Hash
import exh.util.nullIfBlank
import exh.util.removeArticles
import exh.util.trimOrNull
import io.kotest.matchers.shouldBe
import mihon.core.common.utils.mutate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.util.QuerySanitizer.sanitize

/**
 * Small helpers whose output is stored or compared across both apps: hashes name cached files,
 * the URL checks decide whether a cover is fetched or read from storage.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TextAndUrlHelpersTest {

    @Test
    fun `hashes are lowercase hex of the UTF-8 bytes, as on Android`() {
        Hash.md5("") shouldBe "d41d8cd98f00b204e9800998ecf8427e"
        Hash.md5("https://example.org/cover.jpg") shouldBe "c9fd75b5d269f97c837834e658305a33"
        Hash.md5("日本") shouldBe "4dbed2e657457884e67137d3514119b3"
        Hash.md5("日本".toByteArray(Charsets.UTF_8)) shouldBe Hash.md5("日本")
        Hash.sha256("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `online, local and embedded addresses are told apart`() {
        listOf("https://a.example/x.jpg", "HTTP://a.example", "  https://a.example ", "ftp://a.example/f").forEach {
            UrlUtils.isOnlineUrl(it) shouldBe true
            UrlUtils.isLocalUrl(it) shouldBe false
        }
        listOf("file:///tmp/cover.jpg", "content://media/1", "/storage/cover.jpg", "./cover.jpg", "../cover.jpg").forEach {
            UrlUtils.isLocalUrl(it) shouldBe true
            UrlUtils.isOnlineUrl(it) shouldBe false
        }
        UrlUtils.isEmbeddedUrl("data:image/png;base64,AAAA") shouldBe true
        UrlUtils.isEmbeddedUrl("blob:https://a.example/1") shouldBe true
        UrlUtils.isWebUrl("ftp://a.example") shouldBe false
    }

    @Test
    fun `empty, blank and scheme-less addresses are neither online nor local`() {
        listOf(null, "", "   ", "cover.jpg", "javascript:alert(1)").forEach {
            UrlUtils.isOnlineUrl(it) shouldBe false
            UrlUtils.isWebUrl(it) shouldBe false
            UrlUtils.isEmbeddedUrl(it) shouldBe false
        }
        UrlUtils.getUrlScheme("HTTPS://a.example") shouldBe "https"
        UrlUtils.getUrlScheme(":nothing") shouldBe null
        UrlUtils.getUrlScheme("no scheme") shouldBe null
        UrlUtils.getUrlScheme(null) shouldBe null
    }

    @Test
    fun `a search query loses its separators at the ends and gets plain punctuation`() {
        "  “Tower” – of God…  ".sanitize() shouldBe "\"Tower\" - of God..."
        "Solo Leveling: Ragnarok".sanitize("Solo Leveling") shouldBe "Ragnarok"
        "--__,:".sanitize() shouldBe ""
        "It’s ‘fine’—really".sanitize() shouldBe "It's 'fine'-really"
    }

    @Test
    fun `string helpers drop leading articles and blank values`() {
        "The Promised Neverland".removeArticles() shouldBe "Promised Neverland"
        "An Archdemon's Dilemma".removeArticles() shouldBe "Archdemon's Dilemma"
        "Theater".removeArticles() shouldBe "Theater"
        "  ".trimOrNull() shouldBe null
        " x ".trimOrNull() shouldBe "x"
        "".nullIfBlank() shouldBe null
    }

    @Test
    fun `mutate returns a changed copy and leaves the original set alone`() {
        val original = setOf(1, 2)
        val changed = original.mutate {
            it += 3
            it -= 1
        }
        changed shouldBe setOf(2, 3)
        original shouldBe setOf(1, 2)
    }
}
