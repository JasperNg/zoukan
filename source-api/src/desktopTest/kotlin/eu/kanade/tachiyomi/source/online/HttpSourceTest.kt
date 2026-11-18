package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The parts of HttpSource that decide what is stored: the source ID every library entry and backup
 * refers to, which must be the one the Android app computes, and the URL a source saves for an
 * entry. No request is made, so no network or Injekt setup is needed.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HttpSourceTest {

    private class Site(
        override val name: String,
        override val lang: String,
        override val versionId: Int = 1,
    ) : HttpSource() {
        override val baseUrl = "https://example.org"
        override val supportsLatest = false

        fun idFor(name: String, lang: String, versionId: Int) = generateId(name, lang, versionId)
        fun mangaUrl(url: String) = SManga.create().apply { setUrlWithoutDomain(url) }.url
        fun chapterUrl(url: String) = SChapter.create().apply { setUrlWithoutDomain(url) }.url
    }

    @Test
    fun `the source ID is the one the Android app computes`() {
        // A well-known ID: backups and the Android app's database refer to this source by it
        Site("MangaDex", "en").id shouldBe 2499283573021220255L
        // The name is lowercased first
        Site("MANGADEX", "en").id shouldBe 2499283573021220255L
    }

    @Test
    fun `the source ID changes with the language and the version and is never negative`() {
        val site = Site("Example", "en")
        val ids = listOf(
            site.idFor("Example", "en", 1),
            site.idFor("Example", "fr", 1),
            site.idFor("Example", "en", 2),
            site.idFor("Other", "en", 1),
        )
        ids.distinct().size shouldBe 4
        ids.all { it >= 0 } shouldBe true
        Site("Example", "en", versionId = 2).id shouldBe site.idFor("Example", "en", 2)
        // Many names: the sign bit is always cleared
        (1..500).all { site.idFor("Source $it", "all", 1) >= 0 } shouldBe true
    }

    @Test
    fun `a saved URL keeps the path, query and fragment and drops the scheme and host`() {
        val site = Site("Example", "en")
        site.mangaUrl("https://example.org/manga/1?lang=en#top") shouldBe "/manga/1?lang=en#top"
        site.mangaUrl("https://cdn.example.org:8443/manga/2") shouldBe "/manga/2"
        site.chapterUrl("/chapter/3") shouldBe "/chapter/3"
        site.chapterUrl("https://example.org/chapter/a b") shouldBe "/chapter/a b"
    }

    @Test
    fun `a URL that cannot be parsed is saved as it was given`() {
        val site = Site("Example", "en")
        site.mangaUrl("https://example.org/manga/{id}") shouldBe "https://example.org/manga/{id}"
        site.mangaUrl("https://example.org/%zz") shouldBe "https://example.org/%zz"
    }
}
