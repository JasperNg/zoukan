package zoukan.desktop.browser.blocking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The pure parts of the content blocker: host matching, list parsing and the block decision. */
class ContentBlockingTest {
    private val hosts = HostSet.of("ads.example.net", "tracker.example", "Popunder.Example.ORG.")

    @Test
    fun `a host set matches a host and its subdomains only`() {
        hosts.matches("ads.example.net") shouldBe true
        hosts.matches("cdn.ads.example.net") shouldBe true
        hosts.matches("ADS.example.net.") shouldBe true
        hosts.matches("popunder.example.org") shouldBe true
        hosts.matches("example.net") shouldBe false
        hosts.matches("badads.example.net") shouldBe false
        hosts.matches("") shouldBe false
        hosts.matches(null) shouldBe false
        HostSet.EMPTY.matches("ads.example.net") shouldBe false
    }

    @Test
    fun `the parser reads hosts files and whole-host filter rules`() {
        val text = """
            # a comment
            ! another comment
            [Adblock Plus 2.0]
            0.0.0.0 ads.example.net
            127.0.0.1 one.example two.example # trailing comment
            ::1 v6.example
            bare.example
            ||rule.example^
            ||third.example^${'$'}third-party
            ||Upper.Example^|
            0.0.0.0 0.0.0.0
            127.0.0.1 localhost
            192.168.1.1 router.example
            ||path.example/ads^
            ||wild*.example^
            ||script.example^${'$'}script
            @@||allowed.example^
            example.com##.ad-banner
            ##.ad
            not a host line at all
            1.2.3.4
            -bad.example
            singlelabel
        """.trimIndent()
        BlockListParser.parse(text) shouldBe listOf(
            "ads.example.net",
            "one.example",
            "two.example",
            "v6.example",
            "bare.example",
            "rule.example",
            "third.example",
            "upper.example",
        )
    }

    @Test
    fun `the parser stops at its limit and drops repeats`() {
        val text = (1..50).joinToString("\n") { "host$it.example" } + "\nhost1.example"
        BlockListParser.parse(text, limit = 10).size shouldBe 10
        BlockListParser.parse("a.example\na.example\nA.EXAMPLE") shouldBe listOf("a.example")
    }

    @Test
    fun `a listed host of another site is blocked`() {
        BlockPolicy.shouldBlock("https://cdn.ads.example.net/a.js", "https://reader.example.com/ch/1", hosts) shouldBe true
        BlockPolicy.shouldBlock("http://tracker.example/pixel.gif?x=1", "https://reader.example.com/", hosts) shouldBe true
    }

    @Test
    fun `a site's own hosts are never blocked, even when listed`() {
        BlockPolicy.shouldBlock("https://ads.example.net/banner.png", "https://www.example.net/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://tracker.example/x", "https://tracker.example/page", hosts) shouldBe false
    }

    @Test
    fun `unlisted hosts and unreadable addresses are let through`() {
        BlockPolicy.shouldBlock("https://cdn.example.com/a.js", "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", null, hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", "about:blank", hosts) shouldBe false
        BlockPolicy.shouldBlock("data:text/plain,hi", "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock(null, "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", "https://reader.example.org/", HostSet.EMPTY) shouldBe false
    }

    @Test
    fun `Cloudflare, captcha and sign-in hosts are never blocked, whatever a list says`() {
        val everything = HostSet.of(
            "cloudflare.com",
            "hcaptcha.com",
            "recaptcha.net",
            "google.com",
            "gstatic.com",
            "anilist.co",
            "myanimelist.net",
            "ads.example.net",
        )
        val page = "https://reader.example.org/"
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://newassets.hcaptcha.com/captcha/v1/x.js",
            "https://www.recaptcha.net/recaptcha/api.js",
            "https://www.google.com/recaptcha/api2/anchor",
            "https://www.gstatic.com/recaptcha/releases/x/recaptcha__en.js",
            "https://anilist.co/api/v2/oauth/authorize",
            "https://myanimelist.net/v1/oauth2/authorize",
            "https://ads.example.net/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1",
        ).forEach { url -> BlockPolicy.shouldBlock(url, page, everything) shouldBe false }
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, everything) shouldBe true
    }

    @Test
    fun `same site compares registrable domains`() {
        BlockPolicy.sameSite("https://a.example.co.uk/x", "https://b.example.co.uk/y") shouldBe true
        BlockPolicy.sameSite("https://example.co.uk/", "https://other.co.uk/") shouldBe false
        BlockPolicy.sameSite("https://example.com/", "not a url") shouldBe false
    }

    @Test
    fun `the built-in list is valid and keeps clear of the hosts checks and sign-ins need`() {
        val text = ContentBlocker::class.java.getResourceAsStream("blocklist.txt")!!.use { it.readBytes().decodeToString() }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        // Every entry is a host the parser takes
        BlockListParser.parse(text) shouldBe lines.distinct()
        (lines.size > 50) shouldBe true
        val page = "https://reader.example.org/"
        val list = HostSet.of(lines)
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://www.google.com/recaptcha/api.js",
            "https://www.gstatic.com/recaptcha/x.js",
            "https://hcaptcha.com/1/api.js",
            "https://fonts.googleapis.com/css2",
            "https://cdnjs.cloudflare.com/ajax/libs/x.js",
            "https://cdn.jsdelivr.net/npm/x",
            "https://ajax.googleapis.com/ajax/libs/jquery/3/jquery.min.js",
        ).forEach { url -> BlockPolicy.shouldBlock(url, page, list) shouldBe false }
        // The main source of pop-unders and ads on these sites is
        BlockPolicy.shouldBlock("https://pagead2.googlesyndication.com/x.js", page, list) shouldBe true
        // No entry is a whole public suffix
        lines.filter { !it.contains('.') }.shouldBeEmpty()
    }

    @Test
    fun `the parser reads whole-host exception rules apart from blocked hosts`() {
        val text = """
            ||ads.example^
            @@||good.example^
            @@||Third.Example^${'$'}third-party
            @@||path.example/x^
            @@||doc.example^${'$'}document
            @@ads.example
        """.trimIndent()
        val rules = BlockListParser.parseRules(text)
        rules.hosts shouldBe listOf("ads.example")
        rules.exceptions shouldBe listOf("good.example", "third.example")
        BlockListParser.parseRules("@@||a.example^\n||b.example^\n||c.example^", limit = 2) shouldBe
            BlockListParser.Rules(listOf("b.example"), listOf("a.example"))
    }

    @Test
    fun `an exception host and its subdomains are never blocked, by any list`() {
        val page = "https://reader.example.org/"
        val exceptions = HostSet.of("ads.example.net")
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, hosts, exceptions) shouldBe false
        BlockPolicy.shouldBlock("https://cdn.ads.example.net/a.js", page, hosts, exceptions) shouldBe false
        // Other hosts on the list are still blocked
        BlockPolicy.shouldBlock("https://tracker.example/x", page, hosts, exceptions) shouldBe true
        // An exception for a subdomain leaves its parent blocked
        val subdomainOnly = HostSet.of("cdn.ads.example.net")
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, hosts, subdomainOnly) shouldBe true
    }

    @Test
    fun `block rules combine hosts and exceptions`() {
        val builtIn = BlockRules(HostSet.of("ads.example"))
        val user = BlockRules(HostSet.of("more.example"), HostSet.of("ads.example"))
        val all = builtIn + user
        all.hosts.matches("ads.example") shouldBe true
        all.hosts.matches("more.example") shouldBe true
        all.exceptions.matches("ads.example") shouldBe true
        (builtIn + BlockRules.EMPTY) shouldBe builtIn
        BlockRules.EMPTY.isEmpty shouldBe true
    }
}
